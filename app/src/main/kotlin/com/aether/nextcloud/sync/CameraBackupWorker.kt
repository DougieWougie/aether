package com.aether.nextcloud.sync

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aether.nextcloud.R
import com.aether.nextcloud.auth.AccountRepository
import com.aether.nextcloud.auth.AuthIssues
import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.core.AetherError
import com.aether.nextcloud.core.AetherResult
import com.aether.nextcloud.data.local.AetherDatabase
import com.aether.nextcloud.data.local.entity.BackedUpMediaEntity
import com.aether.nextcloud.data.prefs.BackupSettings
import com.aether.nextcloud.data.prefs.SettingsRepository
import com.aether.nextcloud.dav.Checksums
import com.aether.nextcloud.dav.WebDavClient
import com.aether.nextcloud.dav.WebDavClientFactory
import com.aether.nextcloud.dav.relativeRemotePath
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Uploads new photos and videos from MediaStore to `<folder>/YYYY/MM/`. Woken by `ContentUriTriggers` (and a periodic
 * safety net); each run scans for items added since backup was enabled that are not yet in [BackedUpMediaEntity].
 */
@HiltWorker
class CameraBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val accounts: AccountRepository,
    private val davFactory: WebDavClientFactory,
    private val database: AetherDatabase,
    private val settingsRepository: SettingsRepository,
    private val notifications: TransferNotificationHelper,
    private val authIssues: AuthIssues,
) : CoroutineWorker(context, params) {

    private data class MediaItem(
        val id: Long,
        val uri: Uri,
        val name: String,
        val mimeType: String?,
        val size: Long,
        val modifiedMillis: Long,
        val takenMillis: Long,
        val isVideo: Boolean,
        val bucketId: Long?,
        val bucketName: String?,
    )

    override suspend fun getForegroundInfo(): ForegroundInfo =
        notifications.foregroundInfo(id, applicationContext.getString(R.string.notification_backing_up))

    override suspend fun doWork(): Result {
        val triggered = inputData.getBoolean(KEY_TRIGGERED, false)
        val settings = settingsRepository.backup.first()
        val result = if (!settings.enabled || !hasMediaPermission()) {
            Result.success()
        } else {
            val credentials = settings.accountName?.let(accounts::credentials)
            if (credentials == null) Result.success() else scanMutex.withLock { backUpNewMedia(settings, credentials) }
        }
        // Content-URI triggers are one-shot: queue the next watcher unless this run will be retried (it keeps its trigger).
        if (triggered && settings.enabled && result != Result.retry()) scheduleTrigger(applicationContext, settings, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return result
    }

    private suspend fun backUpNewMedia(settings: BackupSettings, credentials: AetherCredentials): Result {
        val items = withContext(Dispatchers.IO) { queryNewMedia(settings) }
        if (items.isEmpty()) return Result.success()
        runCatching { setForeground(getForegroundInfo()) } // not allowed for background-started runs; best effort

        val dav = davFactory.forAccount(credentials)
        val createdDirectories = HashSet<String>()
        for (item in items) {
            if (database.backedUpMediaDao().find(credentials.accountName, item.id, item.isVideo) != null) continue
            when (val outcome = backUp(item, dav, credentials, settings, createdDirectories)) {
                is AetherResult.Success -> Unit
                is AetherResult.Failure -> when (val error = outcome.error) {
                    is AetherError.Network -> return Result.retry()
                    is AetherError.Http -> if (error.code >= 500) return Result.retry() // else: skip this item, keep going
                    AetherError.Unauthorized -> {
                        authIssues.report(credentials.accountName)
                        return Result.failure()
                    }
                    else -> Unit
                }
            }
        }
        return Result.success()
    }

    private suspend fun backUp(
        item: MediaItem,
        dav: WebDavClient,
        credentials: AetherCredentials,
        settings: BackupSettings,
        createdDirectories: MutableSet<String>,
    ): AetherResult<Unit> {
        var remotePath = BackupPaths.remotePath(settings.remoteFolder, item.takenMillis, item.name)

        for (directory in BackupPaths.directoriesFor(remotePath)) {
            if (!createdDirectories.add(directory)) continue
            val made = dav.mkcol(credentials.fileUrl("$directory/"))
            // 405 Method Not Allowed is what MKCOL answers for a folder that already exists.
            if (made is AetherResult.Failure && !(made.error is AetherError.Http && made.error.code == 405)) {
                createdDirectories.remove(directory)
                return made
            }
        }

        // Never overwrite: a same-named file of the same content is this item from an earlier install; a different one forces a new name.
        val plain = ContentUriUploadSource(applicationContext.contentResolver, item.uri, item.size, item.modifiedMillis)
        var source = plain
        when (val existing = dav.propfind(credentials.fileUrl(remotePath), depth = 0)) {
            is AetherResult.Success -> {
                val remote = existing.value.firstOrNull()
                if (remote?.contentLength == item.size) {
                    // Same name and size. When the server holds a SHA-1 the content decides; without one the size has to do.
                    val remoteSha1 = Checksums.sha1Of(remote.checksums)
                    if (remoteSha1 != null) source = withContext(Dispatchers.IO) { plain.withChecksum() }
                    if (remoteSha1 == null || remoteSha1 == source.checksum) {
                        record(credentials, item, remotePath)
                        return AetherResult.Success(Unit)
                    }
                }
                remotePath = BackupPaths.remotePath(settings.remoteFolder, item.takenMillis, BackupPaths.withMediaId(item.name, item.id))
            }
            is AetherResult.Failure -> if (existing.error != AetherError.NotFound) return existing
        }

        // Send a SHA-1 so the server verifies the upload and a later run (or a reinstall) can tell this file from another.
        if (source.checksum == null) source = withContext(Dispatchers.IO) { plain.withChecksum() }
        val upload = if (item.size > WebDavClient.CHUNKED_THRESHOLD) {
            dav.uploadChunkedV2(source, remotePath)
        } else {
            dav.uploadFile(credentials.fileUrl(remotePath), source, item.mimeType)
        }
        return when (upload) {
            is AetherResult.Success -> AetherResult.Success(Unit).also { record(credentials, item, remotePath) }
            is AetherResult.Failure -> upload
        }
    }

    private suspend fun record(credentials: AetherCredentials, item: MediaItem, remotePath: String) = withContext(Dispatchers.IO) {
        database.backedUpMediaDao().upsert(
            BackedUpMediaEntity(
                accountName = credentials.accountName,
                mediaId = item.id,
                isVideo = item.isVideo,
                dateModified = item.modifiedMillis,
                size = item.size,
                remotePath = remotePath,
                backedUpAt = System.currentTimeMillis(),
                bucketId = item.bucketId,
                bucketName = item.bucketName,
            ),
        )
    }

    private fun queryNewMedia(settings: BackupSettings): List<MediaItem> {
        val sinceSeconds = settings.backupSinceMillis / 1000
        val albumIds = settings.albums?.map { it.id }
        if (albumIds != null && albumIds.isEmpty()) return emptyList()
        val items = queryCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false, sinceSeconds, albumIds)
        val videos = if (settings.includeVideos) queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true, sinceSeconds, albumIds) else emptyList()
        return (items + videos).sortedBy { it.modifiedMillis }
    }

    private fun queryCollection(collection: Uri, isVideo: Boolean, sinceSeconds: Long, albumIds: List<Long>?): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        )
        val albumFilter = albumIds?.let { " AND ${MediaStore.MediaColumns.BUCKET_ID} IN (${it.joinToString(",") { "?" }})" }.orEmpty()
        val selection = "${MediaStore.MediaColumns.DATE_ADDED} > ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0 AND ${MediaStore.MediaColumns.SIZE} > 0$albumFilter"
        val selectionArgs = arrayOf(sinceSeconds.toString()) + (albumIds?.map { it.toString() } ?: emptyList())
        val result = mutableListOf<MediaItem>()
        applicationContext.contentResolver.query(
            collection, projection, selection, selectionArgs, "${MediaStore.MediaColumns.DATE_ADDED} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val modified = cursor.getLong(4) * 1000
                result += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    name = cursor.getString(1) ?: "media_$id",
                    mimeType = cursor.getString(2),
                    size = cursor.getLong(3),
                    modifiedMillis = modified,
                    takenMillis = cursor.getLong(5).takeIf { it > 0 } ?: modified,
                    isVideo = isVideo,
                    bucketId = if (cursor.isNull(6)) null else cursor.getLong(6),
                    bucketName = cursor.getString(7),
                )
            }
        }
        return result
    }

    private fun hasMediaPermission(): Boolean {
        val permissions = when {
            Build.VERSION.SDK_INT >= 34 -> listOf(
                Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= 33 -> listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return permissions.any { ContextCompat.checkSelfPermission(applicationContext, it) == PackageManager.PERMISSION_GRANTED }
    }

    companion object {
        private const val KEY_TRIGGERED = "triggered"
        private const val TRIGGER_NAME = "camera-backup-trigger"
        private const val PERIODIC_NAME = "camera-backup-periodic"

        /** Runs never overlap: the trigger and periodic workers share the same scan. */
        private val scanMutex = Mutex()

        fun constraints(settings: BackupSettings): Constraints.Builder = Constraints.Builder()
            .setRequiredNetworkType(if (settings.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(settings.chargingOnly)
            .setRequiresBatteryNotLow(true)

        fun scheduleTrigger(context: Context, settings: BackupSettings, policy: ExistingWorkPolicy) {
            val constraints = constraints(settings)
                .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                .apply { if (settings.includeVideos) addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true) }
                .setTriggerContentUpdateDelay(5, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(30, TimeUnit.SECONDS)
                .build()
            val request = OneTimeWorkRequestBuilder<CameraBackupWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_TRIGGERED to true))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(TRIGGER_NAME, policy, request)
        }

        /** Safety net for events missed while the process was dead, and for items the first scan could not finish. */
        fun schedulePeriodic(context: Context, settings: BackupSettings) {
            val request = PeriodicWorkRequestBuilder<CameraBackupWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints(settings).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** True while either the content-trigger or the periodic backup run is actually executing. */
        fun observeRunning(context: Context): Flow<Boolean> {
            val manager = WorkManager.getInstance(context)
            return combine(
                manager.getWorkInfosForUniqueWorkFlow(TRIGGER_NAME),
                manager.getWorkInfosForUniqueWorkFlow(PERIODIC_NAME),
            ) { trigger, periodic -> (trigger + periodic).any { it.state == WorkInfo.State.RUNNING } }.distinctUntilChanged()
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(TRIGGER_NAME)
                cancelUniqueWork(PERIODIC_NAME)
            }
        }
    }
}
