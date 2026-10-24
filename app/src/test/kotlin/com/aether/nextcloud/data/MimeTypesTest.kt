package com.aether.nextcloud.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MimeTypesTest {
    @Test
    fun `parameters such as the charset are stripped`() {
        assertEquals("text/plain", MimeTypes.clean("text/plain; charset=utf-8"))
        assertEquals("application/json", MimeTypes.clean("application/json;charset=UTF-8"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("image/png", MimeTypes.clean("  image/png  ; q=1"))
    }

    @Test
    fun `a missing or blank content type is null so callers can fall back`() {
        assertNull(MimeTypes.clean(null))
        assertNull(MimeTypes.clean(""))
        assertNull(MimeTypes.clean("   "))
        assertNull(MimeTypes.clean("; charset=utf-8"))
    }

    @Test
    fun `a name with no extension falls back without asking the platform`() {
        assertEquals(MimeTypes.FALLBACK, MimeTypes.guess("README"))
        assertEquals(MimeTypes.FALLBACK, MimeTypes.guess("trailingdot."))
        assertEquals(MimeTypes.FALLBACK, MimeTypes.guess(""))
    }

    @Test
    fun `the constants match what the documents provider expects`() {
        assertEquals("vnd.android.document/directory", MimeTypes.DIRECTORY)
        assertEquals("application/octet-stream", MimeTypes.FALLBACK)
    }
}
