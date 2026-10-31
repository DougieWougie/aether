package com.aether.nextcloud.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.MainActivity
import com.aether.nextcloud.R
import com.aether.nextcloud.provider.DocumentId
import com.aether.nextcloud.support.AetherTestRule
import com.aether.nextcloud.support.awaitTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The real [MainActivity] with the app's own Hilt graph, signed in to a fake Nextcloud. */
@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    private val env = AetherTestRule()
    private val compose = createEmptyComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(env).around(compose)

    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun close() { scenario?.close() }

    private fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java) }

    private fun waitForText(text: String, timeoutMs: Long = 10_000) = compose.waitForText(text, timeoutMs)

    private fun signOutToLogin() {
        env.graph.accounts().removeAccount(env.accountName)
    }

    @Test
    fun withoutAnAccountTheLoginScreenIsShownAndContinueNeedsAnAddress() {
        signOutToLogin()
        launch()

        waitForText(str(R.string.login_continue))
        compose.onNodeWithText(str(R.string.login_tagline)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.login_continue)).assertIsNotEnabled()

        compose.onNodeWithText(str(R.string.login_server_label)).performTextInput("cloud.example.com")

        compose.onNodeWithText(str(R.string.login_continue)).assertIsEnabled()
        compose.onNodeWithText(str(R.string.login_server_hint)).assertIsDisplayed()
    }

    @Test
    fun anUnreachableServerIsExplainedInPlainLanguage() {
        signOutToLogin()
        launch()
        waitForText(str(R.string.login_continue))

        compose.onNodeWithText(str(R.string.login_server_label)).performTextInput("localhost:9")
        compose.onNodeWithText(str(R.string.login_continue)).performClick()

        waitForText(str(R.string.login_unreachable), timeoutMs = 30_000)
        compose.onNodeWithText(str(R.string.login_continue)).assertIsEnabled()
    }

    @Test
    fun signedInTheDashboardShowsTheAccountQuotaAndTheThreeSections() {
        env.nextcloud.displayName = "Alice Example"
        launch()

        waitForText(str(R.string.storage_used_of, android.text.format.Formatter.formatShortFileSize(context, 100_000_000)))
        awaitTrue("the server's display name to be stored") { env.graph.accounts().credentials(env.accountName)?.displayName == "Alice Example" }
        waitForText("Alice Example")
        compose.onNodeWithText(str(R.string.tab_pinned)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.tab_camera)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.tab_transfers)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.pinned_empty_title)).assertIsDisplayed()
    }

    @Test
    fun theTabsSwitchBetweenPinnedCameraAndTransfers() {
        launch()
        waitForText(str(R.string.pinned_empty_title))

        compose.onNodeWithText(str(R.string.tab_camera)).performClick()
        waitForText(str(R.string.camera_off_body))
        compose.onNodeWithContentDescription(str(R.string.action_settings)).assertIsDisplayed()

        compose.onNodeWithText(str(R.string.tab_transfers)).performClick()
        waitForText(str(R.string.transfers_empty_title))

        compose.onNodeWithText(str(R.string.tab_pinned)).performClick()
        waitForText(str(R.string.pinned_empty_title))
    }

    @Test
    fun swipingMovesBetweenTabsAndStopsAtTheEnds() {
        launch()
        waitForText(str(R.string.pinned_empty_title))

        compose.onRoot().performTouchInput { swipeRight() }
        compose.onNodeWithText(str(R.string.pinned_empty_title)).assertIsDisplayed()

        compose.onRoot().performTouchInput { swipeLeft() }
        waitForText(str(R.string.camera_off_body))

        compose.onRoot().performTouchInput { swipeLeft() }
        waitForText(str(R.string.transfers_empty_title))

        compose.onRoot().performTouchInput { swipeLeft() }
        compose.onNodeWithText(str(R.string.transfers_empty_title)).assertIsDisplayed()

        compose.onRoot().performTouchInput { swipeRight() }
        waitForText(str(R.string.camera_off_body))
    }

    @Test
    fun aQuotaFailureShowsTheRetryNoticeAndRetryRecovers() {
        env.nextcloud.forcedStatus = 500
        launch()
        waitForText(str(R.string.storage_load_failed))

        env.nextcloud.forcedStatus = null
        compose.onNodeWithText(str(R.string.action_retry)).performClick()

        compose.waitForTextGone(str(R.string.storage_load_failed))
    }

    @Test
    fun aPinnedFileIsListedAndUnpinCanBeUndone() {
        env.nextcloud.putFile("/notes.txt", "hello")
        val credentials = env.graph.accounts().credentials(env.accountName)!!
        val id = DocumentId(env.accountName, "/notes.txt")
        runBlocking {
            env.graph.documents().refreshChildren(credentials, DocumentId.root(env.accountName))
            env.graph.documents().setPinned(id, true)
        }
        launch()
        waitForText("notes.txt")

        compose.onNodeWithText(str(R.string.action_unpin)).performClick()

        waitForText(str(R.string.pinned_unpinned, "notes.txt"))
        waitForText(str(R.string.pinned_empty_title))
        compose.onNodeWithText(str(R.string.action_undo)).performClick()
        waitForText("notes.txt")
    }

    @Test
    fun signingOutRemovesTheAccountAndReturnsToLogin() {
        launch()
        waitForText(str(R.string.pinned_empty_title))

        compose.onNodeWithContentDescription(str(R.string.accounts_description)).performClick()
        compose.onNodeWithText(str(R.string.account_sign_out, env.accountName)).performClick()
        compose.onNodeWithText(str(R.string.sign_out_confirm)).performClick()

        waitForText(str(R.string.login_continue))
        awaitTrue("the account to be removed") { env.graph.accounts().credentials(env.accountName) == null }
        assertNull(env.graph.accounts().credentials(env.accountName))
    }

    @Test
    fun aRejectedAccountShowsTheSignInBannerAndSignInAgainOpensLogin() {
        val credentials = env.graph.accounts().credentials(env.accountName)!!
        env.graph.authIssues().report(env.accountName)
        awaitTrue("the issue to be recorded") { runBlocking { env.graph.authIssues().accounts.first() }.contains(env.accountName) }
        launch()
        waitForText(str(R.string.dashboard_sign_in_banner, env.accountName))

        compose.onNodeWithText(str(R.string.dashboard_sign_in_action)).performClick()

        waitForText(str(R.string.login_continue))
        compose.onNodeWithText(credentials.serverUrl).assertIsDisplayed()
        runBlocking { env.graph.authIssues().resolve(env.accountName) }
    }
}
