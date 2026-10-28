package com.aether.nextcloud.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.MainActivity
import com.aether.nextcloud.R
import com.aether.nextcloud.support.AetherTestRule
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraSetupTest {
    private val env = AetherTestRule()
    private val compose = createEmptyComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(env).around(compose)

    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun close() { scenario?.close() }

    private fun waitForText(text: String, timeoutMs: Long = 10_000) = compose.waitForText(text, timeoutMs)

    @Test
    fun settingUpOffersTheSwitchTheDestinationAndTheAlbumChoice() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText(str(R.string.tab_camera))
        compose.onNodeWithText(str(R.string.tab_camera)).performClick()
        waitForText(str(R.string.camera_off))

        compose.onNodeWithContentDescription(str(R.string.action_settings)).performClick()

        waitForText(str(R.string.backup_turn_on))
        compose.onNodeWithText(str(R.string.backup_new_only)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.backup_section_what)).assertExists()
        compose.onNodeWithText(str(R.string.backup_section_where)).assertExists()
        compose.onNodeWithText(str(R.string.backup_section_when)).assertExists()
    }

    @Test
    fun backClosesTheSettingsSheet() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText(str(R.string.tab_camera))
        compose.onNodeWithText(str(R.string.tab_camera)).performClick()
        waitForText(str(R.string.camera_off))
        compose.onNodeWithContentDescription(str(R.string.action_settings)).performClick()
        waitForText(str(R.string.backup_turn_on))

        androidx.test.espresso.Espresso.pressBack()

        compose.waitUntil(10_000) { compose.onAllNodesWithText(str(R.string.backup_turn_on)).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(str(R.string.camera_off)).assertIsDisplayed()
    }
}
