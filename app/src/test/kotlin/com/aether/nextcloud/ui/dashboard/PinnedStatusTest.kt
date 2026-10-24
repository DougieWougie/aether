package com.aether.nextcloud.ui.dashboard

import com.aether.nextcloud.data.local.entity.DocumentEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class PinnedStatusTest {
    private fun doc(isDirectory: Boolean = false, etag: String? = "e2", cachedEtag: String? = null, local: String? = null) = DocumentEntity(
        documentId = "a:/x", accountName = "a", path = "/x", parentId = "a:/", name = "x", isDirectory = isDirectory,
        mimeType = "text/plain", size = 10, lastModified = 0, etag = etag, fileId = "1", permissions = "RGDNVW", hasPreview = false,
        isPinned = true, localCachePath = local, cachedEtag = cachedEtag,
    )

    @Test
    fun `a file whose copy matches the server is on the device`() =
        assertEquals(PinnedStatus.OnDevice, pinnedStatus(doc(cachedEtag = "e2", local = "/p/x")))

    @Test
    fun `a file whose copy is older than the server is waiting for an update`() =
        assertEquals(PinnedStatus.UpdateWaiting, pinnedStatus(doc(cachedEtag = "e1", local = "/p/x")))

    @Test
    fun `a file never downloaded is waiting`() =
        assertEquals(PinnedStatus.Waiting, pinnedStatus(doc()))

    @Test
    fun `a matching ETag without a file on disk is still waiting`() =
        assertEquals(PinnedStatus.Waiting, pinnedStatus(doc(cachedEtag = "e2", local = null)))

    @Test
    fun `a folder is synced only when its recorded ETag is current`() {
        assertEquals(PinnedStatus.FolderSynced, pinnedStatus(doc(isDirectory = true, cachedEtag = "e2")))
        assertEquals(PinnedStatus.FolderSyncing, pinnedStatus(doc(isDirectory = true, cachedEtag = "e1")))
        assertEquals(PinnedStatus.FolderSyncing, pinnedStatus(doc(isDirectory = true)))
    }
}
