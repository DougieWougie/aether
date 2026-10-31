package com.aether.nextcloud.ui.dashboard

import android.content.Context
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.provider.DocumentsContract
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.aether.nextcloud.AetherConstants
import com.aether.nextcloud.data.MimeTypes
import com.aether.nextcloud.data.local.entity.TransferEntity
import com.aether.nextcloud.data.local.entity.TransferState
import com.aether.nextcloud.provider.DocumentId
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Whether the transfer is a picture, from its recorded type or else its file name. */
internal fun TransferEntity.isImage(): Boolean =
    (mimeType?.takeIf { it != MimeTypes.FALLBACK } ?: MimeTypes.guess(remotePath.substringAfterLast('/'))).startsWith("image/")

/**
 * A small picture of an image transfer, or null for anything else (or when none can be made, so the row keeps its icon).
 * A file still waiting to upload (or kept after a failure) is read from its staged copy; a finished transfer asks the
 * system provider, which serves the cached copy or the server's preview. A conflict row is left alone, since its path is the
 * original, not the conflict copy that was actually saved.
 */
internal suspend fun loadTransferThumbnail(context: Context, transfer: TransferEntity, px: Int): ImageBitmap? {
    if (!transfer.isImage() || transfer.state == TransferState.CONFLICT) return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val staged = transfer.sourcePath?.let(::File)?.takeIf { it.isFile }
            val bitmap: Bitmap? = when {
                staged != null -> ThumbnailUtils.createImageThumbnail(staged, Size(px, px), null)
                transfer.state == TransferState.COMPLETED -> {
                    val id = DocumentId(transfer.accountName, DocumentId.normalizePath(transfer.remotePath))
                    context.contentResolver.loadThumbnail(DocumentsContract.buildDocumentUri(AetherConstants.AUTHORITY, id.toString()), Size(px, px), null)
                }
                else -> null
            }
            bitmap?.asImageBitmap()
        }.getOrNull()
    }
}
