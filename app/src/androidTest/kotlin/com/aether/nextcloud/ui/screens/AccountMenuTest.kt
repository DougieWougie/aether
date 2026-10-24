package com.aether.nextcloud.ui.screens

import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.R
import com.aether.nextcloud.ui.dashboard.Header
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountMenuTest {
    @get:Rule val compose = createComposeRule()

    private val signedOut = mutableListOf<String>()
    private val selected = mutableListOf<String>()
    private val directShare = mutableListOf<Boolean>()
    private var added = 0

    private fun header(accounts: List<com.aether.nextcloud.auth.AetherCredentials> = listOf(alice), directShareEnabled: Boolean = true) = compose.show {
        Header(
            account = accounts.firstOrNull(), avatar = null, accounts = accounts,
            onSelect = { selected += it }, onAddAccount = { added++ }, onSignOut = { signedOut += it },
            directShareEnabled = directShareEnabled, onDirectShareChange = { directShare += it },
        )
    }

    private fun openMenu() = compose.onNodeWithContentDescription(str(R.string.accounts_description)).performClick()

    @Test
    fun theHeaderShowsTheNameTheInitialAndTheServerWithoutTheScheme() {
        header()

        compose.onNodeWithText("Alice").assertIsDisplayed()
        compose.onNodeWithText("A").assertIsDisplayed()
        compose.onNodeWithText("cloud.example.com").assertIsDisplayed()
    }

    @Test
    fun signingOutAsksForConfirmationAndExplainsWhatIsRemoved() {
        header()
        openMenu()
        compose.onNodeWithText(str(R.string.account_sign_out, alice.accountName)).performClick()

        compose.onNodeWithText(str(R.string.sign_out_title, alice.accountName)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.sign_out_body)).assertIsDisplayed()
        assertTrue("nothing happens before the user confirms", signedOut.isEmpty())

        compose.onNodeWithText(str(R.string.sign_out_confirm)).performClick()

        assertEquals(listOf(alice.accountName), signedOut)
    }

    @Test
    fun cancellingTheConfirmationKeepsTheAccount() {
        header()
        openMenu()
        compose.onNodeWithText(str(R.string.account_sign_out, alice.accountName)).performClick()

        compose.onNodeWithText(str(R.string.action_cancel)).performClick()

        assertTrue(signedOut.isEmpty())
        compose.onNodeWithText(str(R.string.sign_out_body)).assertDoesNotExist()
    }

    @Test
    fun withSeveralAccountsTheCurrentOneIsDisabledAndAnotherCanBePicked() {
        header(accounts = listOf(alice, bob))
        openMenu()

        compose.onNodeWithText(alice.accountName).assertIsNotEnabled()
        compose.onNodeWithText(bob.accountName).performClick()

        assertEquals(listOf(bob.accountName), selected)
    }

    @Test
    fun aSingleAccountIsNotListedForSelection() {
        header()
        openMenu()

        compose.onNodeWithText(alice.accountName).assertDoesNotExist()
    }

    @Test
    fun addAccountAndTheDirectShareSwitchReportTheirActions() {
        header(directShareEnabled = true)
        openMenu()
        compose.onNodeWithText(str(R.string.account_direct_share)).performClick()
        assertEquals(listOf(false), directShare)

        openMenu()
        compose.onNodeWithText(str(R.string.account_add)).performClick()
        assertEquals(1, added)
    }

    @Test
    fun anAvatarReplacesTheInitial() {
        val avatar = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
        compose.show {
            Header(
                account = alice, avatar = avatar, accounts = listOf(alice), onSelect = {}, onAddAccount = {}, onSignOut = {},
                directShareEnabled = true, onDirectShareChange = {},
            )
        }

        compose.onNodeWithText("Alice").assertIsDisplayed()
        compose.onAllNodesWithText("A").assertCountEquals(0)
    }
}
