package com.aether.nextcloud.provider

import android.provider.DocumentsContract.Document
import com.aether.nextcloud.data.local.entity.DocumentEntity

/**
 * Maps Nextcloud `oc:permissions` letters (R share, G read, D delete, N rename, V move, W write, C create file,
 * K create folder) to SAF `COLUMN_FLAGS`. Unknown permissions (a document we only know by id) are treated as read-only.
 */
fun DocumentEntity.safFlags(): Int {
    val perms = permissions.orEmpty()
    var flags = 0
    if (isDirectory) {
        if ('C' in perms || 'K' in perms) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
    } else {
        if ('W' in perms) flags = flags or Document.FLAG_SUPPORTS_WRITE
        if (hasPreview && fileId != null) flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
    }
    // The account root can be none of these; its parentId is null.
    if (parentId != null) {
        if ('D' in perms) flags = flags or Document.FLAG_SUPPORTS_DELETE
        if ('N' in perms) flags = flags or Document.FLAG_SUPPORTS_RENAME
        if ('V' in perms) flags = flags or Document.FLAG_SUPPORTS_MOVE
    }
    return flags
}

/** Placeholder for an account root we have not listed yet (Nextcloud always lets users create in their own root). */
fun rootPlaceholder(id: DocumentId, displayName: String): DocumentEntity = DocumentEntity(
    documentId = id.toString(),
    accountName = id.accountName,
    path = "/",
    parentId = null,
    name = displayName,
    isDirectory = true,
    mimeType = Document.MIME_TYPE_DIR,
    size = 0,
    lastModified = 0,
    etag = null,
    fileId = null,
    permissions = "RGDNVCK",
    hasPreview = false,
)
