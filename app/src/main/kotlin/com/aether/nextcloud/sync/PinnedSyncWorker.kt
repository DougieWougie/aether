package com.aether.nextcloud.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aether.nextcloud.R
import com.aether.nextcloud.auth.AccountRepository
import com.aether.nextcloud.auth.AuthIssues
import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.core.AetherError
import com.aether.nextcloud.core.AetherResult
import com.aether.nextcloud.core.describe
import com.aether.nextcloud.data.DocumentRepository
import com.aether.nextcloud.data.local.AetherDatabase
import com.aether.nextcloud.data.local.entity.DocumentEntity
import com.aether.nextcloud.data.local.entity.TransferDirection
import com.aether.nextcloud.data.local.entity.TransferEntity
import com.aether.nextcloud.data.local.entity.TransferState
import com.aether.nextcloud.provider.DocumentId
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Keeps offline pins current. A pinned folder's ETag changes whenever anything below it does (ADR-0004), so a folder whose
 * ETag still equals the one recorded at its last full sync is skipped without listing it.
 */
@HiltWorker
class PinnedSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val accounts: AccountRepository,
    private val documents: DocumentRepository,
    private val database: AetherDatabase,
    private val notifications: TransferNotificationHelper,
    private val authIssues: AuthIssues,
) : CoroutineWorker(context, params) {

    private val transfers = database.transferDao()

    /** Set when a failure is worth retrying later (offline, 5xx); permanent failures are only reported. */
    private var retryNeeded = false
    private var failures = 0

    override suspend fun getForegroundInfo(): ForegroundInfo =
        notifications.foregroundInfo(id, applicationContext.getString(R.string.notification_syncing_pins))

    override suspend fun doWork(): Result {
        if (inputData.getBoolean(KEY_FOREGROUND, false)) {
            // Only expedited runs may start a foreground service from the background; periodic runs go without.
            runCatching { setForeground(getForegroundInfo()) }
        }
        val pinned = documents.pinnedDocuments()
        val roots = pinned.filter { doc -> pinned.none { other -> other.accountName == doc.accountName && DocumentId.parse(other.documentId).isAncestorOf(DocumentId.parse(doc.documentId)) } }

        for (root in roots) {
            val credentials = accounts.credentials(root.accountName) ?: continue
            syncNode(credentials, DocumentId.parse(root.documentId))
        }
        withContext(Dispatchers.IO) { transfers.pruneFinished(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)) }
        return if (retryNeeded) Result.retry() else Result.success()
    }

    private suspend fun syncNode(credentials: AetherCredentials, id: DocumentId) {
        val before = documents.cached(id)
        val node = when (val refreshed = documents.refreshDocument(credentials, id)) {
            is AetherResult.Success -> refreshed.value
            is AetherResult.Failure -> return noteFailure(credentials, refreshed.error)
        }
        if (!node.isDirectory) return syncFile(credentials, node)

        // Unchanged since the last complete sync: nothing below this folder can have changed.
        if (node.etag != null && node.etag == before?.cachedEtag) return

        val listed = when (val result = documents.refreshChildren(credentials, id)) {
            is AetherResult.Success -> result.value
            is AetherResult.Failure -> return noteFailure(credentials, result.error)
        }
        documents.pinChildren(id)
        val failuresBefore = failures
        for (child in documents.cachedChildren(id)) {
            val childId = DocumentId.parse(child.documentId)
            if (child.isDirectory) syncNode(credentials, childId) else syncFile(credentials, child.copy(isPinned = true))
        }
        if (failures == failuresBefore) documents.markSubtreeSynced(id, listed.etag)
    }

    private suspend fun syncFile(credentials: AetherCredentials, file: DocumentEntity) {
        val id = DocumentId.parse(file.documentId)
        val current = documents.cached(id) ?: file
        if (documents.hasCurrentPinnedCopy(current)) return

        val transferId = withContext(Dispatchers.IO) {
            transfers.insert(
                TransferEntity(
                    accountName = id.accountName,
                    remotePath = id.path,
                    direction = TransferDirection.DOWNLOAD,
                    state = TransferState.RUNNING,
                    totalBytes = file.size,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        var lastReport = 0L
        try {
            val result = documents.ensurePinnedCopy(credentials, id) { done, total ->
                val now = System.currentTimeMillis()
                if (now - lastReport > PROGRESS_INTERVAL_MS) {
                    lastReport = now
                    transfers.setProgress(transferId, done, total, now)
                }
            }
            when (result) {
                is AetherResult.Success -> setState(transferId, TransferState.COMPLETED, null)
                is AetherResult.Failure -> {
                    setState(transferId, TransferState.FAILED, result.error.describe())
                    noteFailure(credentials, result.error)
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { setState(transferId, TransferState.FAILED, "Cancelled") }
            throw e
        }
    }

    private fun noteFailure(credentials: AetherCredentials, error: AetherError) {
        failures++
        if (error == AetherError.Unauthorized) authIssues.report(credentials.accountName)
        if (error is AetherError.Network || (error is AetherError.Http && error.code >= 500)) retryNeeded = true
    }

    private suspend fun setState(id: Long, state: TransferState, error: String?) = withContext(Dispatchers.IO) {
        transfers.setState(id, state, error, System.currentTimeMillis())
    }

    companion object {
        private const val KEY_FOREGROUND = "foreground"
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val PERIODIC_NAME = "pinned-sync-periodic"
        private const val NOW_NAME = "pinned-sync-now"

        /** Every 6 hours; [unmeteredOnly] mirrors the user's sync constraint. Re-applying updates the constraint in place. */
        fun schedulePeriodic(context: Context, unmeteredOnly: Boolean) {
            val request = PeriodicWorkRequestBuilder<PinnedSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints(unmeteredOnly))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** User-triggered sync: expedited and shown as a foreground notification. */
        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<PinnedSyncWorker>()
                // Expedited work accepts only network/storage constraints (no battery constraint).
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_FOREGROUND to true))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW_NAME, ExistingWorkPolicy.KEEP, request)
        }

        private fun constraints(unmeteredOnly: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
    }
}
