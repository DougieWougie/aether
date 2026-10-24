package com.aether.nextcloud.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.support.MediaStoreFixtures
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaAlbumRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val media = MediaStoreFixtures(context)
    private val albums = MediaAlbumRepository(context)

    @Before fun grant() = media.grantMediaPermissions()
    @After fun clean() = media.deleteAll()

    private fun album(name: String, includeVideos: Boolean = true) = runBlocking { albums.albums(includeVideos) }.firstOrNull { it.name == name }

    @Test
    fun photosAreGroupedIntoTheirAlbumAndCounted() {
        media.photo("a.jpg", ByteArray(8) { 1 }, album = "AetherAlbumOne")
        media.photo("b.jpg", ByteArray(8) { 2 }, album = "AetherAlbumOne")
        media.photo("c.jpg", ByteArray(8) { 3 }, album = "AetherAlbumTwo")

        assertEquals(2, album("AetherAlbumOne")?.count)
        assertEquals(1, album("AetherAlbumTwo")?.count)
    }

    @Test
    fun videoAlbumsAreListedOnlyWhenRequested() {
        media.photo("a.jpg", ByteArray(8) { 1 }, album = "AetherPhotosOnly")
        media.video("v.mp4", ByteArray(8) { 2 }, album = "AetherVideosOnly")

        assertEquals(1, album("AetherVideosOnly", includeVideos = true)?.count)
        assertNull(album("AetherVideosOnly", includeVideos = false))
        assertEquals(1, album("AetherPhotosOnly", includeVideos = false)?.count)
    }

    @Test
    fun theBiggestAlbumComesFirst() {
        repeat(3) { media.photo("big$it.jpg", ByteArray(8) { 1 }, album = "AetherBig") }
        media.photo("small.jpg", ByteArray(8) { 1 }, album = "AetherSmall")

        val names = runBlocking { albums.albums(true) }.map { it.name }

        assertEquals(true, names.indexOf("AetherBig") < names.indexOf("AetherSmall"))
    }

    @Test
    fun emptyFilesAreNotAlbumContents() {
        media.photo("empty.jpg", ByteArray(0), album = "AetherEmptyOnly")

        assertNull(album("AetherEmptyOnly"))
        assertNotNull(albums)
    }
}
