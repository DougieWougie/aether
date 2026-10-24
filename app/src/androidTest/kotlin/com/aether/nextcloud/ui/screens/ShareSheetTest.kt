package com.aether.nextcloud.ui.screens

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.R
import com.aether.nextcloud.support.AetherTestRule
import com.aether.nextcloud.support.awaitTrue
import com.aether.nextcloud.ui.share.ShareUploadActivity
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The share sheet other apps open through ACTION_SEND, on the real activity and graph. */
@RunWith(AndroidJUnit4::class)
class ShareSheetTest {
    private val env = AetherTestRule()
    private val compose = createEmptyComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(env).around(compose)

    private var scenario: ActivityScenario<ShareUploadActivity>? = null

    @After fun close() { scenario?.close() }

    private fun shareText(text: String = "from another app") {
        val intent = Intent(context, ShareUploadActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, "Idea")
        scenario = ActivityScenario.launch(intent)
    }

    private fun waitForText(text: String, timeoutMs: Long = 10_000) = compose.waitForText(text, timeoutMs)

    @Test
    fun theSheetNamesWhatIsSharedAndWhereItWillGo() {
        shareText()

        waitForText(str(R.string.share_upload))
        compose.onNodeWithText("Idea.txt").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.share_destination)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.share_saving_top)).assertIsDisplayed()
    }

    @Test
    fun uploadingSendsTheSharedTextToTheServerAndConfirms() {
        shareText("remember this")
        waitForText(str(R.string.share_upload))

        compose.onNodeWithText(str(R.string.share_upload)).performClick()

        // The confirmation only stays up for a moment before the sheet closes, so catch it first.
        waitForText(context.resources.getQuantityString(R.plurals.share_started, 1, 1))
        awaitTrue("the text file to arrive", timeoutMs = 30_000) { env.nextcloud.text("/Idea.txt") == "remember this" }
    }

    @Test
    fun sharingWithoutAnAccountSendsTheUserToSignInFirst() {
        env.graph.accounts().removeAccount(env.accountName)
        shareText()

        waitForText(str(R.string.share_sign_in_title))
        compose.onNodeWithText(str(R.string.share_open_app)).assertIsDisplayed()
    }

    @Test
    fun aShareWithNothingInItSaysSo() {
        scenario = ActivityScenario.launch(Intent(context, ShareUploadActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain"))

        waitForText(str(R.string.share_empty_title))
        compose.onNodeWithText(str(R.string.share_empty_body)).assertIsDisplayed()
    }
}
