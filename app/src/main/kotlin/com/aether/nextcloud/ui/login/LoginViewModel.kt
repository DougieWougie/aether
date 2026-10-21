package com.aether.nextcloud.ui.login

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aether.nextcloud.auth.AccountRepository
import com.aether.nextcloud.auth.LoginFlowV2Client
import com.aether.nextcloud.core.AetherError
import com.aether.nextcloud.core.AetherResult
import com.aether.nextcloud.core.describe
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface LoginPhase {
    data object Idle : LoginPhase
    data object Starting : LoginPhase
    data class WaitingForBrowser(val loginUrl: String) : LoginPhase
    data class SignedIn(val accountName: String) : LoginPhase
    data class Failed(val message: String) : LoginPhase
}

data class LoginUiState(val server: String = "", val phase: LoginPhase = LoginPhase.Idle) {
    val busy: Boolean get() = phase is LoginPhase.Starting || phase is LoginPhase.WaitingForBrowser
}

/**
 * Drives Login Flow v2 (ADR-0002). The poll endpoint, token and login URL live in [SavedStateHandle], so polling resumes
 * after the process was killed while the user was in the Custom Tab or an authenticator app.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val loginClient: LoginFlowV2Client,
    private val accounts: AccountRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState(server = savedState[KEY_SERVER] ?: ""))
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private val _openBrowser = Channel<String>(Channel.BUFFERED)
    /** One-shot requests to open the login URL in a Custom Tab. */
    val openBrowser: Flow<String> = _openBrowser.receiveAsFlow()

    private var polling: Job? = null

    init {
        val endpoint = savedState.get<String>(KEY_POLL_ENDPOINT)
        val token = savedState.get<String>(KEY_POLL_TOKEN)
        val loginUrl = savedState.get<String>(KEY_LOGIN_URL)
        if (endpoint != null && token != null && loginUrl != null) {
            _state.update { it.copy(phase = LoginPhase.WaitingForBrowser(loginUrl)) }
            startPolling(endpoint, token)
        }
    }

    fun onServerChanged(value: String) {
        savedState[KEY_SERVER] = value
        _state.update { it.copy(server = value, phase = if (it.phase is LoginPhase.Failed) LoginPhase.Idle else it.phase) }
    }

    fun signIn() {
        if (_state.value.busy) return
        _state.update { it.copy(phase = LoginPhase.Starting) }
        viewModelScope.launch {
            when (val started = loginClient.start(_state.value.server)) {
                is AetherResult.Failure -> fail(started.error)
                is AetherResult.Success -> {
                    val session = started.value
                    savedState[KEY_POLL_ENDPOINT] = session.pollEndpoint
                    savedState[KEY_POLL_TOKEN] = session.pollToken
                    savedState[KEY_LOGIN_URL] = session.loginUrl
                    _state.update { it.copy(phase = LoginPhase.WaitingForBrowser(session.loginUrl)) }
                    _openBrowser.send(session.loginUrl)
                    startPolling(session.pollEndpoint, session.pollToken)
                }
            }
        }
    }

    fun reopenBrowser() {
        (_state.value.phase as? LoginPhase.WaitingForBrowser)?.let { _openBrowser.trySend(it.loginUrl) }
    }

    fun cancel() {
        polling?.cancel()
        clearSession()
        _state.update { it.copy(phase = LoginPhase.Idle) }
    }

    private fun startPolling(endpoint: String, token: String) {
        polling?.cancel()
        polling = viewModelScope.launch {
            when (val result = loginClient.awaitCredentials(endpoint, token)) {
                is AetherResult.Failure -> fail(result.error)
                is AetherResult.Success -> when (val added = accounts.addAccount(result.value)) {
                    is AetherResult.Failure -> fail(added.error)
                    is AetherResult.Success -> {
                        clearSession()
                        _state.update { it.copy(phase = LoginPhase.SignedIn(added.value.accountName)) }
                    }
                }
            }
        }
    }

    private fun fail(error: AetherError) {
        clearSession()
        val message = if (error == AetherError.Timeout) "Sign-in timed out. Please try again." else error.describe()
        _state.update { it.copy(phase = LoginPhase.Failed(message)) }
    }

    private fun clearSession() {
        savedState.remove<String>(KEY_POLL_ENDPOINT)
        savedState.remove<String>(KEY_POLL_TOKEN)
        savedState.remove<String>(KEY_LOGIN_URL)
    }

    private companion object {
        const val KEY_SERVER = "server"
        const val KEY_POLL_ENDPOINT = "poll_endpoint"
        const val KEY_POLL_TOKEN = "poll_token"
        const val KEY_LOGIN_URL = "login_url"
    }
}
