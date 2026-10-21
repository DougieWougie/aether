package com.aether.nextcloud.data

import com.aether.nextcloud.data.local.entity.DocumentEntity
import com.aether.nextcloud.dav.model.DavResource
import com.aether.nextcloud.dav.relativeRemotePath
import com.aether.nextcloud.provider.DocumentId

/**
 * Builds the cache row for a PROPFIND [resource]. Local state (pin flag, local copy, listing timestamp) is carried over
 * from [existing] so a metadata refresh never forgets what is on disk.
 */
fun DavResource.toEntity(
    accountName: String,
    loginName: String,
    existing: DocumentEntity? = null,
    guessMime: (String) -> String = MimeTypes::guess,
): DocumentEntity {
    val id = DocumentId(accountName, DocumentId.normalizePath(relativeRemotePath(href, loginName)))
    val name = id.name
    return DocumentEntity(
        documentId = id.toString(),
        accountName = accountName,
        path = id.path,
        parentId = id.parent?.toString(),
        name = name,
        isDirectory = isDirectory,
        mimeType = if (isDirectory) MimeTypes.DIRECTORY else MimeTypes.clean(contentType) ?: guessMime(name),
        size = if (isDirectory) size ?: 0L else contentLength ?: size ?: 0L,
        lastModified = lastModified ?: 0L,
        etag = etag,
        fileId = fileId,
        permissions = permissions,
        hasPreview = hasPreview,
        isPinned = existing?.isPinned ?: false,
        localCachePath = existing?.localCachePath,
        cachedEtag = existing?.cachedEtag,
        childrenSyncedAt = existing?.childrenSyncedAt,
        lastAccessed = existing?.lastAccessed ?: 0L,
    )
}

/** Escapes `\`, `%` and `_` so a path can be used as a literal prefix in a SQL `LIKE ... ESCAPE '\'`. */
fun escapeLike(value: String): String =
    value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

fun subtreeLikePattern(path: String): String = escapeLike(path.trimEnd('/')) + "/%"
