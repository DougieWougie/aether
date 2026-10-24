package com.aether.nextcloud.sync

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.aether.nextcloud.AetherApp
import com.aether.nextcloud.data.prefs.BackupAlbum
import com.aether.nextcloud.data.prefs.SettingsRepository
import com.aether.nextcloud.dav.Checksums
import com.aether.nextcloud.support.AetherTestRule
import com.aether.nextcloud.support.MediaStoreFixtures
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the real worker against the device's MediaStore and a [com.aether.nextcloud.support.FakeNextcloud]. */
@RunWith(AndroidJUnit4::class)
class CameraBackupWorkerTest {
    @get:Rule val env = AetherTestRule()

    private val app: AetherApp = ApplicationProvider.getApplicationContext()
    private val settings = SettingsRepository(app)
    private val media = MediaStoreFixtures(app)
    private val folder = "/Photos/Camera Uploads"

    @Before fun grant() = media.grantMediaPermissions()

    @After fun cleanUp() {
        media.deleteAll()
        runBlocking { settings.updateBackup { it.copy(enabled = false, accountName = null, albums = null) } }
    }

    /** Turns backup on and waits out the second in which "added since" is compared, so only later items count. */
    private fun enableBackup(videos: Boolean = true, albums: List<BackupAlbum>? = null) {
        runBlocking { settings.updateBackup { it.copy(enabled = true, accountName = env.accountName, remoteFolder = folder, includeVideos = videos, albums = albums) } }
        Thread.sleep(1_100)
    }

    private fun runWorker(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<CameraBackupWorker>(app).setWorkerFactory(app.workerFactory).build().doWork()
    }

    private fun uploaded(): List<String> = env.nextcloud.paths().filter { it.startsWith("$folder/") && it.count { c -> c == '/' } == 5 }

    private fun bytes(seed: Int, size: Int = 2048) = ByteArray(size) { (it * seed).toByte() }

    @Test
    fun newPhotosGoIntoYearAndMonthFoldersWithAChecksum() {
        enableBackup()
        val photo = media.photo("aether_one.jpg", bytes(7))

        assertEquals(ListenableWorker.Result.success(), runWorker())

        val path = uploaded().single()
        assertTrue("expected $folder/YYYY/MM/aether_one.jpg, got $path", Regex("""$folder/\d{4}/\d{2}/aether_one\.jpg""").matches(path))
        assertArrayEquals(photo.bytes, env.nextcloud.bytes(path))
        val put = env.nextcloud.requestsMatching("PUT", path).single()
        assertEquals(Checksums.sha1(photo.bytes.inputStream()), put.getHeader("OC-Checksum"))
    }

    @Test
    fun photosAddedBeforeBackupWasEnabledAreLeftAlone() {
        media.photo("aether_old.jpg", bytes(3))
        Thread.sleep(1_100)
        enableBackup()

        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertTrue(uploaded().isEmpty())
    }

    @Test
    fun onlyTheChosenAlbumsAreBackedUp() {
        enableBackup()
        val wanted = media.photo("aether_wanted.jpg", bytes(5), album = "AetherTestKeep")
        media.photo("aether_skipped.jpg", bytes(9), album = "AetherTestSkip")
        runBlocking { settings.updateBackup { it.copy(albums = listOf(BackupAlbum(wanted.bucketId, "AetherTestKeep"))) } }

        runWorker()

        assertEquals(listOf("aether_wanted.jpg"), uploaded().map { it.substringAfterLast('/') })
    }

    @Test
    fun videosAreSkippedWhenTheyAreSwitchedOff() {
        enableBackup(videos = false)
        media.photo("aether_pic.jpg", bytes(2))
        media.video("aether_clip.mp4", bytes(4))

        runWorker()

        assertEquals(listOf("aether_pic.jpg"), uploaded().map { it.substringAfterLast('/') })
    }

    @Test
    fun aRunAfterReinstallDoesNotUploadAPhotoTheServerAlreadyHolds() {
        enableBackup()
        val photo = media.photo("aether_dupe.jpg", bytes(11))
        val year = java.time.LocalDate.now().year.toString()
        val month = "%02d".format(java.time.LocalDate.now().monthValue)
        val remote = "$folder/$year/$month/aether_dupe.jpg"
        env.nextcloud.putFile(remote, photo.bytes, "image/jpeg", checksum = Checksums.sha1(photo.bytes.inputStream()))

        runWorker()

        assertTrue("an identical file must not be uploaded again", env.nextcloud.requestsMatching("PUT", remote).isEmpty())
        assertEquals(listOf(remote), uploaded())
    }

    @Test
    fun aDifferentPhotoWithTheSameNameAndSizeKeepsBothFiles() {
        enableBackup()
        val photo = media.photo("aether_clash.jpg", bytes(13))
        val year = java.time.LocalDate.now().year.toString()
        val month = "%02d".format(java.time.LocalDate.now().monthValue)
        val remote = "$folder/$year/$month/aether_clash.jpg"
        val other = bytes(17).also { assertEquals(photo.bytes.size, it.size) }
        env.nextcloud.putFile(remote, other, "image/jpeg", checksum = Checksums.sha1(other.inputStream()))

        runWorker()

        assertArrayEquals("the existing file must be untouched", other, env.nextcloud.bytes(remote))
        val renamed = uploaded().single { it != remote }
        assertTrue(renamed.substringAfterLast('/').startsWith("aether_clash_"))
        assertNotNull(env.nextcloud.bytes(renamed))
        assertArrayEquals(photo.bytes, env.nextcloud.bytes(renamed))
    }
}
