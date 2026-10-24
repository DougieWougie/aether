package com.aether.nextcloud.dav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParserException

class WebDavXmlParserTest {
    private val parser = WebDavXmlParser()

    private fun fixture(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!

    @Test
    fun `parses a depth 1 listing from a real-shaped nextcloud response`() {
        val resources = parser.parse(fixture("propfind_depth1.xml"))

        assertEquals(3, resources.size)

        val folder = resources[0]
        assertEquals("/remote.php/dav/files/alice/Documents/", folder.href)
        assertTrue(folder.isDirectory)
        assertEquals("1042", folder.fileId)
        assertEquals("66a1b2c3d4e5f", folder.etag)
        assertEquals("RGDNVCK", folder.permissions)
        assertEquals(4096L, folder.size)
        assertNull("404 propstat must not leak in", folder.contentLength)
        assertNull(folder.contentType)
        assertFalse(folder.hasPreview)
        assertFalse(folder.isFavorite)
        assertEquals(1_792_484_100_000L, folder.lastModified)
        assertTrue(folder.canWrite && folder.canDelete && folder.canRename && folder.canMove)

        val file = resources[1]
        assertEquals("/remote.php/dav/files/alice/Documents/My%20notes%20%231.md", file.href)
        assertFalse(file.isDirectory)
        assertEquals(1337L, file.contentLength)
        assertEquals("text/markdown", file.contentType)
        assertEquals("abc123def456", file.etag)
        assertTrue(file.hasPreview)
        assertTrue(file.isFavorite)
    }

    @Test
    fun `strips weak prefix, tolerates property order and skips unknown nested properties`() {
        val photo = parser.parse(fixture("propfind_depth1.xml"))[2]

        assertEquals("weak-etag", photo.etag)
        assertEquals(2_048_000L, photo.contentLength)
        assertEquals("image/jpeg", photo.contentType)
        assertEquals("1051", photo.fileId)
        assertEquals("RGD", photo.permissions)
        assertFalse(photo.canWrite)
    }

    @Test
    fun `reads oc checksums and treats a missing property as none`() {
        val xml = """<?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
              <d:response><d:href>/remote.php/dav/files/alice/a.jpg</d:href><d:propstat><d:prop>
                <oc:checksums><oc:checksum>SHA1:ABC123 MD5:def456</oc:checksum></oc:checksums>
              </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
              <d:response><d:href>/remote.php/dav/files/alice/b.jpg</d:href><d:propstat><d:prop>
                <d:getcontentlength>5</d:getcontentlength>
              </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            </d:multistatus>""".trimIndent()

        val (withSum, without) = parser.parse(xml.byteInputStream())

        assertEquals(listOf("SHA1:ABC123", "MD5:def456"), withSum.checksums)
        assertEquals(emptyList<String>(), without.checksums)
    }

    @Test
    fun `works with arbitrary namespace prefixes`() {
        val xml = """
            <multistatus xmlns="DAV:" xmlns:o="http://owncloud.org/ns">
              <response><href>/a/</href><propstat><prop>
                <resourcetype><collection/></resourcetype><o:fileid>7</o:fileid>
              </prop><status>HTTP/1.1 200 OK</status></propstat></response>
            </multistatus>
        """.trimIndent()

        val resource = parser.parse(xml.byteInputStream()).single()

        assertTrue(resource.isDirectory)
        assertEquals("7", resource.fileId)
    }

    @Test
    fun `ignores responses without href and bad dates`() {
        val xml = """
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:propstat><d:prop><d:getetag>"x"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
              <d:response><d:href>/b</d:href><d:propstat><d:prop><d:getlastmodified>yesterday-ish</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            </d:multistatus>
        """.trimIndent()

        val resources = parser.parse(xml.byteInputStream())

        assertEquals(1, resources.size)
        assertEquals("/b", resources[0].href)
        assertNull(resources[0].lastModified)
    }

    @Test
    fun `empty multistatus yields empty list`() {
        assertTrue(parser.parse("""<d:multistatus xmlns:d="DAV:"/>""".byteInputStream()).isEmpty())
    }

    @Test(expected = XmlPullParserException::class)
    fun `malformed xml throws`() {
        parser.parse("<d:multistatus xmlns:d=\"DAV:\"><d:response>".byteInputStream())
    }

    @Test
    fun `relativeRemotePath decodes and strips the dav prefix`() {
        assertEquals("/Documents/My notes #1.md", relativeRemotePath("/remote.php/dav/files/alice/Documents/My%20notes%20%231.md", "alice"))
        assertEquals("/Documents", relativeRemotePath("/remote.php/dav/files/alice/Documents/", "alice"))
        assertEquals("/", relativeRemotePath("/remote.php/dav/files/alice/", "alice"))
        assertEquals("/x", relativeRemotePath("/nextcloud/remote.php/dav/files/alice/x", "alice"))
        assertEquals("/x", relativeRemotePath("https://c.example.com/remote.php/dav/files/alice/x", "alice"))
    }
}
