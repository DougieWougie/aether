package com.aether.nextcloud.ui.login

import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aether.nextcloud.R

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoginScreen(
    onSignedIn: (accountName: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toolbarColor = MaterialTheme.colorScheme.surface.toArgb()

    // Authentication always happens in the user's browser (Custom Tabs), never in a WebView.
    LaunchedEffect(viewModel) {
        viewModel.openBrowser.collect { url ->
            CustomTabsIntent.Builder()
                .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(toolbarColor).build())
                .setShowTitle(true)
                .build()
                .launchUrl(context, Uri.parse(url))
        }
    }
    (state.phase as? LoginPhase.SignedIn)?.let { signedIn ->
        LaunchedEffect(signedIn) { onSignedIn(signedIn.accountName) }
    }

    Scaffold(modifier = modifier.fillMaxSize(), containerColor = MaterialTheme.colorScheme.surface) { padding ->
        Column(Modifier.fillMaxSize().imePadding()) {
            // Colour-block hero: a tall panel with only the bottom corners rounded, holding the morphing mark.
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = true),
                shape = RoundedCornerShape(bottomStart = 56.dp, bottomEnd = 56.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Column(
                    Modifier.padding(top = padding.calculateTopPadding()).padding(horizontal = 24.dp).verticalScroll(rememberScrollState()).fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    MorphingHero(
                        busy = state.busy,
                        size = 220.dp,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayMediumEmphasized)
                    Text(
                        "Your Nextcloud files, right inside Android.",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 24.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnimatedContent(state.phase is LoginPhase.WaitingForBrowser, label = "login-phase") { waiting ->
                    if (waiting) {
                        WaitingForBrowser(onReopen = viewModel::reopenBrowser, onCancel = viewModel::cancel)
                    } else {
                        ServerForm(
                            server = state.server,
                            error = (state.phase as? LoginPhase.Failed)?.message,
                            starting = state.phase is LoginPhase.Starting,
                            onServerChanged = viewModel::onServerChanged,
                            onContinue = viewModel::signIn,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ServerForm(
    server: String,
    error: String?,
    starting: Boolean,
    onServerChanged: (String) -> Unit,
    onContinue: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedTextField(
            value = server,
            onValueChange = onServerChanged,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            label = { Text("Server address") },
            placeholder = { Text("cloud.example.com") },
            singleLine = true,
            isError = error != null,
            supportingText = { Text(error ?: "HTTPS is used unless you say otherwise") },
            enabled = !starting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onContinue() }),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onContinue,
            enabled = server.isNotBlank() && !starting,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.LargeContainerHeight),
            contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.LargeContainerHeight),
        ) {
            if (starting) ContainedLoadingIndicator(Modifier.size(32.dp)) else Text("Continue in browser", style = MaterialTheme.typography.titleMediumEmphasized)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WaitingForBrowser(onReopen: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ContainedLoadingIndicator(Modifier.size(64.dp))
        Text("Waiting for you to sign in…", style = MaterialTheme.typography.titleLargeEmphasized)
        Text(
            "Finish signing in in your browser, then come back. Passkeys, 2FA and SSO all work.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onReopen, shapes = ButtonDefaults.shapes()) { Text("Reopen browser") }
        OutlinedButton(onClick = onCancel, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
    }
}
