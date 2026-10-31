package com.aether.nextcloud.ui.screens

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import java.io.File
import android.provider.DocumentsContract
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aether.nextcloud.AetherConstants
import com.aether.nextcloud.MainActivity
import com.aether.nextcloud.R
import com.aether.nextcloud.data.local.entity.TransferDirection
import com.aether.nextcloud.data.prefs.BackupSettings
import com.aether.nextcloud.data.prefs.SettingsRepository
import com.aether.nextcloud.support.MediaStoreFixtures
import com.aether.nextcloud.data.local.entity.TransferState
import com.aether.nextcloud.provider.DocumentId
import com.aether.nextcloud.support.AetherTestRule
import com.aether.nextcloud.support.awaitTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The Pinned tab's menu and the Files button, with the system pickers and Files app replaced by stubs. */
@RunWith(AndroidJUnit4::class)
class DashboardActionsTest {
    private val env = AetherTestRule()
    private val compose = createEmptyComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(env).around(compose)

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun stubSystemUi() {
        runCatching { InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS") }
        Intents.init()
    }

    @After
    fun release() {
        scenario?.close()
        Intents.release()
    }

    private fun answer(action: String, data: Uri) {
        Intents.intending(hasAction(action)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(data)))
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForText(str(R.string.pinned_empty_title))
    }

    /** The toolbar can lag the screen's text on a slow device, so wait for the button before pressing it. */
    private fun tap(label: Int) {
        val description = str(label)
        compose.waitUntil(10_000) { runCatching { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        compose.onNodeWithContentDescription(description).performClick()
    }

    @Test
    fun pinningAFileFromThePickerListsItAndStartsTheSync() {
        env.nextcloud.putFile("/notes.txt", "hello")
        answer(Intent.ACTION_OPEN_DOCUMENT, DocumentsContract.buildDocumentUri(AetherConstants.AUTHORITY, env.docId("/notes.txt")))
        launch()

        tap(R.string.pin_files)

        compose.waitForText("notes.txt")
        awaitTrue("the pinned copy to be downloaded", timeoutMs = 30_000) {
            env.graph.documents().cached(DocumentId(env.accountName, "/notes.txt"))?.localCachePath != null
        }
    }

    @Test
    fun pinningAFolderFromThePickerListsIt() {
        env.nextcloud.putFile("/Docs/a.txt", "a")
        answer(Intent.ACTION_OPEN_DOCUMENT_TREE, DocumentsContract.buildTreeDocumentUri(AetherConstants.AUTHORITY, env.docId("/Docs")))
        launch()

        tap(R.string.pin_folder)

        compose.waitForText("Docs")
        awaitTrue("the folder to be pinned", timeoutMs = 30_000) {
            runBlocking { env.graph.documents().pinnedDocuments() }.any { it.path == "/Docs" }
        }
    }

    @Test
    fun syncNowAppearsOnceSomethingIsPinnedAndDownloadsIt() {
        env.nextcloud.putFile("/notes.txt", "hello")
        runBlocking {
            val credentials = env.graph.accounts().credentials(env.accountName)!!
            env.graph.documents().refreshChildren(credentials, DocumentId.root(env.accountName))
            env.graph.documents().setPinned(DocumentId(env.accountName, "/notes.txt"), true)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForText("notes.txt")

        tap(R.string.sync_now)

        awaitTrue("the file to be downloaded", timeoutMs = 30_000) {
            env.graph.documents().cached(DocumentId(env.accountName, "/notes.txt"))?.localCachePath != null
        }
    }

    @Test
    fun theFilesButtonOpensTheAccountRootInTheSystemFilesApp() {
        Intents.intending(hasAction(Intent.ACTION_VIEW)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
        launch()

        tap(R.string.action_files)

        compose.waitUntil(10_000) {
            runCatching {
                Intents.intended(allOf(hasAction(Intent.ACTION_VIEW), hasData(DocumentsContract.buildRootUri(AetherConstants.AUTHORITY, env.accountName))))
            }.isSuccess
        }
    }

    @Test
    fun retryFailedRequeuesAStagedUploadAndRemovesItsFailedRow() {
        env.nextcloud.putFile("/seed.txt", "x")
        val staged = File.createTempFile("retry", ".tmp", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir).apply { writeText("kept bytes") }
        val transfers = env.graph.database().transferDao()
        transfers.insert(
            transfer("/retry.txt", TransferState.FAILED, error = "Your Nextcloud storage is full.")
                .copy(accountName = env.accountName, sourcePath = staged.absolutePath, mimeType = "text/plain"),
        )
        transfers.insert(transfer("/gone.txt", TransferState.FAILED, error = "boom").copy(accountName = env.accountName, sourcePath = "/nonexistent/file"))
        try {
            launch()
            compose.onNodeWithText(str(R.string.tab_transfers)).performClick()
            compose.waitUntil(10_000) { runCatching { compose.onNodeWithContentDescription(str(R.string.transfers_retry_failed)).assertIsEnabled() }.isSuccess }

            tap(R.string.transfers_retry_failed)

            awaitTrue("the staged bytes to reach the server", timeoutMs = 30_000) { env.nextcloud.text("/retry.txt") == "kept bytes" }
            val mine = { runBlocking { transfers.observeRecent(100).first() }.filter { it.accountName == env.accountName } }
            awaitTrue("the retried row to be replaced by its new attempt") { mine().none { it.remotePath == "/retry.txt" && it.state == TransferState.FAILED } }
            assertEquals("the row whose file is gone stays", listOf("/gone.txt"), mine().filter { it.state == TransferState.FAILED }.map { it.remotePath })
        } finally {
            staged.delete()
            transfers.deleteAccount(env.accountName)
        }
    }

    private fun openCameraTab() {
        launch()
        compose.onNodeWithText(str(R.string.tab_camera)).performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithContentDescription(str(R.string.backup_now)).assertIsEnabled() }.isSuccess }
    }

    /** Plays the camera app: writes [photo] where it was asked to and answers OK, or answers cancelled when [photo] is null. */
    private fun stubCamera(photo: ByteArray?) {
        Intents.intending(hasAction(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)).respondWithFunction { intent ->
            if (photo == null) {
                Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            } else {
                val target = androidx.core.content.IntentCompat.getParcelableExtra(intent, android.provider.MediaStore.EXTRA_OUTPUT, Uri::class.java)!!
                context.contentResolver.openOutputStream(target)!!.use { it.write(photo) }
                Instrumentation.ActivityResult(Activity.RESULT_OK, null)
            }
        }
    }

    private fun capturedRows() = context.contentResolver.query(
        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(android.provider.MediaStore.MediaColumns._ID),
        "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'AETHER_%'", null, null,
    )!!.use { it.count }

    private fun removeCapturedRows() {
        context.contentResolver.delete(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'AETHER_%'", null)
    }

    @Test
    fun theCameraTabsMainActionIsTakingAPhotoAndFilesStaysOneTapAway() {
        launch()
        compose.onNodeWithContentDescription(str(R.string.action_files)).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithContentDescription(str(R.string.action_take_photo)).fetchSemanticsNodes().isEmpty())

        compose.onNodeWithText(str(R.string.tab_camera)).performClick()

        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(str(R.string.action_take_photo)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(str(R.string.action_files)).assertIsDisplayed()
    }

    @Test
    fun takingAPhotoUploadsItToTheBackupFolder() {
        val photo = ByteArray(3000) { (it * 11).toByte() }
        stubCamera(photo)
        try {
            openCameraTab()

            tap(R.string.action_take_photo)

            compose.waitForText(str(R.string.capture_queued))
            awaitTrue("the photo to reach the server", timeoutMs = 30_000) {
                env.nextcloud.paths().any { it.startsWith("/Photos/Camera Uploads/") && it.endsWith(".jpg") }
            }
            val path = env.nextcloud.paths().single { it.startsWith("/Photos/Camera Uploads/") && it.endsWith(".jpg") }
            assertTrue(env.nextcloud.bytes(path)!!.contentEquals(photo))
        } finally {
            removeCapturedRows()
        }
    }

    @Test
    fun cancellingTheCameraUploadsNothingAndLeavesNoPhotoBehind() {
        stubCamera(null)
        try {
            openCameraTab()

            tap(R.string.action_take_photo)

            // The camera is launched once the photo's file has been reserved, a moment after the tap.
            compose.waitUntil(10_000) { runCatching { Intents.intended(hasAction(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)) }.isSuccess }
            awaitTrue("the camera's empty file to be removed") { java.io.File(context.cacheDir, "captures").listFiles().orEmpty().isEmpty() }
            assertEquals("no gallery copy either", 0, capturedRows())
            Thread.sleep(1_000)
            assertTrue(env.nextcloud.paths().none { it.endsWith(".jpg") })
        } finally {
            removeCapturedRows()
        }
    }

    @Test
    fun backUpNowWithBackupOffSaysSoAndSetUpOpensTheSettings() {
        val settings = SettingsRepository(context)
        runBlocking { settings.updateBackup { BackupSettings() } }
        openCameraTab()

        tap(R.string.backup_now)

        compose.waitForText(str(R.string.backup_off_notice))
        compose.onAllNodesWithText(str(R.string.action_set_up)).onLast().performClick()
        compose.waitForText(str(R.string.backup_turn_on))
    }

    @Test
    fun backUpNowUploadsAPhotoDespiteTheWifiOnlyRule() {
        val settings = SettingsRepository(context)
        val media = MediaStoreFixtures(context)
        media.grantMediaPermissions()
        runBlocking {
            settings.updateBackup { it.copy(enabled = true, accountName = env.accountName, remoteFolder = "/Photos", unmeteredOnly = true, chargingOnly = true) }
        }
        Thread.sleep(1_100)
        media.photo("aether_now.jpg", ByteArray(2048) { (it * 3).toByte() })
        try {
            openCameraTab()

            tap(R.string.backup_now)

            awaitTrue("the photo to reach the server", timeoutMs = 30_000) { env.nextcloud.paths().any { it.endsWith("/aether_now.jpg") } }
        } finally {
            media.deleteAll()
            runBlocking { settings.updateBackup { BackupSettings() } }
        }
    }

    @Test
    fun pauseHoldsBackupAndResumeUploadsWhatWasTakenMeanwhile() {
        val settings = SettingsRepository(context)
        val media = MediaStoreFixtures(context)
        media.grantMediaPermissions()
        runBlocking { settings.updateBackup { it.copy(enabled = true, accountName = env.accountName, remoteFolder = "/Photos") } }
        Thread.sleep(1_100)
        try {
            openCameraTab()

            tap(R.string.backup_pause)
            compose.waitForText(str(R.string.camera_paused))
            awaitTrue("the pause to be stored") { runBlocking { settings.backup.first() }.paused }
            media.photo("aether_while_paused.jpg", ByteArray(2048) { (it * 5).toByte() })
            tap(R.string.backup_now)
            compose.waitForText(str(R.string.backup_paused_notice))
            assertTrue("nothing is uploaded while paused", env.nextcloud.paths().none { it.endsWith("/aether_while_paused.jpg") })

            tap(R.string.backup_resume)

            awaitTrue("the photo to reach the server", timeoutMs = 30_000) { env.nextcloud.paths().any { it.endsWith("/aether_while_paused.jpg") } }
            assertFalse(runBlocking { settings.backup.first() }.paused)
        } finally {
            media.deleteAll()
            runBlocking { settings.updateBackup { BackupSettings() } }
        }
    }

    @Test
    fun retryFailedIsDisabledWhenNothingHasFailed() {
        launch()
        compose.onNodeWithText(str(R.string.tab_transfers)).performClick()

        compose.onNodeWithContentDescription(str(R.string.transfers_retry_failed)).assertIsNotEnabled()
    }

    @Test
    fun clearFinishedRemovesCompletedTransfersButKeepsFailedOnesAndOtherAccounts() {
        val transfers = env.graph.database().transferDao()
        val other = "bob@elsewhere"
        fun mine() = runBlocking { transfers.observeRecent(100).first() }.filter { it.accountName == env.accountName }
        fun theirs() = runBlocking { transfers.observeRecent(100).first() }.filter { it.accountName == other }
        transfers.insert(transfer("/done.txt", TransferState.COMPLETED, TransferDirection.DOWNLOAD).copy(accountName = env.accountName))
        transfers.insert(transfer("/bad.txt", TransferState.FAILED, error = "Your Nextcloud storage is full.").copy(accountName = env.accountName))
        transfers.insert(transfer("/theirs.txt", TransferState.COMPLETED, TransferDirection.DOWNLOAD).copy(accountName = other))
        try {
            launch()
            compose.onNodeWithText(str(R.string.tab_transfers)).performClick()
            compose.waitUntil(10_000) { runCatching { compose.onNodeWithContentDescription(str(R.string.transfers_clear_finished)).assertIsEnabled() }.isSuccess }

            tap(R.string.transfers_clear_finished)

            awaitTrue("the completed transfer to be removed") { mine().none { it.state == TransferState.COMPLETED } }
            assertEquals(listOf("/bad.txt"), mine().map { it.remotePath })
            assertEquals(listOf("/theirs.txt"), theirs().map { it.remotePath })
            compose.onNodeWithContentDescription(str(R.string.transfers_clear_finished)).assertIsNotEnabled()
        } finally {
            transfers.deleteAccount(other)
        }
    }
}
