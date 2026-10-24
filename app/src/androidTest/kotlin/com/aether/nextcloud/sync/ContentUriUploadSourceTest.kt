package com.aether.nextcloud.sync

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.dav.Checksums
import com.aether.nextcloud.support.MediaStoreFixtures
import java.io.FileNotFoundException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentUriUploadSourceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val media = MediaStoreFixtures(context)
    private val bytes = ByteArray(5_000) { (it % 251).toByte() }

    @Before fun grant() = media.grantMediaPermissions()
    @After fun clean() = media.deleteAll()

    private fun source(checksum: String? = null) =
        ContentUriUploadSource(context.contentResolver, media.photo("src.jpg", bytes).uri, bytes.size.toLong(), 123L, checksum)

    @Test
    fun itStreamsTheWholeItemFromTheStart() {
        assertArrayEquals(bytes, source().open(0).use { it.readBytes() })
    }

    @Test
    fun itCanStartPartWayThrough() {
        assertArrayEquals(bytes.copyOfRange(1_234, bytes.size), source().open(1_234).use { it.readBytes() })
    }

    @Test
    fun closingTheStreamAlsoClosesTheFileDescriptor() {
        val stream = source().open(0)
        stream.read()

        stream.close()
        stream.close()
    }

    @Test
    fun anItemThatIsGoneCannotBeOpened() {
        val item = media.photo("gone.jpg", bytes)
        val gone = ContentUriUploadSource(context.contentResolver, item.uri, 1, null)
        media.deleteAll()

        try {
            gone.open(0)
            fail("expected FileNotFoundException")
        } catch (_: FileNotFoundException) {
        }
    }

    @Test
    fun withChecksumHashesTheContentAndKeepsTheRest() {
        val hashed = source().withChecksum()

        assertEquals(source().open(0).use(Checksums::sha1), hashed.checksum)
        assertEquals(bytes.size.toLong(), hashed.length)
        assertEquals(123L, hashed.lastModified)
        assertNull(source().checksum)
    }
}
