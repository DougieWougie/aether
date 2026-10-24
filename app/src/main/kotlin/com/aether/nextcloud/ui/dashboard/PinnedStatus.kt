package com.aether.nextcloud.ui.dashboard

import com.aether.nextcloud.data.local.entity.DocumentEntity

/** Where a pinned item stands, read from the cached row: a file's copy matches the server's ETag, or it does not. */
enum class PinnedStatus { OnDevice, UpdateWaiting, Waiting, FolderSynced, FolderSyncing }

internal fun pinnedStatus(doc: DocumentEntity): PinnedStatus {
    val current = doc.cachedEtag != null && doc.cachedEtag == doc.etag
    return when {
        // For a folder, cachedEtag is the ETag at which the whole subtree was last fully synced.
        doc.isDirectory -> if (current) PinnedStatus.FolderSynced else PinnedStatus.FolderSyncing
        current && doc.localCachePath != null -> PinnedStatus.OnDevice
        doc.localCachePath != null -> PinnedStatus.UpdateWaiting
        else -> PinnedStatus.Waiting
    }
}
