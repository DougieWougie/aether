package com.aether.nextcloud.sync

import android.content.Context
import com.aether.nextcloud.data.local.AetherDatabase
import com.aether.nextcloud.data.local.entity.TransferDirection
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [retried] failed rows were queued again (and removed); [skipped] could not be, because the file to send is gone. */
data class RetryOutcome(val retried: Int, val skipped: Int)

/**
 * Queues an account's failed transfers again. What "again" means depends on the row: a staged upload is re-enqueued
 * from its kept file, a camera item is picked up by a new scan, and a download by a pinned sync. Each retried row is
 * removed because the new attempt writes its own row.
 */
@Singleton
class TransferRetrier @Inject constructor(
    @ApplicationContext private val context: Context,
    database: AetherDatabase,
    private val scheduler: SyncScheduler,
) {
    private val transfers = database.transferDao()

    suspend fun retryFailed(accountName: String): RetryOutcome = withContext(Dispatchers.IO) {
        var retried = 0
        var skipped = 0
        var scanCamera = false
        var syncPins = false
        for (row in transfers.failed(accountName)) {
            val staged = row.sourcePath?.let(::File)
            when {
                row.mediaKey != null -> scanCamera = true
                staged != null && staged.isFile -> {
                    // Enqueue first: if the process dies in between, the row is still there to retry again.
                    UploadDocumentWorker.enqueue(context, UploadRequest(accountName, row.remotePath, staged, row.mimeType, row.ifMatchEtag, createParents = row.ifMatchEtag == null))
                }
                row.direction == TransferDirection.DOWNLOAD -> syncPins = true
                else -> { skipped++; continue }
            }
            transfers.delete(row.id)
            retried++
        }
        if (scanCamera) scheduler.backUpNow()
        if (syncPins) scheduler.syncPinsNow()
        RetryOutcome(retried, skipped)
    }
}
