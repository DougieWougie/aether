package com.aether.nextcloud.provider

import android.provider.DocumentsContract.Document
import com.aether.nextcloud.data.local.entity.DocumentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentFlagsTest {
    private fun doc(permissions: String?, dir: Boolean = false, preview: Boolean = false, fileId: String? = "1", parent: String? = "a@h:/") =
        DocumentEntity(
            documentId = "a@h:/x", accountName = "a@h", path = "/x", parentId = parent, name = "x", isDirectory = dir,
            mimeType = "text/plain", size = 0, lastModified = 0, etag = null, fileId = fileId, permissions = permissions, hasPreview = preview,
        )

    private fun Int.has(flag: Int) = this and flag != 0

    @Test
    fun `writable file`() {
        val flags = doc("RGDNVW").safFlags()
        assertTrue(flags.has(Document.FLAG_SUPPORTS_WRITE))
        assertTrue(flags.has(Document.FLAG_SUPPORTS_DELETE))
        assertTrue(flags.has(Document.FLAG_SUPPORTS_RENAME))
        assertTrue(flags.has(Document.FLAG_SUPPORTS_MOVE))
        assertFalse(flags.has(Document.FLAG_DIR_SUPPORTS_CREATE))
    }

    @Test
    fun `read only share`() {
        assertEquals(0, doc("RG").safFlags())
    }

    @Test
    fun `unknown permissions are read-only`() {
        assertEquals(0, doc(null).safFlags())
    }

    @Test
    fun `folder create requires C or K`() {
        assertTrue(doc("RGDNVCK", dir = true).safFlags().has(Document.FLAG_DIR_SUPPORTS_CREATE))
        assertTrue(doc("RGC", dir = true).safFlags().has(Document.FLAG_DIR_SUPPORTS_CREATE))
        assertFalse(doc("RGDNV", dir = true).safFlags().has(Document.FLAG_DIR_SUPPORTS_CREATE))
        assertFalse("W is for files", doc("RGW", dir = true).safFlags().has(Document.FLAG_SUPPORTS_WRITE))
    }

    @Test
    fun `thumbnails need a preview and a file id`() {
        assertTrue(doc("RG", preview = true).safFlags().has(Document.FLAG_SUPPORTS_THUMBNAIL))
        assertFalse(doc("RG", preview = false).safFlags().has(Document.FLAG_SUPPORTS_THUMBNAIL))
        assertFalse(doc("RG", preview = true, fileId = null).safFlags().has(Document.FLAG_SUPPORTS_THUMBNAIL))
    }

    @Test
    fun `account roots can be neither deleted nor renamed nor moved`() {
        val flags = doc("RGDNVCK", dir = true, parent = null).safFlags()
        assertTrue(flags.has(Document.FLAG_DIR_SUPPORTS_CREATE))
        assertFalse(flags.has(Document.FLAG_SUPPORTS_DELETE))
        assertFalse(flags.has(Document.FLAG_SUPPORTS_RENAME))
        assertFalse(flags.has(Document.FLAG_SUPPORTS_MOVE))
    }

    @Test
    fun `placeholder root allows creating`() {
        val root = rootPlaceholder(DocumentId.root("a@h"), "a@h")
        assertTrue(root.safFlags().has(Document.FLAG_DIR_SUPPORTS_CREATE))
        assertEquals("a@h:/", root.documentId)
    }
}
