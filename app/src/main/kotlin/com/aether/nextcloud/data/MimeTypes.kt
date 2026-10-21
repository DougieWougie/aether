package com.aether.nextcloud.data

import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap

object MimeTypes {
    const val DIRECTORY = Document.MIME_TYPE_DIR
    const val FALLBACK = "application/octet-stream"

    /** Strips parameters such as `; charset=utf-8`. */
    fun clean(contentType: String?): String? = contentType?.substringBefore(';')?.trim()?.ifEmpty { null }

    fun guess(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) return FALLBACK
        return runCatching { MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) }.getOrNull() ?: FALLBACK
    }
}
