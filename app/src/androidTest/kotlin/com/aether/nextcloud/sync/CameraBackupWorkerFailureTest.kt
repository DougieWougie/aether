package com.aether.nextcloud.sync

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.aether.nextcloud.AetherApp
import com.aether.nextcloud.data.prefs.SettingsRepository
import com.aether.nextcloud.support.AetherTestRule
import com.aether.nextcloud.support.MediaStoreFixtures
import com.aether.nextcloud.support.awaitTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What camera backup does when it cannot do its job: no permission, no account, or a server that says no. */
@RunWith(AndroidJUnit4::class)
class CameraBackupWorkerFailureTest {
    @get:Rule val env = AetherTestRule()

    private val app: AetherApp = ApplicationProvider.getApplicationContext()
    private val settings = SettingsRepository(app)
    private val media = MediaStoreFixtures(app)
    private val folder = "/Photos/Camera Uploads"

    @Before fun grant() = media.grantMediaPermissions()

    @After
    fun cleanUp() {
        media.deleteAll()
        runBlocking {
            settings.updateBackup { it.copy(enabled = false, accountName = null, albums = null) }
            env.graph.authIssues().resolve(env.accountName)
        }
        CameraBackupWorker.cancel(app)
    }

    private fun enableBackup(account: String? = env.accountName, albums: List<com.aether.nextcloud.data.prefs.BackupAlbum>? = null) {
        runBlocking { settings.updateBackup { it.copy(enabled = true, accountName = account, remoteFolder = folder, albums = albums) } }
        Thread.sleep(1_100)
    }

    private fun runWorker(triggered: Boolean = false): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<CameraBackupWorker>(app)
            .setWorkerFactory(app.workerFactory)
            .setInputData(workDataOf("triggered" to triggered))
            .build()
            .doWork()
            .withoutOutput()
    }

    /** A successful run also reports how many items it tried ; these tests only care that it succeeded. */
    private fun ListenableWorker.Result.withoutOutput() = if (this is ListenableWorker.Result.Success) ListenableWorker.Result.success() else this

    private fun bytes(seed: Int, size: Int = 2048) = ByteArray(size) { (it * seed).toByte() }

    private fun nothingUploaded() = env.nextcloud.requestsMatching("PUT").isEmpty()

    @Test
    fun withBackupSwitchedOffNothingHappens() {
        media.photo("aether_off.jpg", bytes(3))

        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertTrue(env.nextcloud.requests.isEmpty())
    }

    @Test
    fun anAccountThatWasRemovedIsLeftAlone() {
        enableBackup(account = "ghost@nowhere")
        media.photo("aether_ghost.jpg", bytes(3))

        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertTrue(env.nextcloud.requests.isEmpty())
    }

    @Test
    fun anEmptyAlbumChoiceBacksUpNothing() {
        enableBackup(albums = emptyList())
        media.photo("aether_noalbum.jpg", bytes(3))

        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertTrue(env.nextcloud.requests.isEmpty())
    }

    @Test
    fun aRejectedPasswordStopsTheRunAndAsksTheUserToSignInAgain() {
        enableBackup()
        media.photo("aether_401.jpg", bytes(3))
        env.nextcloud.forcedStatus = 401

        assertEquals(ListenableWorker.Result.failure(), runWorker())

        awaitTrue("the account to be flagged") { runBlocking { env.graph.authIssues().accounts.first() }.contains(env.accountName) }
    }

    @Test
    fun aServerOutageAsksWorkManagerToTryAgain() {
        enableBackup()
        media.photo("aether_503.jpg", bytes(3))
        env.nextcloud.forcedStatus = 503

        assertEquals(ListenableWorker.Result.retry(), runWorker())
    }

    @Test
    fun anUnreachableServerAsksWorkManagerToTryAgain() {
        enableBackup()
        media.photo("aether_offline.jpg", bytes(3))
        env.nextcloud.close()

        assertEquals(ListenableWorker.Result.retry(), runWorker())
    }

    @Test
    fun aFailedFolderListingIsRetriedRatherThanSkipped() {
        enableBackup()
        media.photo("aether_propfind.jpg", bytes(3))
        env.nextcloud.statusByMethod["PROPFIND"] = 500

        assertEquals(ListenableWorker.Result.retry(), runWorker())
        assertTrue(nothingUploaded())
    }

    @Test
    fun aRefusedFolderIsSkippedAndTheRunStillSucceeds() {
        enableBackup()
        media.photo("aether_forbidden.jpg", bytes(3))
        env.nextcloud.statusByMethod["MKCOL"] = 403

        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertTrue(nothingUploaded())
    }

    @Test
    fun aFullQuotaSkipsTheItemWithoutRetrying() {
        enableBackup()
        media.photo("aether_full.jpg", bytes(3))
        env.nextcloud.quotaFull = true

        assertEquals(ListenableWorker.Result.success(), runWorker())
    }

    @Test
    fun anItemAlreadyBackedUpIsNotSentAgain() {
        enableBackup()
        media.photo("aether_once.jpg", bytes(3))
        runWorker()
        val puts = env.nextcloud.requestsMatching("PUT").size

        runWorker()

        assertEquals(puts, env.nextcloud.requestsMatching("PUT").size)
    }

    @Test
    fun aLargeVideoGoesUpInChunks() {
        enableBackup()
        val big = media.video("aether_big.mp4", ByteArray(11 * 1024 * 1024) { (it % 251).toByte() })

        assertEquals(ListenableWorker.Result.success(), runWorker())

        val path = env.nextcloud.paths().single { it.endsWith("aether_big.mp4") }
        assertEquals(big.bytes.size, env.nextcloud.bytes(path)?.size)
        assertTrue(env.nextcloud.requests.any { it.path.orEmpty().contains("/remote.php/dav/uploads/") && it.method == "MKCOL" })
    }

    @Test
    fun aTriggeredRunQueuesTheNextWatcher() {
        enableBackup()
        val work = WorkManager.getInstance(app)

        runWorker(triggered = true)

        awaitTrue("the next trigger to be queued") { work.getWorkInfosForUniqueWork("camera-backup-trigger").get().any { !it.state.isFinished } }
    }

    @Test
    fun aTriggeredRunThatWillBeRetriedKeepsItsOwnTrigger() {
        enableBackup()
        media.photo("aether_retry.jpg", bytes(3))
        env.nextcloud.forcedStatus = 503
        val work = WorkManager.getInstance(app)
        work.cancelUniqueWork("camera-backup-trigger")

        assertEquals(ListenableWorker.Result.retry(), runWorker(triggered = true))

        assertTrue(work.getWorkInfosForUniqueWork("camera-backup-trigger").get().none { !it.state.isFinished })
    }
}
