package com.aether.nextcloud.ui.login

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.aether.nextcloud.auth.AccountRepository
import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.auth.AuthIssues
import com.aether.nextcloud.auth.LoginCredentials
import com.aether.nextcloud.auth.LoginFlowV2Client
import com.aether.nextcloud.auth.LoginSession
import com.aether.nextcloud.core.AetherError
import com.aether.nextcloud.core.AetherResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val client = mockk<LoginFlowV2Client>()
    private val accounts = mockk<AccountRepository>()
    private val authIssues = mockk<AuthIssues>(relaxed = true)
    private val context = mockk<Context> { every { getString(any()) } answers { "res${firstArg<Int>()}" } }

    private val session = LoginSession("https://cloud.example.com", "https://cloud.example.com/login/flow/abc", "https://cloud.example.com/login/v2/poll", "tok")
    private val login = LoginCredentials("https://cloud.example.com", "alice", "app-pw")
    private val credentials = AetherCredentials("alice@cloud.example.com", "https://cloud.example.com", "alice", "app-pw", "alice")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle()) = LoginViewModel(saved, client, accounts, authIssues, context)

    @Test
    fun `signing in opens the browser, polls and ends signed in`() = runTest(dispatcher) {
        coEvery { client.start("cloud.example.com") } returns AetherResult.Success(session)
        coEvery { client.awaitCredentials(session.pollEndpoint, session.pollToken, session.server) } returns AetherResult.Success(login)
        every { accounts.addAccount(login) } returns AetherResult.Success(credentials)
        val saved = SavedStateHandle()
        val vm = viewModel(saved)
        vm.onServerChanged("cloud.example.com")

        vm.signIn()
        advanceUntilIdle()

        assertEquals(session.loginUrl, vm.openBrowser.first())
        assertEquals(LoginPhase.SignedIn("alice@cloud.example.com"), vm.state.value.phase)
        coVerify { authIssues.resolve("alice@cloud.example.com") }
        assertNull("the poll token must not outlive the flow", saved.get<String>("poll_token"))
    }

    @Test
    fun `the poll session is saved while the user is in the browser`() = runTest(dispatcher) {
        val never = CompletableDeferred<AetherResult<LoginCredentials>>()
        coEvery { client.start(any()) } returns AetherResult.Success(session)
        coEvery { client.awaitCredentials(any(), any(), any()) } coAnswers { never.await() }
        val saved = SavedStateHandle()
        val vm = viewModel(saved)
        vm.onServerChanged("cloud.example.com")

        vm.signIn()
        advanceUntilIdle()

        assertEquals(LoginPhase.WaitingForBrowser(session.loginUrl), vm.state.value.phase)
        assertTrue(vm.state.value.busy)
        assertEquals(session.pollEndpoint, saved.get<String>("poll_endpoint"))
        assertEquals(session.pollToken, saved.get<String>("poll_token"))
        assertEquals(session.server, saved.get<String>("issuing_server"))
        vm.cancel()
    }

    @Test
    fun `polling resumes from saved state after the process was recreated`() = runTest(dispatcher) {
        coEvery { client.awaitCredentials(session.pollEndpoint, session.pollToken, session.server) } returns AetherResult.Success(login)
        every { accounts.addAccount(login) } returns AetherResult.Success(credentials)
        val saved = SavedStateHandle(mapOf("poll_endpoint" to session.pollEndpoint, "poll_token" to session.pollToken, "login_url" to session.loginUrl, "issuing_server" to session.server))

        val vm = viewModel(saved)
        assertEquals(LoginPhase.WaitingForBrowser(session.loginUrl), vm.state.value.phase)
        advanceUntilIdle()

        assertEquals(LoginPhase.SignedIn("alice@cloud.example.com"), vm.state.value.phase)
        coVerify(exactly = 0) { client.start(any()) }
    }

    @Test
    fun `a partly saved session is ignored`() = runTest(dispatcher) {
        val vm = viewModel(SavedStateHandle(mapOf("poll_token" to "tok")))

        assertEquals(LoginPhase.Idle, vm.state.value.phase)
    }

    @Test
    fun `an unreachable server shows the friendly message, not the exception text`() = runTest(dispatcher) {
        coEvery { client.start(any()) } returns AetherResult.Failure(AetherError.Network(IOException("Failed to connect to /10.0.2.2:9")))
        val vm = viewModel()
        vm.onServerChanged("cloud.example.com")

        vm.signIn()
        advanceUntilIdle()

        val phase = vm.state.value.phase as LoginPhase.Failed
        assertEquals("res${com.aether.nextcloud.R.string.login_unreachable}", phase.message)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `errors without a friendly message fall back to the error text`() = runTest(dispatcher) {
        coEvery { client.start(any()) } returns AetherResult.Failure(AetherError.InvalidServer("Enter your Nextcloud server address"))
        val vm = viewModel()

        vm.signIn()
        advanceUntilIdle()

        assertEquals(LoginPhase.Failed("Enter your Nextcloud server address"), vm.state.value.phase)
    }

    @Test
    fun `a failure while adding the account ends the flow and clears the session`() = runTest(dispatcher) {
        coEvery { client.start(any()) } returns AetherResult.Success(session)
        coEvery { client.awaitCredentials(any(), any(), any()) } returns AetherResult.Success(login)
        every { accounts.addAccount(login) } returns AetherResult.Failure(AetherError.InvalidServer("Android refused to create the account"))
        val saved = SavedStateHandle()
        val vm = viewModel(saved)

        vm.signIn()
        advanceUntilIdle()

        assertEquals(LoginPhase.Failed("Android refused to create the account"), vm.state.value.phase)
        assertNull(saved.get<String>("poll_token"))
    }

    @Test
    fun `a second tap while signing in is ignored`() = runTest(dispatcher) {
        val never = CompletableDeferred<AetherResult<LoginCredentials>>()
        coEvery { client.start(any()) } returns AetherResult.Success(session)
        coEvery { client.awaitCredentials(any(), any(), any()) } coAnswers { never.await() }
        val vm = viewModel()

        vm.signIn()
        vm.signIn()
        advanceUntilIdle()

        coVerify(exactly = 1) { client.start(any()) }
        vm.cancel()
    }

    @Test
    fun `cancel returns to idle and forgets the session`() = runTest(dispatcher) {
        val never = CompletableDeferred<AetherResult<LoginCredentials>>()
        coEvery { client.start(any()) } returns AetherResult.Success(session)
        coEvery { client.awaitCredentials(any(), any(), any()) } coAnswers { never.await() }
        val saved = SavedStateHandle()
        val vm = viewModel(saved)
        vm.signIn()
        advanceUntilIdle()

        vm.cancel()

        assertEquals(LoginPhase.Idle, vm.state.value.phase)
        assertNull(saved.get<String>("poll_endpoint"))
    }

    @Test
    fun `editing the address after a failure clears the error`() = runTest(dispatcher) {
        coEvery { client.start(any()) } returns AetherResult.Failure(AetherError.Timeout)
        val vm = viewModel()
        vm.signIn()
        advanceUntilIdle()
        assertTrue(vm.state.value.phase is LoginPhase.Failed)

        vm.onServerChanged("other.example.com")

        assertEquals(LoginPhase.Idle, vm.state.value.phase)
        assertEquals("other.example.com", vm.state.value.server)
    }

    @Test
    fun `the typed address survives recreation`() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        viewModel(saved).onServerChanged("cloud.example.com")

        assertEquals("cloud.example.com", viewModel(saved).state.value.server)
    }

    @Test
    fun `reopening the browser repeats the login url only while waiting`() = runTest(dispatcher) {
        val never = CompletableDeferred<AetherResult<LoginCredentials>>()
        coEvery { client.start(any()) } returns AetherResult.Success(session)
        coEvery { client.awaitCredentials(any(), any(), any()) } coAnswers { never.await() }
        val vm = viewModel()
        vm.reopenBrowser()
        vm.signIn()
        advanceUntilIdle()
        assertEquals(session.loginUrl, vm.openBrowser.first())

        vm.reopenBrowser()

        assertEquals(session.loginUrl, vm.openBrowser.first())
        vm.cancel()
    }
}
