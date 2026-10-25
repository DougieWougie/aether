package com.aether.nextcloud.ui.screens

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
import com.aether.nextcloud.provider.DocumentId
import com.aether.nextcloud.support.AetherTestRule
import com.aether.nextcloud.support.awaitTrue
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.junit.After
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
}
