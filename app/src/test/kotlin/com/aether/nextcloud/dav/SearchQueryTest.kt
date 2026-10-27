package com.aether.nextcloud.dav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {
    private fun SearchQuery.hit(name: String = "report.pdf", mime: String = "application/pdf", dir: Boolean = false, size: Long = 10, modified: Long = 1_000) =
        matches(name, mime, dir, size, modified)

    @Test
    fun `a name matches anywhere in the file name, ignoring case`() {
        val query = SearchQuery(name = " Port ")
        assertTrue(query.hit("Annual REPORT.pdf"))
        assertFalse(query.hit("notes.md"))
    }

    @Test
    fun `a blank name matches everything`() {
        assertTrue(SearchQuery().hit("anything"))
    }

    @Test
    fun `mime types match exactly or by family, and any type matches the wildcard`() {
        assertTrue(SearchQuery(mimeTypes = listOf("image/*")).hit(mime = "image/png"))
        assertFalse(SearchQuery(mimeTypes = listOf("image/*")).hit(mime = "video/mp4"))
        assertTrue(SearchQuery(mimeTypes = listOf("application/pdf", "text/plain")).hit(mime = "text/plain"))
        assertTrue(SearchQuery(mimeTypes = listOf("*/*")).hit(mime = "video/mp4"))
    }

    @Test
    fun `excluding media drops images, video and audio`() {
        val query = SearchQuery(excludeMedia = true)
        assertFalse(query.hit(mime = "audio/ogg"))
        assertTrue(query.hit(mime = "application/pdf"))
    }

    @Test
    fun `date and size limits are exclusive, and folders never pass a size limit`() {
        assertFalse(SearchQuery(modifiedAfter = 1_000).hit(modified = 1_000))
        assertTrue(SearchQuery(modifiedAfter = 1_000).hit(modified = 1_001))
        assertFalse(SearchQuery(sizeOver = 10).hit(size = 10))
        assertTrue(SearchQuery(sizeOver = 10).hit(size = 11))
        assertFalse(SearchQuery(sizeOver = 0).hit(dir = true, size = 5))
    }

    @Test
    fun `the request body scopes the search to the user's files and escapes the text`() {
        val body = SearchQuery(name = "a&b <c>").toRequestBody("alice")
        assertTrue(body.contains("<d:href>/files/alice</d:href>"))
        assertTrue(body.contains("<d:literal>%a&amp;b &lt;c&gt;%</d:literal>"))
        assertTrue(body.contains("<d:nresults>${SearchQuery.DEFAULT_LIMIT}</d:nresults>"))
        assertFalse(body.contains("<d:and>"))
    }

    @Test
    fun `filters are combined with and, and several mime types with or`() {
        val body = SearchQuery(name = "x", mimeTypes = listOf("image/*", "application/pdf"), modifiedAfter = 0, sizeOver = 5)
            .toRequestBody("alice")
        assertTrue(body.contains("<d:and>"))
        assertTrue(body.contains("<d:or><d:like><d:prop><d:getcontenttype/></d:prop><d:literal>image/%</d:literal></d:like>"))
        assertTrue(body.contains("<d:gt><d:prop><d:getlastmodified/></d:prop><d:literal>Thu, 1 Jan 1970 00:00:00 GMT</d:literal></d:gt>"))
        assertTrue(body.contains("<d:gt><d:prop><d:getcontentlength/></d:prop><d:literal>5</d:literal></d:gt>"))
    }

    @Test
    fun `queries that differ only in case or mime order share a key`() {
        assertEquals(
            SearchQuery(name = "Report", mimeTypes = listOf("b/x", "a/x")).key,
            SearchQuery(name = "report", mimeTypes = listOf("a/x", "b/x")).key,
        )
    }
}
