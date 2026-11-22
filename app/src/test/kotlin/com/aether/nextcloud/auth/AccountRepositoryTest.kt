package com.aether.nextcloud.auth

import android.accounts.AccountManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.AetherConstants
import com.aether.nextcloud.core.AetherError
import com.aether.nextcloud.core.AetherResult
import com.aether.nextcloud.support.awaitTrue
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val repository = AccountRepository(context)
    private val manager get() = AccountManager.get(context)

    private fun clear() = manager.getAccountsByType(AetherConstants.ACCOUNT_TYPE).forEach { manager.removeAccountExplicitly(it) }

    @Before fun before() = clear()
    @After fun after() = clear()

    private fun added(result: AetherResult<AetherCredentials>) = (result as AetherResult.Success).value

    @Test
    fun aFinishedLoginCreatesAnAccountNamedAfterTheUserAndHost() {
        val credentials = added(repository.addAccount(LoginCredentials("https://cloud.example.com", "alice", "app-pw")))

        assertEquals("alice@cloud.example.com", credentials.accountName)
        assertEquals("https://cloud.example.com", credentials.serverUrl)
        assertEquals("https://cloud.example.com/remote.php/dav/files/alice/", credentials.webDavBaseUrl)
        assertEquals(listOf("alice@cloud.example.com"), repository.accounts().map { it.name })
    }

    @Test
    fun theAppPasswordLivesInAccountManagerAndComesBackAsCredentials() {
        repository.addAccount(LoginCredentials("https://cloud.example.com", "alice", "app-pw"))

        val credentials = repository.credentials("alice@cloud.example.com")!!

        assertEquals("app-pw", credentials.appPassword)
        assertEquals("alice", credentials.loginName)
        assertEquals("alice", credentials.displayName)
        assertEquals(listOf(credentials), repository.allCredentials())
    }

    @Test
    fun aNonDefaultPortIsPartOfTheAccountName() {
        val credentials = added(repository.addAccount(LoginCredentials("https://cloud.example.com:8443", "alice", "pw")))

        assertEquals("alice@cloud.example.com:8443", credentials.accountName)
    }

    @Test
    fun plainHttpIsUpgradedToHttps() {
        val credentials = added(repository.addAccount(LoginCredentials("http://cloud.example.com", "alice", "pw")))

        assertEquals("https://cloud.example.com", credentials.serverUrl)
    }

    @Test
    fun aTrailingIndexPhpAndSlashAreDropped() {
        val credentials = added(repository.addAccount(LoginCredentials("https://cloud.example.com/index.php/", "alice", "pw")))

        assertEquals("https://cloud.example.com", credentials.serverUrl)
    }

    @Test
    fun aBlankServerIsRefusedAndNothingIsStored() {
        val result = repository.addAccount(LoginCredentials("  ", "alice", "pw"))

        assertTrue((result as AetherResult.Failure).error is AetherError.InvalidServer)
        assertTrue(repository.accounts().isEmpty())
    }

    @Test
    fun signingInAgainReplacesTheAppPasswordWithoutDuplicatingTheAccount() {
        repository.addAccount(LoginCredentials("https://cloud.example.com", "alice", "old"))

        repository.addAccount(LoginCredentials("https://cloud.example.com", "alice", "new"))

        assertEquals(1, repository.accounts().size)
        assertEquals("new", repository.credentials("alice@cloud.example.com")!!.appPassword)
    }

    @Test
    fun theDisplayNameCanBeChangedAndUnknownAccountsAreIgnored() {
        repository.addAccount(LoginCredentials("https://cloud.example.com", "alice", "pw"))

        repository.setDisplayName("alice@cloud.example.com", "Alice Liddell")
        repository.setDisplayName("nobody@nowhere", "Ghost")

        assertEquals("Alice Liddell", repository.credentials("alice@cloud.example.com")!!.displayName)
        assertNull(repository.credentials("nobody@nowhere"))
    }

    @Test
    fun removingAnAccountDeletesItAndIgnoresUnknownNames() {
        repository.addAccount(LoginCredentials("https://cloud.example.com", "alice", "pw"))
        repository.addAccount(LoginCredentials("https://cloud.example.com", "bob", "pw"))

        repository.removeAccount("alice@cloud.example.com")
        repository.removeAccount("nobody@nowhere")

        assertEquals(listOf("bob@cloud.example.com"), repository.accounts().map { it.name })
    }

    @Test
    fun anAccountMissingItsDetailsIsLeftOutOfTheCredentials() {
        manager.addAccountExplicitly(android.accounts.Account("broken@h", AetherConstants.ACCOUNT_TYPE), "pw", null)

        assertNull(repository.credentials("broken@h"))
        assertTrue(repository.allCredentials().isEmpty())
    }

    @Test
    fun accountNamesFollowTheLoginAndHostRules() {
        assertEquals("alice@host", AccountRepository.accountNameFor("alice", "https://host"))
        assertEquals("alice@host:8080", AccountRepository.accountNameFor("alice", "http://host:8080"))
        assertEquals("alice@host", AccountRepository.accountNameFor("alice", "http://host"))
        assertNull(AccountRepository.accountNameFor("alice", "not a url"))
    }

    @Test
    fun observersHearAboutAdditionsRemovalsAndDetailChanges() {
        val seen = CopyOnWriteArrayList<List<String>>()
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { repository.observeAccounts().collect { list -> seen += list.map { it.displayName } } }
        awaitTrue("the initial empty list") { seen.isNotEmpty() }

        repository.addAccount(LoginCredentials("https://cloud.example.com", "alice", "pw"))
        awaitTrue("the new account") { seen.last() == listOf("alice") }

        repository.setDisplayName("alice@cloud.example.com", "Alice Liddell")
        awaitTrue("the new display name") { seen.last() == listOf("Alice Liddell") }

        repository.removeAccount("alice@cloud.example.com")
        awaitTrue("the removal") { seen.last().isEmpty() }
        scope.cancel()
    }
}
