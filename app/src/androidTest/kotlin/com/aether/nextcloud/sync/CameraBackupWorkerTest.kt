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
import org.junit.Assert.assertNotEquals
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

    /**
     * Turns backup on. MediaStore stamps items in whole seconds and the worker only takes items added after the "since" stamp,
     * so the stamp is set explicitly instead of waiting out the second: by default a few seconds back, so items added by the
     * test right after count; pass [sinceMillis] (an item's own date added) to leave earlier items out.
     */
    private fun enableBackup(videos: Boolean = true, albums: List<BackupAlbum>? = null, sinceMillis: Long = System.currentTimeMillis() - 5_000) {
        runBlocking {
            settings.updateBackup { it.copy(enabled = true, accountName = env.accountName, remoteFolder = folder, includeVideos = videos, albums = albums) }
            settings.updateBackup { it.copy(backupSinceMillis = sinceMillis) }
        }
    }

    private fun runWorkerRaw(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<CameraBackupWorker>(app).setWorkerFactory(app.workerFactory).build().doWork()
    }

    private fun runWorker(): ListenableWorker.Result = runWorkerRaw().withoutOutput()

    /** How many items the run reported trying to upload. */
    private fun attempted(): Int = (runWorkerRaw() as ListenableWorker.Result.Success).outputData.getInt("attempted", -1)

    /** A successful run also reports how many items it tried (see [attempted]); these tests only care that it succeeded. */
    private fun ListenableWorker.Result.withoutOutput() = if (this is ListenableWorker.Result.Success) ListenableWorker.Result.success() else this

    private fun uploaded(): List<String> = env.nextcloud.paths().filter { it.startsWith("$folder/") && it.count { c -> c == '/' } == 5 }

    private fun bytes(seed: Int, size: Int = 2048) = ByteArray(size) { (it * seed).toByte() }

    /** A small JPEG tagged with a GPS position, as a camera app would write it. */
    private fun geotaggedJpeg(): ByteArray {
        val file = java.io.File.createTempFile("geo", ".jpg", app.cacheDir)
        try {
            file.outputStream().use { out ->
                android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            }
            android.media.ExifInterface(file.absolutePath).apply {
                setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE, "51/1,30/1,0/1")
                setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                setAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE, "0/1,7/1,0/1")
                setAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
                saveAttributes()
            }
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    private fun gpsLatitudeOf(bytes: ByteArray): String? {
        val file = java.io.File.createTempFile("served", ".jpg", app.cacheDir)
        try {
            file.writeBytes(bytes)
            return android.media.ExifInterface(file.absolutePath).getAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE)
        } finally {
            file.delete()
        }
    }

    @Test
    fun aGeotaggedPhotoKeepsItsLocationOnTheServer() {
        val original = geotaggedJpeg()
        assertEquals("the fixture itself must carry a location", "51/1,30/1,0/1", gpsLatitudeOf(original))
        enableBackup()
        media.photoFromAnotherApp("geo_one.jpg", original)

        assertEquals(ListenableWorker.Result.success(), runWorker())

        val stored = env.nextcloud.bytes(uploaded().single())!!
        assertEquals("the upload must carry the original bytes", original.size, stored.size)
        assertEquals("GPS location must survive the upload", "51/1,30/1,0/1", gpsLatitudeOf(stored))
    }

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
    fun aRunReportsHowManyItemsItTriedToUpload() {
        enableBackup()
        assertEquals("nothing new yet", 0, attempted())

        media.photo("aether_a.jpg", bytes(21))
        media.photo("aether_b.jpg", bytes(23))
        assertEquals(2, attempted())

        assertEquals("both are backed up now", 0, attempted())
    }

    @Test
    fun aUserRequestedRunIgnoresTheWifiAndChargingRulesAndReportsItsCount() {
        enableBackup()
        runBlocking { settings.updateBackup { it.copy(unmeteredOnly = true, chargingOnly = true) } }
        media.photo("aether_now.jpg", bytes(29))

        val count = runBlocking { CameraBackupWorker.runNowAndCount(app) }

        assertEquals(1, count)
        assertEquals(listOf("aether_now.jpg"), uploaded().map { it.substringAfterLast('/') })
    }

    @Test
    fun photosAddedBeforeBackupWasEnabledAreLeftAlone() {
        val old = media.photo("aether_old.jpg", bytes(3))
        enableBackup(sinceMillis = media.dateAddedMillis(old))

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
