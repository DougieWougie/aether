package com.aether.nextcloud.data

import com.aether.nextcloud.data.local.entity.DocumentEntity
import com.aether.nextcloud.dav.model.DavResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentMappingTest {
    private val file = DavResource(
        href = "/remote.php/dav/files/alice/Documents/My%20notes%20%231.md",
        isDirectory = false, fileId = "1050", contentLength = 1337, contentType = "text/markdown; charset=utf-8",
        lastModified = 5, etag = "abc", permissions = "RGDNVW", size = 1337, hasPreview = true,
    )

    private fun map(resource: DavResource, existing: DocumentEntity? = null) =
        resource.toEntity("alice@h", "alice", existing) { "guessed/type" }

    @Test
    fun `maps a file with decoded path, ids and cleaned mime type`() {
        val entity = map(file)

        assertEquals("alice@h:/Documents/My notes #1.md", entity.documentId)
        assertEquals("/Documents/My notes #1.md", entity.path)
        assertEquals("alice@h:/Documents", entity.parentId)
        assertEquals("My notes #1.md", entity.name)
        assertEquals("text/markdown", entity.mimeType)
        assertEquals(1337L, entity.size)
        assertEquals("abc", entity.etag)
        assertTrue(entity.hasPreview)
        assertFalse(entity.isPinned)
        assertNull(entity.localCachePath)
    }

    @Test
    fun `folders use the directory mime type and oc size`() {
        val entity = map(DavResource("/remote.php/dav/files/alice/Docs/", true, size = 4096, etag = "f"))
        assertEquals(MimeTypes.DIRECTORY, entity.mimeType)
        assertEquals(4096L, entity.size)
        assertEquals("alice@h:/Docs", entity.documentId)
    }

    @Test
    fun `account root has no parent`() {
        val entity = map(DavResource("/remote.php/dav/files/alice/", true))
        assertEquals("alice@h:/", entity.documentId)
        assertNull(entity.parentId)
    }

    @Test
    fun `missing content type falls back to a guess`() {
        assertEquals("guessed/type", map(file.copy(contentType = null)).mimeType)
    }

    @Test
    fun `refresh preserves local state`() {
        val existing = map(file).copy(isPinned = true, localCachePath = "/data/x", cachedEtag = "old", childrenSyncedAt = 99, lastAccessed = 7)

        val refreshed = map(file.copy(etag = "new"), existing)

        assertEquals("new", refreshed.etag)
        assertTrue(refreshed.isPinned)
        assertEquals("/data/x", refreshed.localCachePath)
        assertEquals("old", refreshed.cachedEtag)
        assertEquals(99L, refreshed.childrenSyncedAt)
        assertEquals(7L, refreshed.lastAccessed)
    }

    @Test
    fun `children go stale after 60 seconds or when never synced`() {
        val base = map(file)
        assertTrue(base.areChildrenStale(now = 1_000))
        assertFalse(base.copy(childrenSyncedAt = 1_000).areChildrenStale(now = 60_999))
        assertTrue(base.copy(childrenSyncedAt = 1_000).areChildrenStale(now = 61_001))
        assertTrue("clock moved backwards", base.copy(childrenSyncedAt = 5_000).areChildrenStale(now = 1_000))
    }

    @Test
    fun `like patterns escape wildcards`() {
        assertEquals("/100\\%\\_done/%", subtreeLikePattern("/100%_done"))
        assertEquals("/a\\\\b/%", subtreeLikePattern("/a\\b/"))
    }
}
