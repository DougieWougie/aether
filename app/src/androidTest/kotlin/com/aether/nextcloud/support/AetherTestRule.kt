package com.aether.nextcloud.support

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.aether.nextcloud.AetherConstants
import com.aether.nextcloud.auth.AccountRepository
import com.aether.nextcloud.provider.AetherDocumentsProvider
import com.aether.nextcloud.sync.UploadDocumentWorker
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

/**
 * Signs a real Android account in against a [FakeNextcloud] using the app's own graph (Room, WorkManager, OkHttp),
 * so tests exercise the same objects the provider and workers use. Everything is removed afterwards.
 */
class AetherTestRule : ExternalResource() {
    val context: Context = ApplicationProvider.getApplicationContext()
    lateinit var nextcloud: FakeNextcloud
    lateinit var accountName: String
    /** The app's own singletons, through the provider's entry point. */
    val graph: AetherDocumentsProvider.Dependencies get() = EntryPointAccessors.fromApplication(context, AetherDocumentsProvider.Dependencies::class.java)

    override fun before() {
        removeLeftovers()
        nextcloud = FakeNextcloud()
        accountName = AccountRepository.accountNameFor(nextcloud.user, nextcloud.baseUrl)!!
        val userData = android.os.Bundle().apply {
            putString(AccountRepository.KEY_SERVER_URL, nextcloud.baseUrl)
            putString(AccountRepository.KEY_LOGIN_NAME, nextcloud.user)
            putString(AccountRepository.KEY_USER_DISPLAY_NAME, nextcloud.user)
        }
        check(AccountManager.get(context).addAccountExplicitly(Account(accountName, AetherConstants.ACCOUNT_TYPE), "app-password", userData)) {
            "could not create the test account"
        }
    }

    override fun after() {
        UploadDocumentWorker.cancelAll(context, accountName)
        graph.accounts().removeAccount(accountName)
        nextcloud.close()
        runBlocking { graph.documents().forgetAccount(accountName) }
        graph.diskCache().forgetAccount(accountName)
    }

    /** Accounts left by an earlier crashed run would make `queryRoots` ambiguous. */
    private fun removeLeftovers() {
        val manager = AccountManager.get(context)
        manager.getAccountsByType(AetherConstants.ACCOUNT_TYPE).forEach { manager.removeAccountExplicitly(it) }
    }

    fun docId(path: String) = "$accountName:$path"

    fun documentUri(path: String): Uri = DocumentsContract.buildDocumentUri(AetherConstants.AUTHORITY, docId(path))

    fun childrenUri(path: String): Uri = DocumentsContract.buildChildDocumentsUri(AetherConstants.AUTHORITY, docId(path))

    val resolver get() = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
}
