package com.aether.nextcloud.dav

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParserException

class WebDavXmlParserEdgeCasesTest {
    private val parser = WebDavXmlParser()

    private fun parse(body: String) = parser.parse(ByteArrayInputStream(body.toByteArray()))

    private fun multistatus(inner: String) =
        """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns" xmlns:x="urn:other">$inner</d:multistatus>"""

    private fun response(props: String, status: String = "HTTP/1.1 200 OK", href: String = "/remote.php/dav/files/a/f.txt") =
        "<d:response><d:href>$href</d:href><d:propstat><d:prop>$props</d:prop><d:status>$status</d:status></d:propstat></d:response>"

    @Test
    fun `unknown elements at every level are skipped`() {
        val resource = parse(
            multistatus(
                "<d:response><x:extra>ignored</x:extra><d:href>/f</d:href>" +
                    "<d:propstat><d:prop>" +
                    "<d:getetag>\"e\"</d:getetag><d:displayname>name</d:displayname><oc:owner-id>alice</oc:owner-id>" +
                    "<nc:note>n</nc:note><x:whatever>w</x:whatever>" +
                    "</d:prop><d:status>HTTP/1.1 200 OK</d:status><x:more/></d:propstat></d:response>",
            ),
        ).single()

        assertEquals("/f", resource.href)
        assertEquals("e", resource.etag)
    }

    @Test
    fun `a propstat with an unknown child is still read`() {
        val resources = parse(multistatus("<d:response><d:href>/f</d:href><d:propstat><x:odd/><d:prop><oc:fileid>7</oc:fileid></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"))

        assertEquals("7", resources.single().fileId)
    }

    @Test
    fun `preview, favourite, permissions and ids are read and blanks become null`() {
        val resource = parse(
            multistatus(response("<nc:has-preview>TRUE</nc:has-preview><oc:favorite>1</oc:favorite><oc:permissions> RGD </oc:permissions><oc:fileid>  </oc:fileid><oc:size>12</oc:size>")),
        ).single()

        assertTrue(resource.hasPreview)
        assertTrue(resource.isFavorite)
        assertEquals("RGD", resource.permissions)
        assertNull(resource.fileId)
        assertEquals(12L, resource.size)
    }

    @Test
    fun `preview off and favourite off are false`() {
        val resource = parse(multistatus(response("<nc:has-preview>false</nc:has-preview><oc:favorite>0</oc:favorite>"))).single()

        assertFalse(resource.hasPreview)
        assertFalse(resource.isFavorite)
    }

    @Test
    fun `checksums split on spaces and ignore other children`() {
        val resource = parse(
            multistatus(response("<oc:checksums><oc:checksum>SHA1:aa MD5:bb</oc:checksum><oc:other>x</oc:other></oc:checksums>")),
        ).single()

        assertEquals(listOf("SHA1:aa", "MD5:bb"), resource.checksums)
    }

    @Test
    fun `properties from several propstats are merged and a failed one is ignored`() {
        val resource = parse(
            multistatus(
                "<d:response><d:href>/f</d:href>" +
                    "<d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><oc:checksums><oc:checksum>SHA1:aa</oc:checksum></oc:checksums><nc:has-preview>true</nc:has-preview><oc:favorite>1</oc:favorite></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>" +
                    "<d:propstat><d:prop><oc:checksums><oc:checksum>SHA1:zz</oc:checksum></oc:checksums><d:getcontentlength>5</d:getcontentlength></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>" +
                    "<d:propstat><d:prop><d:getcontenttype>text/plain</d:getcontenttype><oc:size>9</oc:size></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>" +
                    "</d:response>",
            ),
        ).single()

        assertTrue(resource.isDirectory)
        assertEquals(listOf("SHA1:aa"), resource.checksums)
        assertTrue(resource.hasPreview)
        assertTrue(resource.isFavorite)
        assertNull("the 404 propstat's length must not leak in", resource.contentLength)
        assertEquals("text/plain", resource.contentType)
        assertEquals(9L, resource.size)
    }

    @Test
    fun `a status line without a code is treated as a failure`() {
        val resource = parse(multistatus(response("<oc:fileid>1</oc:fileid>", status = "gibberish"))).single()

        assertNull(resource.fileId)
    }

    @Test
    fun `a document that ends mid element is rejected`() {
        val truncated = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/f</d:href><d:propstat><d:prop><d:getetag>"""

        try {
            parse(truncated)
            org.junit.Assert.fail("expected the parser to reject a truncated body")
        } catch (_: XmlPullParserException) {
        }
    }

    @Test
    fun `etags lose the weak prefix and quotes and blanks become null`() {
        assertEquals("abc", WebDavXmlParser.cleanEtag("W/\"abc\""))
        assertEquals("abc", WebDavXmlParser.cleanEtag("  \"abc\"  "))
        assertNull(WebDavXmlParser.cleanEtag("\"\""))
    }

    @Test
    fun `http dates parse and garbage is null`() {
        assertEquals(1_704_067_200_000L, WebDavXmlParser.parseHttpDate("Mon, 01 Jan 2024 00:00:00 GMT"))
        assertNull(WebDavXmlParser.parseHttpDate("yesterday"))
    }
}
