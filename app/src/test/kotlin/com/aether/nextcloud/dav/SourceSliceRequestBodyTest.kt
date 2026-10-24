package com.aether.nextcloud.dav

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SourceSliceRequestBodyTest {
    private class BytesSource(private val bytes: ByteArray) : UploadSource {
        val opened = mutableListOf<Long>()
        override val length: Long get() = bytes.size.toLong()
        override val lastModified: Long? = null
        override val checksum: String? = null
        override fun open(offset: Long): InputStream {
            opened += offset
            return ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
        }
    }

    @Test
    fun `it reports its type and length`() {
        val body = SourceSliceRequestBody(BytesSource(ByteArray(10)), offset = 2, length = 5, contentType = "text/plain".toMediaType())

        assertEquals("text/plain".toMediaType(), body.contentType())
        assertEquals(5L, body.contentLength())
    }

    @Test
    fun `only the requested slice is written`() {
        val source = BytesSource(ByteArray(10) { it.toByte() })
        val sink = Buffer()

        SourceSliceRequestBody(source, offset = 3, length = 4, contentType = null).writeTo(sink)

        assertEquals(listOf<Byte>(3, 4, 5, 6), sink.readByteArray().toList())
        assertEquals(listOf(3L), source.opened)
    }

    @Test
    fun `progress counts up to the slice length`() {
        val seen = mutableListOf<Long>()

        SourceSliceRequestBody(BytesSource(ByteArray(200_000)), offset = 0, length = 150_000, contentType = null) { seen += it }.writeTo(Buffer())

        assertEquals(150_000L, seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `a replayed body starts counting from zero again`() {
        val source = BytesSource(ByteArray(100))
        val seen = mutableListOf<Long>()
        val body = SourceSliceRequestBody(source, 0, 100, null) { seen += it }

        body.writeTo(Buffer())
        body.writeTo(Buffer())

        assertEquals(listOf(100L, 100L), seen)
    }

    @Test
    fun `a source that ends early fails instead of sending a short body`() {
        val body = SourceSliceRequestBody(BytesSource(ByteArray(5)), offset = 0, length = 10, contentType = null)

        try {
            body.writeTo(Buffer())
            fail("expected EOFException")
        } catch (e: EOFException) {
            assertTrue(e.message!!.contains("5 of 10"))
        }
    }
}
