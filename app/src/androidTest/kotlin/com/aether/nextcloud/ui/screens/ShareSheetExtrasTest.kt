package com.aether.nextcloud.ui.screens

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.R
import com.aether.nextcloud.auth.LoginCredentials
import com.aether.nextcloud.data.prefs.SettingsRepository
import com.aether.nextcloud.provider.DocumentId
import com.aether.nextcloud.support.AetherTestRule
import com.aether.nextcloud.support.MediaStoreFixtures
import com.aether.nextcloud.ui.share.ShareUploadActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The share sheet's choices: accounts, recent folders, failures and the way into the app. */
@RunWith(AndroidJUnit4::class)
class ShareSheetExtrasTest {
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

    private fun shareText() {
        scenario = ActivityScenario.launch(
            Intent(context, ShareUploadActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "hello").putExtra(Intent.EXTRA_SUBJECT, "Idea"),
        )
        compose.waitForText(str(R.string.share_upload))
    }

    @Test
    fun withTwoAccountsTheSheetOffersBothAndSwitchingMovesTheDestination() {
        env.graph.accounts().addAccount(LoginCredentials("https://other.example.com", "bob", "pw"))
        shareText()

        compose.onNodeWithText(secondAccount).performClick()

        compose.waitForText(str(R.string.share_saving_top))
        compose.onNodeWithText(env.accountName).assertIsDisplayed()
    }

    @Test
    fun recentFoldersAreOfferedAsChoices() {
        runBlocking { settings.rememberFolder(DocumentId(env.accountName, "/Work").toString()) }
        runBlocking { settings.rememberFolder(DocumentId(env.accountName, "/Photos").toString()) }
        shareText()

        compose.onNodeWithText("Work").performClick()

        compose.waitForText(str(R.string.share_saving_to, "/Work"))
    }

    @Test
    fun anUnreadableShareShowsAMessageThatClearsItself() {
        val photo = media.photo("vanishing.jpg", ByteArray(32) { 1 })
        scenario = ActivityScenario.launch(
            Intent(context, ShareUploadActivity::class.java).setAction(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, photo.uri),
        )
        compose.waitForText(str(R.string.share_upload))
        media.deleteAll()
        val before = visibleTexts()

        compose.onNodeWithText(str(R.string.share_upload)).performClick()

        // The message is the exception's own text, so recognise it as the one piece of text that was not there before.
        compose.waitUntil(10_000) { visibleTexts().any { it !in before } }
        compose.waitUntil(15_000) { visibleTexts().none { it !in before } }
    }

    private fun visibleTexts(): Set<String> = compose.onAllNodes(androidx.compose.ui.test.isRoot().not(), useUnmergedTree = true).fetchSemanticsNodes()
        .flatMap { node ->
            if (androidx.compose.ui.semantics.SemanticsProperties.Text in node.config) node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].map { it.text } else emptyList()
        }.toSet()

    @Test
    fun theSignInPromptOpensTheApp() {
        env.graph.accounts().removeAccount(env.accountName)
        scenario = ActivityScenario.launch(
            Intent(context, ShareUploadActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "hi"),
        )
        compose.waitForText(str(R.string.share_sign_in_title))

        compose.onNodeWithText(str(R.string.share_open_app)).performClick()

        compose.waitUntil(10_000) { scenario?.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
    }

    @Test
    fun cancelClosesTheSheetWithoutUploading() {
        shareText()

        compose.onNodeWithText(str(R.string.action_cancel)).performClick()

        compose.waitUntil(10_000) { scenario?.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
        org.junit.Assert.assertTrue(env.nextcloud.requestsMatching("PUT").isEmpty())
    }
}
