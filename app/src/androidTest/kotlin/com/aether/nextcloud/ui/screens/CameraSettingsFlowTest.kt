package com.aether.nextcloud.ui.screens

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aether.nextcloud.MainActivity
import com.aether.nextcloud.R
import com.aether.nextcloud.auth.LoginCredentials
import com.aether.nextcloud.data.prefs.BackupSettings
import com.aether.nextcloud.data.prefs.SettingsRepository
import com.aether.nextcloud.support.AetherTestRule
import com.aether.nextcloud.support.MediaStoreFixtures
import com.aether.nextcloud.support.awaitTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Camera backup set up through the real screen: switch, chips, destination and the album sheet. */
@RunWith(AndroidJUnit4::class)
class CameraSettingsFlowTest {
    private val env = AetherTestRule()
    private val compose = createEmptyComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(env).around(compose)

    private val settings by lazy { SettingsRepository(context) }
    private val media by lazy { MediaStoreFixtures(context) }
    private var scenario: ActivityScenario<MainActivity>? = null
    private val secondAccount = "bob@other.example.com"

    @Before
    fun allow() {
        media.grantMediaPermissions()
        // Granting never restarts the app (revoking does); with this the notification prompt cannot cover the screen.
        runCatching { InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS") }
        runBlocking { settings.updateBackup { BackupSettings() } }
    }

    @After
    fun clean() {
        scenario?.close()
        media.deleteAll()
        env.graph.accounts().removeAccount(secondAccount)
        runBlocking { settings.updateBackup { BackupSettings() } }
    }

    private fun backup() = runBlocking { settings.backup.first() }

    private fun openCameraTab() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForText(str(R.string.tab_camera))
        compose.onNodeWithText(str(R.string.tab_camera)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(str(R.string.action_settings)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openSettings() {
        openCameraTab()
        compose.onNodeWithContentDescription(str(R.string.action_settings)).performClick()
        compose.waitForText(str(R.string.backup_settings_title))
    }

    /** The sheet's "All albums" switch; the settings sheet and the tile hold toggles of their own. */
    private fun allAlbumsSwitch() = compose.onNode(isToggleable() and hasAnySibling(hasText(str(R.string.albums_all_hint))), useUnmergedTree = true)

    private fun tileSwitch() = compose.onNode(isToggleable() and hasContentDescription(str(R.string.camera_title)))

    @Test
    fun turningBackupOnAndOffFollowsTheSwitch() {
        openCameraTab()

        tileSwitch().performSemanticsAction(SemanticsActions.OnClick)
        awaitTrue("backup to turn on") { backup().enabled }
        assertEquals(env.accountName, backup().accountName)

        compose.waitForText(str(R.string.camera_backing_up))
        tileSwitch().performSemanticsAction(SemanticsActions.OnClick)

        awaitTrue("backup to turn off") { !backup().enabled }
        compose.waitForText(str(R.string.camera_off))
    }

    @Test
    fun theSheetsTurnOnButtonFinishesSettingUpAndFoldsTheSheetAway() {
        openSettings()

        compose.onNodeWithText(str(R.string.backup_turn_on)).performClick()

        awaitTrue("backup to turn on") { backup().enabled }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(str(R.string.backup_settings_title)).fetchSemanticsNodes().isEmpty() }
        compose.waitForText(str(R.string.camera_backing_up))
    }

    @Test
    fun theOptionSwitchesFlipTheirSettingsAndTheTileSummaryFollows() {
        runBlocking { settings.updateBackup { it.copy(enabled = true, accountName = env.accountName) } }
        openSettings()
        assertTrue(backup().unmeteredOnly)

        compose.onNodeWithText(str(R.string.option_wifi)).performSemanticsAction(SemanticsActions.OnClick)
        awaitTrue("Wi-Fi only to turn off") { !backup().unmeteredOnly }
        compose.onNodeWithText(str(R.string.option_charging)).performSemanticsAction(SemanticsActions.OnClick)
        awaitTrue("charging only to turn on") { backup().chargingOnly }
        compose.onNodeWithText(str(R.string.option_videos)).performSemanticsAction(SemanticsActions.OnClick)
        awaitTrue("videos to turn off") { !backup().includeVideos }

        compose.waitForText(
            listOf(str(R.string.backup_mobile_too), str(R.string.backup_charging_only), str(R.string.backup_photos_only)).joinToString(" · "),
        )
    }

    @Test
    fun theDestinationFolderIsNormalizedWhenEntered() {
        openSettings()

        compose.onNodeWithText(str(R.string.backup_destination)).performTextReplacement("Holiday//2026/")
        compose.onNodeWithText(str(R.string.backup_destination)).performImeAction()

        awaitTrue("the folder to be saved") { backup().remoteFolder == "/Holiday/2026" }
    }

    @Test
    fun withTwoAccountsTheDestinationAccountCanBeChosen() {
        env.graph.accounts().addAccount(LoginCredentials("https://other.example.com", "bob", "pw"))
        openSettings()

        compose.waitForText(str(R.string.backup_upload_to))
        compose.onNodeWithText(secondAccount).performSemanticsAction(SemanticsActions.OnClick)

        awaitTrue("the account to be chosen") { backup().accountName == secondAccount }
    }

    @Test
    fun theAlbumSheetListsAlbumsAndLetsTheUserNarrowThemDown() {
        media.photo("a1.jpg", ByteArray(16) { 1 }, album = "AetherSheetBig")
        media.photo("a2.jpg", ByteArray(16) { 2 }, album = "AetherSheetBig")
        media.photo("b1.jpg", ByteArray(16) { 3 }, album = "AetherSheetSmall")
        openSettings()

        compose.onNodeWithText(str(R.string.albums_all)).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForText(str(R.string.albums_picker_title))
        compose.waitForText("AetherSheetBig")
        compose.onNodeWithText(str(R.string.album_on_phone, 2)).assertExists()

        // "All albums" off: every album is selected explicitly.
        allAlbumsSwitch().performClick()
        awaitTrue("an explicit album list") { backup().albums != null }
        assertTrue(backup().albums!!.any { it.name == "AetherSheetBig" })

        // Untick everything but one; that last one is locked so the list never goes empty.
        val names = backup().albums!!.map { it.name }
        names.filter { it != "AetherSheetBig" }.forEach { name ->
            compose.onNode(isToggleable() and hasAnySibling(hasText(name)), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        }
        awaitTrue("one album left") { backup().albums?.map { it.name } == listOf("AetherSheetBig") }
        compose.onNode(isToggleable() and hasAnySibling(hasText("AetherSheetBig")), useUnmergedTree = true).assertIsNotEnabled()
        compose.onNodeWithText("${str(R.string.album_on_phone, 2)} · ${str(R.string.album_keep_one)}").assertExists()

        // Back to every album, including ones created later.
        allAlbumsSwitch().performClick()
        awaitTrue("all albums again") { backup().albums == null }
    }

    @Test
    fun ticksCanBeAddedBackToAnAlbumList() {
        media.photo("a1.jpg", ByteArray(16) { 1 }, album = "AetherSheetOne")
        media.photo("b1.jpg", ByteArray(16) { 3 }, album = "AetherSheetTwo")
        openSettings()
        compose.onNodeWithText(str(R.string.albums_all)).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForText("AetherSheetOne")
        allAlbumsSwitch().performClick()
        awaitTrue("an explicit album list") { backup().albums != null }

        compose.onNode(isToggleable() and hasAnySibling(hasText("AetherSheetOne")), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        awaitTrue("the album to be unticked") { backup().albums!!.none { it.name == "AetherSheetOne" } }
        compose.onNode(isToggleable() and hasAnySibling(hasText("AetherSheetOne")), useUnmergedTree = true).assertIsEnabled()
        compose.onNode(isToggleable() and hasAnySibling(hasText("AetherSheetOne")), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)

        awaitTrue("the album to be ticked again") { backup().albums!!.any { it.name == "AetherSheetOne" } }
        assertFalse(backup().albums.isNullOrEmpty())
    }
}
