package com.aether.nextcloud.dav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChecksumsTest {
    @Test
    fun `sha1 of known content in the nextcloud format`() {
        assertEquals("SHA1:a9993e364706816aba3e25717850c26c9cd0d89d", Checksums.sha1("abc".byteInputStream()))
        assertEquals("SHA1:da39a3ee5e6b4b0d3255bfef95601890afd80709", Checksums.sha1("".byteInputStream()))
    }

    @Test
    fun `picks the sha1 from the server list, ignoring other algorithms and case`() {
        assertEquals("SHA1:abc", Checksums.sha1Of(listOf("MD5:1", "sha1:ABC", "ADLER32:2")))
        assertNull(Checksums.sha1Of(listOf("MD5:1", "ADLER32:2")))
        assertNull(Checksums.sha1Of(emptyList()))
    }
}
