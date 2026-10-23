package com.aether.nextcloud.data

import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.core.AetherResult
import com.aether.nextcloud.data.local.AetherDatabase
import com.aether.nextcloud.data.local.entity.BackedUpMediaEntity
import com.aether.nextcloud.dav.NextcloudOcsClient
import com.aether.nextcloud.dav.WebDavClientFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** What camera backup has uploaded: sizes by kind, the newest uploads, and (once fetched) the destination folder's size on the server. */
data class BackupStats(
    val photoCount: Int = 0,
    val photoBytes: Long = 0,
    val videoCount: Int = 0,
    val videoBytes: Long = 0,
    /** Size of the whole backup folder on the server, including anything other devices put there; null until fetched. */
    val serverBytes: Long? = null,
    val recent: List<BackedUpMediaEntity> = emptyList(),
) {
    val totalCount: Int get() = photoCount + videoCount
    /** Bytes uploaded by this phone, from the backup log. */
    val localBytes: Long get() = photoBytes + videoBytes
}

/** Stats and thumbnails for the camera backup UI. */
@Singleton
class CameraBackupStatsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    database: AetherDatabase,
    private val davFactory: WebDavClientFactory,
    private val ocs: NextcloudOcsClient,
) {
    private val dao = database.backedUpMediaDao()
    private val serverBytes = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val memory = LruCache<String, ImageBitmap>(MEMORY_THUMBNAILS)

    fun observe(accountName: String, recentLimit: Int): Flow<BackupStats> =
        combine(dao.observeTotals(accountName), dao.observeRecent(accountName, recentLimit), serverBytes) { totals, recent, server ->
            val photos = totals.firstOrNull { !it.isVideo }
            val videos = totals.firstOrNull { it.isVideo }
            BackupStats(
                photoCount = photos?.count ?: 0,
                photoBytes = photos?.bytes ?: 0,
                videoCount = videos?.count ?: 0,
                videoBytes = videos?.bytes ?: 0,
                serverBytes = server[accountName],
                recent = recent,
            )
        }

    /** Asks the server how big [folder] is; keeps the last answer when the folder does not exist yet or the request fails. */
    suspend fun refreshServerSize(credentials: AetherCredentials, folder: String) {
        val result = davFactory.forAccount(credentials).propfind(credentials.fileUrl(folder.trimEnd('/') + "/"), depth = 0)
        val size = (result as? AetherResult.Success)?.value?.firstOrNull()?.size ?: return
        serverBytes.update { it + (credentials.accountName to size) }
    }

    /** Local MediaStore thumbnail while the item is still on the phone, otherwise the server's preview of the uploaded copy. */
    suspend fun thumbnail(credentials: AetherCredentials, item: BackedUpMediaEntity): ImageBitmap? {
        val key = "${item.accountName}:${item.isVideo}:${item.mediaId}"
        memory.get(key)?.let { return it }
        val bitmap = localThumbnail(item) ?: serverPreview(credentials, item)
        if (bitmap != null) memory.put(key, bitmap)
        return bitmap
    }

    private suspend fun localThumbnail(item: BackedUpMediaEntity): ImageBitmap? = withContext(Dispatchers.IO) {
        val collection = if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        try {
            context.contentResolver.loadThumbnail(ContentUris.withAppendedId(collection, item.mediaId), Size(THUMBNAIL_PX, THUMBNAIL_PX), null).asImageBitmap()
        } catch (_: IOException) {
            null // deleted from the phone
        } catch (_: SecurityException) {
            null // not among the photos the user shared with Aether
        }
    }

    private suspend fun serverPreview(credentials: AetherCredentials, item: BackedUpMediaEntity): ImageBitmap? = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, "camera-previews/" + sha256("${item.accountName}:${item.remotePath}"))
        if (!target.isFile) {
            val fileId = (davFactory.forAccount(credentials).propfind(credentials.fileUrl(item.remotePath), depth = 0) as? AetherResult.Success)
                ?.value?.firstOrNull()?.fileId ?: return@withContext null
            if (ocs.downloadPreview(credentials, fileId, target, THUMBNAIL_PX) is AetherResult.Failure) return@withContext null
        }
        BitmapFactory.decodeFile(target.path)?.asImageBitmap()
    }

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val THUMBNAIL_PX = 256
        const val MEMORY_THUMBNAILS = 48
    }
}
