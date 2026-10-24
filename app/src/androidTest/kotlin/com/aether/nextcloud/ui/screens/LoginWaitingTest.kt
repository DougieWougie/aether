package com.aether.nextcloud.ui.screens

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.MainActivity
import com.aether.nextcloud.R
import com.aether.nextcloud.support.AetherTestRule
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The part of sign-in where the user is in the browser. A login that was already started comes back through the saved
 * state (here seeded through the intent extras), which is exactly how it survives a killed process.
 */
@RunWith(AndroidJUnit4::class)
class LoginWaitingTest {
    private val env = AetherTestRule()
    private val compose = createEmptyComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(env).around(compose)

    private val loginUrl = "https://cloud.example.com/index.php/login/v2/flow/abc"
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun stubBrowser() {
        env.graph.accounts().removeAccount(env.accountName)
        Intents.init()
        Intents.intending(hasAction(Intent.ACTION_VIEW)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
    }

    @After
    fun release() {
        scenario?.close()
        Intents.release()
    }

    private fun resumeLogin() {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("poll_endpoint", "${env.nextcloud.baseUrl}/index.php/login/v2/poll")
            .putExtra("poll_token", "token")
            .putExtra("login_url", loginUrl)
            .putExtra("issuing_server", "https://cloud.example.com")
        scenario = ActivityScenario.launch(intent)
    }

    @Test
    fun aLoginInProgressShowsTheWaitingScreen() {
        resumeLogin()

        compose.waitForText(str(R.string.login_waiting_title))
        compose.onNodeWithText(str(R.string.login_waiting_body)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.login_reopen)).assertIsDisplayed()
    }

    @Test
    fun reopeningTheBrowserLaunchesTheLoginPageInACustomTab() {
        resumeLogin()
        compose.waitForText(str(R.string.login_reopen))

        compose.onNodeWithText(str(R.string.login_reopen)).performClick()

        compose.waitUntil(10_000) { runCatching { Intents.intended(allOf(hasAction(Intent.ACTION_VIEW), hasData(Uri.parse(loginUrl)))) }.isSuccess }
    }

    @Test
    fun cancellingReturnsToTheAddressForm() {
        resumeLogin()
        compose.waitForText(str(R.string.login_waiting_title))

        compose.onNodeWithText(str(R.string.action_cancel)).performClick()

        compose.waitForText(str(R.string.login_continue))
        compose.waitForTextGone(str(R.string.login_waiting_title))
    }
}
