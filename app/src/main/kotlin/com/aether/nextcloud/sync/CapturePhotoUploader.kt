package com.aether.nextcloud.sync

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.aether.nextcloud.auth.AccountRepository
import com.aether.nextcloud.data.cache.DocumentDiskCache
import com.aether.nextcloud.data.local.AetherDatabase
import com.aether.nextcloud.data.local.entity.BackedUpMediaEntity
import com.aether.nextcloud.data.local.entity.TransferDirection
import com.aether.nextcloud.data.local.entity.TransferEntity
import com.aether.nextcloud.data.local.entity.TransferState
import com.aether.nextcloud.data.prefs.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** What happened to a photo the user just took. */
sealed interface CaptureOutcome {
    /** The photo is staged and queued; [remotePath] is where it will land. */
    data class Queued(val remotePath: String) : CaptureOutcome
    /** No signed-in account to upload to. */
    data object NoAccount : CaptureOutcome
    /** The photo could not be read back from where the camera app saved it. */
    data object Unreadable : CaptureOutcome
}

/**
 * Photos taken from the Camera tab. The system camera app writes into a file in this app's cache (shared with it through
 * `FileProvider`; a MediaStore row can't be used, because a pending one is only writable by its owner and the camera app
 * crashes on it). When the photo comes back it is staged and uploaded to the camera backup folder as
 * `<folder>/YYYY/MM/<name>`, and a copy is added to the gallery under Pictures/Aether. It works whether or not camera backup
 * is on.
 *
 * The gallery copy is written to the backed-up log before it becomes visible to MediaStore scans, so camera backup does not
 * upload the same photo a second time while this upload is still queued.
 */
@Singleton
class CapturePhotoUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val accounts: AccountRepository,
    private val diskCache: DocumentDiskCache,
    private val database: AetherDatabase,
) {
    private val captureDir get() = File(context.cacheDir, "captures")

    /** A fresh file for the camera app to write into, or null if one can't be made. */
    suspend fun newCaptureUri(now: Long = System.currentTimeMillis()): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            captureDir.apply { mkdirs() }.listFiles()?.filter { now - it.lastModified() > STALE_MS }?.forEach(File::delete)
            val file = File(captureDir, BackupPaths.captureName(now))
            FileProvider.getUriForFile(context, "${context.packageName}.captures", file)
        }.getOrNull()
    }

    /** The camera was cancelled or failed: drop the empty file. */
    suspend fun discard(uri: Uri) {
        withContext(Dispatchers.IO) { fileFor(uri)?.delete() }
    }

    /**
     * Stages the photo the camera app wrote to [uri] and queues its upload. [preferredAccount] is used when camera backup has
     * no account of its own.
     */
    suspend fun upload(uri: Uri, preferredAccount: String?, now: Long = System.currentTimeMillis()): CaptureOutcome {
        val backup = settings.backup.first()
        val accountName = (backup.accountName ?: preferredAccount)?.takeIf { accounts.credentials(it) != null }
        if (accountName == null) {
            discard(uri)
            return CaptureOutcome.NoAccount
        }
        val captured = withContext(Dispatchers.IO) { fileFor(uri)?.takeIf { it.length() > 0 } }
        if (captured == null) {
            discard(uri)
            return CaptureOutcome.Unreadable
        }
        val name = captured.name
        val remotePath = BackupPaths.remotePath(backup.remoteFolder, now, name)
        val staged = withContext(Dispatchers.IO) {
            val file = diskCache.newStagingFile("$accountName:$remotePath")
            try {
                captured.inputStream().use { input -> file.outputStream().use(input::copyTo) }
                file
            } catch (e: IOException) {
                file.delete()
                null
            }
        }
        if (staged == null) {
            discard(uri)
            return CaptureOutcome.Unreadable
        }
        withContext(Dispatchers.IO) {
            addToGallery(captured, accountName, remotePath, now)
            captured.delete()
            // Show it under Transfers straight away (also while offline); the worker picks this row up when it starts.
            database.transferDao().insert(
                TransferEntity(
                    accountName = accountName,
                    remotePath = remotePath,
                    direction = TransferDirection.UPLOAD,
                    state = TransferState.QUEUED,
                    totalBytes = staged.length(),
                    createdAt = now,
                    sourcePath = staged.absolutePath,
                    mimeType = "image/jpeg",
                ),
            )
        }
        UploadDocumentWorker.enqueue(context, UploadRequest(accountName, remotePath, staged, "image/jpeg", ifMatchEtag = null, createParents = true))
        return CaptureOutcome.Queued(remotePath)
    }

    /** Keeps a copy in Pictures/Aether, as a camera app would. Best effort: a failure only means no gallery copy. */
    private fun addToGallery(photo: File, accountName: String, remotePath: String, now: Long) {
        val resolver = context.contentResolver
        runCatching {
            val row = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, photo.name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Aether")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ) ?: return
            try {
                resolver.openOutputStream(row)!!.use { out -> photo.inputStream().use { it.copyTo(out) } }
                // Logged before the row becomes visible to scans; the upload itself is tracked under Transfers.
                database.backedUpMediaDao().upsert(
                    BackedUpMediaEntity(accountName, ContentUris.parseId(row), isVideo = false, dateModified = now, size = photo.length(), remotePath = remotePath, backedUpAt = now),
                )
                resolver.update(row, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(row, null, null)
                throw e
            }
        }
    }

    private fun fileFor(uri: Uri): File? = uri.lastPathSegment?.let { File(captureDir, it) }?.takeIf { it.parentFile == captureDir }

    private companion object {
        const val STALE_MS = 24 * 60 * 60 * 1000L
    }
}
