package com.aether.nextcloud.data

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class MimeTypesTest {
    @Test
    fun parametersSuchAsTheCharsetAreStripped() {
        assertEquals("text/plain", MimeTypes.clean("text/plain; charset=utf-8"))
        assertEquals("application/json", MimeTypes.clean("application/json;charset=UTF-8"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("image/png", MimeTypes.clean("  image/png  ; q=1"))
    }

    @Test
    fun aMissingOrBlankContentTypeIsNullSoCallersCanFallBack() {
        assertNull(MimeTypes.clean(null))
        assertNull(MimeTypes.clean(""))
        assertNull(MimeTypes.clean("   "))
        assertNull(MimeTypes.clean("; charset=utf-8"))
    }

    @Test
    fun commonExtensionsAreRecognisedCaseInsensitivelyUsingTheLastExtension() {
        assertEquals("image/jpeg", MimeTypes.guess("holiday.jpg"))
        assertEquals("image/jpeg", MimeTypes.guess("IMG_0001.JPG"))
        assertEquals("application/pdf", MimeTypes.guess("report.pdf"))
        assertEquals("application/pdf", MimeTypes.guess("archive.tar.pdf"))
        assertEquals("text/plain", MimeTypes.guess("notes.txt"))
        assertEquals("text/plain", MimeTypes.guess("my.photo.txt"))
        assertEquals("video/mp4", MimeTypes.guess("clip.mp4"))
    }

    @Test
    fun unknownExtensionsAndNamesWithoutExtensionsFallBack() {
        assertEquals(MimeTypes.FALLBACK, MimeTypes.guess("README"))
        assertEquals(MimeTypes.FALLBACK, MimeTypes.guess("trailingdot."))
        assertEquals(MimeTypes.FALLBACK, MimeTypes.guess(""))
        assertEquals(MimeTypes.FALLBACK, MimeTypes.guess("data.zzzunknown"))
    }

    @Test
    fun theConstantsMatchWhatTheDocumentsProviderExpects() {
        assertEquals("vnd.android.document/directory", MimeTypes.DIRECTORY)
        assertEquals("application/octet-stream", MimeTypes.FALLBACK)
    }
}
