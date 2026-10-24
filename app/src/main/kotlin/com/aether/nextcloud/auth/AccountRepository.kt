package com.aether.nextcloud.auth

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.accounts.OnAccountsUpdateListener
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import com.aether.nextcloud.AetherConstants
import com.aether.nextcloud.core.AetherError
import com.aether.nextcloud.core.AetherResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Everything needed to talk to one Nextcloud account. */
data class AetherCredentials(
    val accountName: String,
    val serverUrl: String,
    val loginName: String,
    val appPassword: String,
    val displayName: String,
) {
    private val server: HttpUrl get() = serverUrl.toHttpUrl()

    /** `<server>/remote.php/dav/files/<loginName>/` */
    val webDavBaseUrl: String get() = davUrl("files", emptyList(), trailingSlash = true)

    /** `<server>/remote.php/dav/uploads/<loginName>` (Chunked Upload v2 staging area). */
    val uploadsBaseUrl: String get() = davUrl("uploads", emptyList(), trailingSlash = false)

    /** Percent-encoded URL for [remotePath] (e.g. `/Documents/notes.md`) under the files endpoint. */
    fun fileUrl(remotePath: String): String =
        davUrl("files", remotePath.split('/').filter { it.isNotEmpty() }, trailingSlash = remotePath.endsWith("/"))

    private fun davUrl(kind: String, segments: List<String>, trailingSlash: Boolean): String =
        server.newBuilder().apply {
            addPathSegments("remote.php/dav")
            addPathSegment(kind)
            addPathSegment(loginName)
            segments.forEach { addPathSegment(it) }
            if (trailingSlash) addPathSegment("")
        }.build().toString()
}

@Singleton
class AccountRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val accountManager: AccountManager get() = AccountManager.get(context)

    fun accounts(): List<Account> = accountManager.getAccountsByType(AetherConstants.ACCOUNT_TYPE).toList()

    fun allCredentials(): List<AetherCredentials> = accounts().mapNotNull(::credentialsFor)

    /** Emits the current accounts immediately and again whenever Android's account list changes. */
    fun observeAccounts(): Flow<List<AetherCredentials>> = callbackFlow {
        val listener = OnAccountsUpdateListener { trySend(Unit) }
        accountManager.addOnAccountsUpdatedListener(listener, Handler(Looper.getMainLooper()), true)
        awaitClose { accountManager.removeOnAccountsUpdatedListener(listener) }
    }.conflate().map { allCredentials() }.flowOn(Dispatchers.IO)

    fun credentials(accountName: String): AetherCredentials? =
        accounts().firstOrNull { it.name == accountName }?.let(::credentialsFor)

    /** Stores (or refreshes) the account for a completed Login Flow v2 and tells DocumentsUI the roots changed. */
    fun addAccount(login: LoginCredentials): AetherResult<AetherCredentials> {
        val server = when (val normalized = LoginFlowV2Client.normalizeServerUrl(login.server)) {
            is AetherResult.Success -> normalized.value
            is AetherResult.Failure -> return normalized
        }
        val name = accountNameFor(login.loginName, server)
            ?: return AetherResult.Failure(AetherError.InvalidServer("Invalid server address"))
        val userData = Bundle().apply {
            putString(KEY_SERVER_URL, server)
            putString(KEY_LOGIN_NAME, login.loginName)
            putString(KEY_USER_DISPLAY_NAME, login.loginName)
        }
        val credentials = AetherCredentials(name, server, login.loginName, login.appPassword, login.loginName)
        userData.putString(KEY_WEBDAV_BASE_URL, credentials.webDavBaseUrl)

        val account = Account(name, AetherConstants.ACCOUNT_TYPE)
        if (accounts().any { it.name == name }) {
            accountManager.setPassword(account, login.appPassword)
            userData.keySet().forEach { accountManager.setUserData(account, it, userData.getString(it)) }
        } else if (!accountManager.addAccountExplicitly(account, login.appPassword, userData)) {
            return AetherResult.Failure(AetherError.InvalidServer("Android refused to create the account"))
        }
        notifyRootsChanged()
        return AetherResult.Success(credentials)
    }

    fun setDisplayName(accountName: String, displayName: String) {
        accounts().firstOrNull { it.name == accountName }
            ?.let { accountManager.setUserData(it, KEY_USER_DISPLAY_NAME, displayName) }
    }

    fun removeAccount(accountName: String) {
        accounts().firstOrNull { it.name == accountName }?.let { accountManager.removeAccountExplicitly(it) }
        notifyRootsChanged()
    }

    fun notifyRootsChanged() {
        context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(AetherConstants.AUTHORITY), null)
    }

    private fun credentialsFor(account: Account): AetherCredentials? {
        val server = accountManager.getUserData(account, KEY_SERVER_URL) ?: return null
        val loginName = accountManager.getUserData(account, KEY_LOGIN_NAME) ?: return null
        val password = accountManager.getPassword(account) ?: return null
        val display = accountManager.getUserData(account, KEY_USER_DISPLAY_NAME) ?: loginName
        return AetherCredentials(account.name, server, loginName, password, display)
    }

    companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_LOGIN_NAME = "login_name"
        const val KEY_USER_DISPLAY_NAME = "user_display_name"
        const val KEY_WEBDAV_BASE_URL = "webdav_base_url"

        /** `<loginName>@<host>` (port appended only when non-default), e.g. `alice@cloud.example.com`. */
        fun accountNameFor(loginName: String, serverUrl: String): String? {
            val url = runCatching { serverUrl.toHttpUrl() }.getOrNull() ?: return null
            val defaultPort = if (url.isHttps) 443 else 80
            val host = if (url.port == defaultPort) url.host else "${url.host}:${url.port}"
            return "$loginName@$host"
        }
    }
}
