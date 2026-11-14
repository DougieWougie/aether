package com.aether.nextcloud.auth

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.accounts.NetworkErrorException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.aether.nextcloud.MainActivity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class AetherAccountAuthenticator(private val context: Context) : AbstractAccountAuthenticator(context) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun accountCleaner(): AccountCleaner
    }

    /** "Add account" from system Settings opens the Login Flow v2 screen and reports back via the response. */
    override fun addAccount(
        response: AccountAuthenticatorResponse,
        accountType: String,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?,
    ): Bundle {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
        return Bundle().apply { putParcelable(AccountManager.KEY_INTENT, intent) }
    }

    /** Aether never issues auth tokens: the Nextcloud app password stays inside the app. */
    @Throws(NetworkErrorException::class)
    override fun getAuthToken(
        response: AccountAuthenticatorResponse,
        account: Account,
        authTokenType: String,
        options: Bundle?,
    ): Bundle {
        // The app password never leaves Aether: it is used in-process through AccountRepository. Handing it out as an auth token
        // would give it to any app that can request one for this account type, and nothing in Aether asks for one.
        return Bundle().apply {
            putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION)
            putString(AccountManager.KEY_ERROR_MESSAGE, "Aether does not hand out auth tokens")
        }
    }

    /** Settings → Remove account: let it proceed, and clean up Aether's data for it in the background. */
    override fun getAccountRemovalAllowed(response: AccountAuthenticatorResponse, account: Account): Bundle {
        EntryPointAccessors.fromApplication(context.applicationContext, Dependencies::class.java)
            .accountCleaner()
            .remove(account.name, removeFromAccountManager = false)
        return super.getAccountRemovalAllowed(response, account)
    }

    override fun getAuthTokenLabel(authTokenType: String): String = "Nextcloud app password"

    override fun hasFeatures(response: AccountAuthenticatorResponse, account: Account, features: Array<out String>): Bundle =
        Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, features.isEmpty()) }

    override fun editProperties(response: AccountAuthenticatorResponse, accountType: String): Bundle? = null

    override fun confirmCredentials(response: AccountAuthenticatorResponse, account: Account, options: Bundle?): Bundle? = null

    override fun updateCredentials(
        response: AccountAuthenticatorResponse,
        account: Account,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle? = null
}
