package com.aether.nextcloud.ui.screens

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import java.io.File
import android.provider.DocumentsContract
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
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
import com.aether.nextcloud.data.local.entity.TransferState
import com.aether.nextcloud.provider.DocumentId
import com.aether.nextcloud.support.AetherTestRule
import com.aether.nextcloud.support.awaitTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
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

    private fun tap(label: Int) = compose.onNodeWithContentDescription(str(label)).performClick()

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
