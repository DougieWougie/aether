package com.aether.nextcloud.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Cached WebDAV metadata for one file or folder, plus local state (pin flag and where its bytes live). */
@Entity(
    tableName = "documents",
    indices = [Index("parentId"), Index("accountName", "path"), Index("isPinned")],
)
data class DocumentEntity(
    @PrimaryKey val documentId: String,
    val accountName: String,
    val path: String,
    val parentId: String?,
    val name: String,
    val isDirectory: Boolean,
    val mimeType: String,
    val size: Long,
    val lastModified: Long,
    val etag: String?,
    val fileId: String?,
    val permissions: String?,
    val hasPreview: Boolean,
    val isPinned: Boolean = false,
    /** Absolute path of the local copy (disk cache or pinned storage); null when not downloaded. */
    val localCachePath: String? = null,
    /** ETag the local copy corresponds to; the copy is only served while this equals [etag]. */
    val cachedEtag: String? = null,
    /** For directories: when the child list was last refreshed from the server. */
    val childrenSyncedAt: Long? = null,
    val lastAccessed: Long = 0,
) {
    fun areChildrenStale(now: Long, ttlMs: Long = CHILDREN_TTL_MS): Boolean {
        val syncedAt = childrenSyncedAt ?: return true
        return now - syncedAt > ttlMs || syncedAt > now
    }

    companion object {
        /** CLAUDE.md: directory listings older than 60 s trigger a background refresh. */
        const val CHILDREN_TTL_MS = 60_000L
    }
}
