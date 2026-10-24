package com.aether.nextcloud

import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aether.nextcloud.auth.AccountRepository
import com.aether.nextcloud.ui.dashboard.DashboardScreen
import com.aether.nextcloud.ui.login.LoginScreen
import com.aether.nextcloud.ui.theme.AetherTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private enum class Route { Home, AddAccount }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var accounts: AccountRepository

    /** Present when Android Settings → "Add account" launched us (ADR-0003); we must answer it exactly once. */
    private var authenticatorResponse: AccountAuthenticatorResponse? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        authenticatorResponse = IntentCompat.getParcelableExtra(
            intent, AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, AccountAuthenticatorResponse::class.java,
        )
        setContent {
            AetherTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AetherNavigation(accounts, onAccountAdded = ::reportAccountAdded)
                }
            }
        }
    }

    private fun reportAccountAdded(accountName: String) {
        val response = authenticatorResponse ?: return
        authenticatorResponse = null
        response.onResult(
            Bundle().apply {
                putString(AccountManager.KEY_ACCOUNT_NAME, accountName)
                putString(AccountManager.KEY_ACCOUNT_TYPE, AetherConstants.ACCOUNT_TYPE)
            },
        )
        finish()
    }

    override fun onDestroy() {
        if (isFinishing) authenticatorResponse?.onError(AccountManager.ERROR_CODE_CANCELED, "canceled")
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AetherNavigation(accounts: AccountRepository, onAccountAdded: (String) -> Unit) {
    // null = still reading the account list from Android
    val signedIn by accounts.observeAccounts().collectAsStateWithLifecycle(initialValue = null)
    var route by rememberSaveable { mutableStateOf(Route.Home) }
    var reauthServer by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(enabled = route != Route.Home) {
        route = Route.Home
        reauthServer = null
    }

    val current = signedIn
    when {
        current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
        current.isEmpty() || route == Route.AddAccount -> LoginScreen(
            initialServer = reauthServer,
            onSignedIn = { name ->
                route = Route.Home
                reauthServer = null
                onAccountAdded(name)
            },
        )
        else -> DashboardScreen(
            onAddAccount = { route = Route.AddAccount },
            onSignInAgain = { account ->
                reauthServer = account.serverUrl
                route = Route.AddAccount
            },
        )
    }
}
