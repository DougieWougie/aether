package com.aether.nextcloud.ui.screens

import android.content.Intent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.R
import com.aether.nextcloud.auth.LoginCredentials
import com.aether.nextcloud.data.prefs.SettingsRepository
import com.aether.nextcloud.provider.DocumentId
import com.aether.nextcloud.support.AetherTestRule
import com.aether.nextcloud.support.MediaStoreFixtures
import com.aether.nextcloud.support.awaitTrue
import com.aether.nextcloud.ui.share.ShareUploadActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The share sheet other apps open through ACTION_SEND, exercising the real activity, bottom sheet, and upload queue. */
@RunWith(AndroidJUnit4::class)
class ShareSheetTest {
    private val env = AetherTestRule()
    private val compose = createEmptyComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(env).around(compose)

    private val settings by lazy { SettingsRepository(context) }
    private val media by lazy { MediaStoreFixtures(context) }
    private val secondAccount = "bob@other.example.com"
    private var scenario: ActivityScenario<ShareUploadActivity>? = null

    @Before fun grant() = media.grantMediaPermissions()

    @After
    fun clean() {
        scenario?.close()
        media.deleteAll()
        env.graph.accounts().removeAccount(secondAccount)
        runBlocking { settings.forgetAccount(env.accountName); settings.forgetAccount(secondAccount) }
    }

    private fun shareText(text: String = "from another app") {
        val intent = Intent(context, ShareUploadActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, "Idea")
        scenario = ActivityScenario.launch(intent)
    }

    private fun waitForText(text: String, timeoutMs: Long = 10_000) = compose.waitForText(text, timeoutMs)

    @Test
    fun theSheetSwitchesAccountsPicksARecentFolderAndUploadsSharedText() {
        env.graph.accounts().addAccount(LoginCredentials("https://other.example.com", "bob", "pw"))
        runBlocking { settings.rememberFolder(DocumentId(env.accountName, "/Work").toString()) }
        env.nextcloud.mkdirs("/Work")
        shareText("remember this")

        waitForText(str(R.string.share_upload))
        compose.onNodeWithText("Idea.txt").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.share_destination)).assertIsDisplayed()

        // Switch away to the second account and back, then choose the remembered folder.
        compose.onNodeWithText(secondAccount).performClick()
        waitForText(str(R.string.share_saving_top))
        compose.onNodeWithText(env.accountName).performClick()
        compose.onNodeWithText("Work").performClick()
        waitForText(str(R.string.share_saving_to, "/Work"))

        compose.onNodeWithText(str(R.string.share_upload)).performClick()

        waitForText(context.resources.getQuantityString(R.plurals.share_started, 1, 1))
        awaitTrue("the text file to arrive", timeoutMs = 30_000) { env.nextcloud.text("/Work/Idea.txt") == "remember this" }
    }

    @Test
    fun anUnreadableShareShowsADismissibleErrorAndCancelClosesWithoutUploading() {
        val photo = media.photo("vanishing.jpg", ByteArray(32) { 1 })
        scenario = ActivityScenario.launch(
            Intent(context, ShareUploadActivity::class.java).setAction(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, photo.uri),
        )
        waitForText(str(R.string.share_upload))
        media.deleteAll()

        compose.onNodeWithText(str(R.string.share_upload)).performClick()

        waitForText(str(R.string.action_dismiss))
        compose.onNodeWithText(str(R.string.action_dismiss)).performClick()
        compose.onAllNodesWithText(str(R.string.action_dismiss)).assertCountEquals(0)

        compose.onNodeWithText(str(R.string.action_cancel)).performClick()
        compose.waitUntil(10_000) { scenario?.state == Lifecycle.State.DESTROYED }
        assertTrue(env.nextcloud.requestsMatching("PUT").isEmpty())
    }

    @Test
    fun sharingWithoutAnAccountPromptsSignInAndOpensTheApp() {
        env.graph.accounts().removeAccount(env.accountName)
        shareText()

        waitForText(str(R.string.share_sign_in_title))
        compose.onNodeWithText(str(R.string.share_open_app)).performClick()

        compose.waitUntil(10_000) { scenario?.state == Lifecycle.State.DESTROYED }
    }

    @Test
    fun aShareWithNothingInItSaysSo() {
        scenario = ActivityScenario.launch(Intent(context, ShareUploadActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain"))

        waitForText(str(R.string.share_empty_title))
        compose.onNodeWithText(str(R.string.share_empty_body)).assertIsDisplayed()
    }
}
