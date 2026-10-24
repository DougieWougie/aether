package com.aether.nextcloud.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** `guess` asks the platform's `MimeTypeMap`, which only has real data on a device. */
@RunWith(AndroidJUnit4::class)
class MimeTypesGuessTest {
    @Test
    fun commonExtensionsAreRecognised() {
        assertEquals("image/jpeg", MimeTypes.guess("holiday.jpg"))
        assertEquals("application/pdf", MimeTypes.guess("report.pdf"))
        assertEquals("text/plain", MimeTypes.guess("notes.txt"))
        assertEquals("video/mp4", MimeTypes.guess("clip.mp4"))
    }

    @Test
    fun theExtensionIsMatchedCaseInsensitively() {
        assertEquals("image/jpeg", MimeTypes.guess("IMG_0001.JPG"))
    }

    @Test
    fun onlyTheLastExtensionCounts() {
        assertEquals("application/pdf", MimeTypes.guess("archive.tar.pdf"))
        assertEquals("text/plain", MimeTypes.guess("my.photo.txt"))
    }

    @Test
    fun unknownExtensionsAndHiddenFilesFallBack() {
        assertEquals(MimeTypes.FALLBACK, MimeTypes.guess("data.zzzunknown"))
        assertEquals(MimeTypes.FALLBACK, MimeTypes.guess("noextension"))
    }
}
