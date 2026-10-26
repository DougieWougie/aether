package com.aether.nextcloud.sync

import android.content.Context
import android.provider.DocumentsContract
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aether.nextcloud.AetherConstants
import com.aether.nextcloud.auth.AccountRepository
import com.aether.nextcloud.auth.AuthIssues
import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.core.AetherError
import com.aether.nextcloud.core.AetherResult
import com.aether.nextcloud.core.describe
import com.aether.nextcloud.data.DocumentRepository
import com.aether.nextcloud.data.local.AetherDatabase
import com.aether.nextcloud.data.local.entity.TransferDirection
import com.aether.nextcloud.data.local.entity.TransferEntity
import com.aether.nextcloud.data.local.entity.TransferState
import com.aether.nextcloud.dav.FileUploadSource
import com.aether.nextcloud.dav.UploadResult
import com.aether.nextcloud.dav.WebDavClient
import com.aether.nextcloud.dav.WebDavClientFactory
import com.aether.nextcloud.provider.DocumentId
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One file to push to Nextcloud. [sourceFile] must be app-private; it is deleted after a settled upload. */
data class UploadRequest(
    val accountName: String,
    val remotePath: String,
    val sourceFile: File,
    val mimeType: String?,
    /** ETag the edit was based on (`If-Match`); null for brand-new files, which then must not overwrite anything. */
    val ifMatchEtag: String?,
)

/**
 * Pushes a staged file to Nextcloud. Used for SAF write-backs and Share Sheet uploads. Large files go through
 * Chunked Upload v2 under a `dataSync` foreground notification; a `412` (remote changed) saves a conflict copy.
 */
@HiltWorker
class UploadDocumentWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val accounts: AccountRepository,
    private val davFactory: WebDavClientFactory,
    private val documents: DocumentRepository,
    private val database: AetherDatabase,
    private val notifications: TransferNotificationHelper,
    private val authIssues: AuthIssues,
) : CoroutineWorker(context, params) {

    private val transfers = database.transferDao()

    override suspend fun getForegroundInfo(): ForegroundInfo =
        notifications.foregroundInfo(id, applicationContext.getString(com.aether.nextcloud.R.string.notification_uploading, inputData.getString(KEY_REMOTE_PATH)?.substringAfterLast('/') ?: ""))

    override suspend fun doWork(): Result {
        val accountName = inputData.getString(KEY_ACCOUNT) ?: return Result.failure()
        val remotePath = inputData.getString(KEY_REMOTE_PATH) ?: return Result.failure()
        val source = inputData.getString(KEY_SOURCE_PATH)?.let(::File) ?: return Result.failure()
        val mimeType = inputData.getString(KEY_MIME_TYPE)
        val ifMatch = inputData.getString(KEY_IF_MATCH_ETAG)
        if (!source.isFile) return Result.failure()
        val credentials = accounts.credentials(accountName) ?: run { source.delete(); return Result.failure() }
        val target = DocumentId(accountName, DocumentId.normalizePath(remotePath))
        val total = source.length()

        val transferId = withContext(Dispatchers.IO) {
            // A retry continues the row the previous attempt left QUEUED rather than adding a second one.
            val previous = if (runAttemptCount > 0) transfers.queuedUploadFor(source.absolutePath) else null
            if (previous != null) {
                transfers.setState(previous, TransferState.RUNNING, null, System.currentTimeMillis())
                previous
            } else {
                transfers.insert(
                    TransferEntity(
                        accountName = accountName,
                        remotePath = target.path,
                        direction = TransferDirection.UPLOAD,
                        state = TransferState.RUNNING,
                        totalBytes = total,
                        createdAt = System.currentTimeMillis(),
                        sourcePath = source.absolutePath,
                        mimeType = mimeType,
                        ifMatchEtag = ifMatch,
                    ),
                )
            }
        }
        val large = total > WebDavClient.CHUNKED_THRESHOLD
        if (large) setForeground(getForegroundInfo())

        val progress = ThrottledProgress(transferId, total, large)
        return try {
            val dav = davFactory.forAccount(credentials)
            val first = upload(dav, credentials, target, source, mimeType, ifMatch, total, progress)
            when {
                first is AetherResult.Success -> finish(credentials, target, source, first.value, transferId, TransferState.COMPLETED)
                first is AetherResult.Failure && first.error == AetherError.PreconditionFailed ->
                    saveConflictCopy(dav, credentials, target, source, mimeType, total, progress, transferId)
                first is AetherResult.Failure -> failed(first.error, transferId)
                else -> Result.failure()
            }
        } catch (e: CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable) { setTransferState(transferId, TransferState.FAILED, "Cancelled") }
            throw e
        }
    }

    private suspend fun upload(
        dav: WebDavClient,
        credentials: AetherCredentials,
        target: DocumentId,
        source: File,
        mimeType: String?,
        ifMatch: String?,
        total: Long,
        progress: ThrottledProgress,
    ): AetherResult<UploadResult> =
        if (total > WebDavClient.CHUNKED_THRESHOLD) {
            dav.uploadChunkedV2(FileUploadSource(source), target.path, ifMatch, { done, size -> progress.report(done, size) }, createOnly = true)
        } else {
            dav.uploadFile(credentials.fileUrl(target.path), FileUploadSource(source), mimeType, ifMatch, { done, size -> progress.report(done, size) }, createOnly = true)
        }

    private suspend fun saveConflictCopy(
        dav: WebDavClient,
        credentials: AetherCredentials,
        target: DocumentId,
        source: File,
        mimeType: String?,
        total: Long,
        progress: ThrottledProgress,
        transferId: Long,
    ): Result {
        val parent = target.parent ?: return failed(AetherError.PreconditionFailed, transferId)
        val conflict = parent.child(ConflictNaming.conflictName(target.name, System.currentTimeMillis()))
        return when (val copy = upload(dav, credentials, conflict, source, mimeType, null, total, progress)) {
            is AetherResult.Success -> {
                notifications.notifyConflict(credentials.accountName, target.name, conflict.name)
                finish(credentials, conflict, source, copy.value, transferId, TransferState.CONFLICT, refreshOriginal = target)
            }
            is AetherResult.Failure -> failed(copy.error, transferId)
        }
    }

    private suspend fun finish(
        credentials: AetherCredentials,
        uploaded: DocumentId,
        source: File,
        result: UploadResult,
        transferId: Long,
        state: TransferState,
        refreshOriginal: DocumentId? = null,
    ): Result {
        // Keep the uploaded bytes as the cached copy so the next read does not re-download them.
        if (state == TransferState.COMPLETED) documents.adoptUploadedFile(credentials, uploaded, source, result.etag)
        else documents.refreshDocument(credentials, uploaded)
        refreshOriginal?.let { documents.refreshDocument(credentials, it) }
        source.delete()
        setTransferState(transferId, state, null)
        uploaded.parent?.let { notifyChildrenChanged(it) }
        return Result.success()
    }

    private suspend fun failed(error: AetherError, transferId: Long): Result {
        // A server-side lock normally clears once the other editor closes, so it is retried like a transient failure.
        val retryable = error is AetherError.Network || error == AetherError.Locked || (error is AetherError.Http && error.code >= 500)
        if (retryable && runAttemptCount < MAX_ATTEMPTS) {
            setTransferState(transferId, TransferState.QUEUED, error.describe())
            return Result.retry()
        }
        setTransferState(transferId, TransferState.FAILED, error.describe())
        // The staged bytes stay: the failed row points at them so Retry failed can send them again. Pruning the row deletes them.
        if (error == AetherError.Unauthorized) inputData.getString(KEY_ACCOUNT)?.let(authIssues::report)
        return Result.failure()
    }

    private suspend fun setTransferState(id: Long, state: TransferState, error: String?) = withContext(Dispatchers.IO) {
        transfers.setState(id, state, error, System.currentTimeMillis())
    }

    private fun notifyChildrenChanged(parent: DocumentId) {
        applicationContext.contentResolver.notifyChange(
            DocumentsContract.buildChildDocumentsUri(AetherConstants.AUTHORITY, parent.toString()), null,
        )
    }

    /** Updates Room and the notification at most every [MIN_UPDATE_INTERVAL_MS] so progress callbacks stay cheap. */
    private inner class ThrottledProgress(private val transferId: Long, private val total: Long, private val foreground: Boolean) {
        private var lastUpdate = 0L

        fun report(done: Long, size: Long) {
            val now = System.currentTimeMillis()
            if (done < total && now - lastUpdate < MIN_UPDATE_INTERVAL_MS) return
            lastUpdate = now
            transfers.setProgress(transferId, done, size, now)
            if (foreground) {
                val name = inputData.getString(KEY_REMOTE_PATH)?.substringAfterLast('/') ?: ""
                val info = notifications.foregroundInfo(
                    id, applicationContext.getString(com.aether.nextcloud.R.string.notification_uploading, name), done, size,
                )
                setForegroundAsync(info)
            }
        }
    }

    companion object {
        const val KEY_ACCOUNT = "account"
        const val KEY_REMOTE_PATH = "remote_path"
        const val KEY_SOURCE_PATH = "source_path"
        const val KEY_MIME_TYPE = "mime_type"
        const val KEY_IF_MATCH_ETAG = "if_match_etag"
        private const val MAX_ATTEMPTS = 5
        private const val MIN_UPDATE_INTERVAL_MS = 500L

        fun inputData(request: UploadRequest): Data = workDataOf(
            KEY_ACCOUNT to request.accountName,
            KEY_REMOTE_PATH to request.remotePath,
            KEY_SOURCE_PATH to request.sourceFile.absolutePath,
            KEY_MIME_TYPE to request.mimeType,
            KEY_IF_MATCH_ETAG to request.ifMatchEtag,
        )

        private fun accountTag(accountName: String) = "upload-account:$accountName"

        /** Cancels every queued or running upload for [accountName]; the caller removes the staged files. */
        fun cancelAll(context: Context, accountName: String) {
            WorkManager.getInstance(context).cancelAllWorkByTag(accountTag(accountName))
        }

        /** Expedited, network-constrained, serialized per remote path so consecutive saves upload in order. */
        fun enqueue(context: Context, request: UploadRequest): UUID {
            val work = OneTimeWorkRequestBuilder<UploadDocumentWorker>()
                .setInputData(inputData(request))
                .addTag(accountTag(request.accountName))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("upload:${request.accountName}:${request.remotePath}", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
            return work.id
        }
    }
}
