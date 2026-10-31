package com.aether.nextcloud.sync

import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.AetherApp
import com.aether.nextcloud.data.prefs.BackupSettings
import com.aether.nextcloud.data.prefs.SettingsRepository
import com.aether.nextcloud.support.AetherTestRule
import com.aether.nextcloud.support.awaitTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A photo taken from the Camera tab, with the camera app played by a test that writes into the reserved MediaStore row. */
@RunWith(AndroidJUnit4::class)
class CapturePhotoUploaderTest {
    @get:Rule val env = AetherTestRule()

    private val app: AetherApp = ApplicationProvider.getApplicationContext()
    private val settings = SettingsRepository(app)
    private val resolver = app.contentResolver
    private val reserved = mutableListOf<Uri>()
    private lateinit var uploader: CapturePhotoUploader

    @Before fun setUp() {
        runBlocking { settings.updateBackup { BackupSettings() } }
        uploader = CapturePhotoUploader(app, settings, env.graph.accounts(), env.graph.diskCache(), env.graph.database())
    }

    @After fun cleanUp() {
        reserved.forEach { captureFile(it).delete() }
        resolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'AETHER_%'", null)
        runBlocking { settings.updateBackup { BackupSettings() } }
    }

    private fun reserve(): Uri = runBlocking { uploader.newCaptureUri() }!!.also { reserved += it }

    /** The file the camera app was given, in the app's cache. */
    private fun captureFile(uri: Uri) = java.io.File(app.cacheDir, "captures/${uri.lastPathSegment}")

    private fun galleryRows() = resolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH),
        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'AETHER_%'", null, null,
    )!!.use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) to c.getString(1) else null }.toList() }

    private fun cameraWrites(uri: Uri, bytes: ByteArray) = resolver.openOutputStream(uri)!!.use { it.write(bytes) }

    private fun uploaded() = env.nextcloud.paths().filter { it.substringAfterLast('/').startsWith("AETHER_") }

    @Test
    fun aTakenPhotoIsUploadedToTheBackupFolderEvenWithBackupOff() {
        val photo = ByteArray(4096) { (it * 7).toByte() }
        val uri = reserve()
        cameraWrites(uri, photo)

        val outcome = runBlocking { uploader.upload(uri, preferredAccount = env.accountName) }

        val queued = outcome as CaptureOutcome.Queued
        assertTrue("under the default backup folder by year and month: ${queued.remotePath}", Regex("/Photos/Camera Uploads/\\d{4}/\\d{2}/AETHER_\\d{8}_\\d{6}\\.jpg").matches(queued.remotePath))
        awaitTrue("the photo to reach the server", timeoutMs = 30_000) { env.nextcloud.bytes(queued.remotePath) != null }
        assertArrayEquals(photo, env.nextcloud.bytes(queued.remotePath))
        assertTrue("the missing year and month folders were created", env.nextcloud.exists(queued.remotePath.substringBeforeLast('/')))
        assertTrue("the photo stays in the gallery", galleryRows().any { it.second.startsWith("Pictures/Aether") })
        assertFalse("the camera's cache file is gone", captureFile(uri).exists())
    }

    @Test
    fun theUploadIsLoggedSoCameraBackupDoesNotSendItAgain() {
        val uri = reserve()
        cameraWrites(uri, ByteArray(1024) { it.toByte() })

        runBlocking { uploader.upload(uri, preferredAccount = env.accountName) }

        val galleryId = galleryRows().single().first
        val logged = env.graph.database().backedUpMediaDao().find(env.accountName, galleryId, isVideo = false)
        assertNotNull("the gallery copy is in the backed-up log", logged)
        assertTrue(logged!!.remotePath.endsWith(".jpg"))
        awaitTrue("the upload to finish", timeoutMs = 30_000) { uploaded().isNotEmpty() }
    }

    @Test
    fun theUploadShowsUnderTransfersFromTheMomentThePhotoIsTaken() {
        val uri = reserve()
        cameraWrites(uri, ByteArray(2048) { 3 })

        val queued = runBlocking { uploader.upload(uri, preferredAccount = env.accountName) } as CaptureOutcome.Queued

        val rows = env.graph.database().transferDao().let { runBlocking { it.observeActive().first() + it.observeRecent(20).first() } }
            .filter { it.accountName == env.accountName && it.remotePath == queued.remotePath }
        assertEquals("exactly one transfer row, reused by the worker", 1, rows.size)
        awaitTrue("the row to complete", timeoutMs = 30_000) {
            runBlocking { env.graph.database().transferDao().observeRecent(20).first() }.any { it.accountName == env.accountName && it.remotePath == queued.remotePath && it.state == com.aether.nextcloud.data.local.entity.TransferState.COMPLETED }
        }
        assertEquals("still one row after the upload", 1, runBlocking { env.graph.database().transferDao().observeRecent(20).first() }.count { it.accountName == env.accountName && it.remotePath == queued.remotePath })
    }

    @Test
    fun backupsOwnFolderAndAccountWinOverTheSelectedAccount() {
        runBlocking { settings.updateBackup { it.copy(enabled = true, accountName = env.accountName, remoteFolder = "/Holiday") } }
        val uri = reserve()
        cameraWrites(uri, ByteArray(512) { 1 })

        val outcome = runBlocking { uploader.upload(uri, preferredAccount = "someone-else@nowhere") } as CaptureOutcome.Queued

        assertTrue(outcome.remotePath, outcome.remotePath.startsWith("/Holiday/"))
        awaitTrue("the photo to reach the server", timeoutMs = 30_000) { env.nextcloud.bytes(outcome.remotePath) != null }
    }

    @Test
    fun aCancelledCaptureLeavesNoRowBehind() {
        val uri = reserve()

        cameraWrites(uri, ByteArray(8))
        assertTrue(captureFile(uri).exists())

        runBlocking { uploader.discard(uri) }

        assertFalse(captureFile(uri).exists())
        assertTrue(galleryRows().isEmpty())
    }

    @Test
    fun withNoAccountNothingIsUploadedAndTheRowIsReleased() {
        val uri = reserve()
        cameraWrites(uri, ByteArray(256) { 2 })

        val outcome = runBlocking { uploader.upload(uri, preferredAccount = null) }

        assertEquals(CaptureOutcome.NoAccount, outcome)
        assertFalse(captureFile(uri).exists())
        assertTrue(uploaded().isEmpty())
    }

    @Test
    fun anEmptyCaptureIsReportedAndNotUploaded() {
        val uri = reserve() // the camera app never wrote anything

        val outcome = runBlocking { uploader.upload(uri, preferredAccount = env.accountName) }

        assertEquals(CaptureOutcome.Unreadable, outcome)
        assertFalse(captureFile(uri).exists())
        assertTrue(galleryRows().isEmpty())
        assertTrue(uploaded().isEmpty())
    }
}
