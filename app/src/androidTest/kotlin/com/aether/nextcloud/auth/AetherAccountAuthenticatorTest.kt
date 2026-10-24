package com.aether.nextcloud.auth

import android.accounts.Account
import android.accounts.AccountManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.AetherConstants
import com.aether.nextcloud.provider.DocumentId
import com.aether.nextcloud.support.AetherTestRule
import com.aether.nextcloud.support.awaitTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Goes through the real [AccountManager], which binds to Aether's authenticator service as other apps and Settings do. */
@RunWith(AndroidJUnit4::class)
class AetherAccountAuthenticatorTest {
    @get:Rule val env = AetherTestRule()

    private val manager get() = AccountManager.get(env.context)
    private val account get() = Account(env.accountName, AetherConstants.ACCOUNT_TYPE)

    @Test
    fun theAuthTokenIsTheAppPassword() {
        val bundle = manager.getAuthToken(account, "any", null, false, null, null).result

        assertEquals("app-password", bundle.getString(AccountManager.KEY_AUTHTOKEN))
        assertEquals(env.accountName, bundle.getString(AccountManager.KEY_ACCOUNT_NAME))
        assertEquals(AetherConstants.ACCOUNT_TYPE, bundle.getString(AccountManager.KEY_ACCOUNT_TYPE))
    }

    @Test
    fun addingAnAccountFromSettingsOpensTheLoginScreen() {
        val bundle = manager.addAccount(AetherConstants.ACCOUNT_TYPE, null, null, null, null, null, null).result

        val intent = bundle.getParcelable<android.content.Intent>(AccountManager.KEY_INTENT)
        assertNotNull(intent)
        assertEquals("com.aether.nextcloud.MainActivity", intent!!.component?.className)
    }

    @Test
    fun theAccountTypeAnswersFeatureQueriesOnlyWithoutFeatures() {
        assertTrue(manager.hasFeatures(account, emptyArray(), null, null).result)
        assertEquals(false, manager.hasFeatures(account, arrayOf("fingerprint"), null, null).result)
    }

    @Test
    fun removingTheAccountInSystemSettingsCleansUpItsData() {
        env.nextcloud.putFile("/a.txt", "a")
        val credentials = env.graph.accounts().credentials(env.accountName)!!
        runBlocking { env.graph.documents().refreshChildren(credentials, DocumentId.root(env.accountName)) }
        assertNotNull(env.graph.documents().cached(DocumentId(env.accountName, "/a.txt")))

        assertTrue(manager.removeAccount(account, null, null, null).result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT))

        awaitTrue("Aether's rows for the account to be removed") { env.graph.documents().cached(DocumentId(env.accountName, "/a.txt")) == null }
        assertNull(env.graph.accounts().credentials(env.accountName))
    }
}
