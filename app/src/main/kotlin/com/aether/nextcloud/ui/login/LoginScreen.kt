package com.aether.nextcloud.ui.login

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.material3.TextButton
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
    /** Set when signing in again to a known account. */
    initialServer: String? = null,
    /** Non-null when adding/re-authenticating an account from an already signed-in state. */
    onBack: (() -> Unit)? = null,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toolbarColor = MaterialTheme.colorScheme.surface.toArgb()
    LaunchedEffect(initialServer) { initialServer?.let(viewModel::onServerChanged) }

    // Authentication always happens in the user's browser (Custom Tabs), never in a WebView.
    LaunchedEffect(viewModel) {
        viewModel.openBrowser.collect { url ->
            val uri = Uri.parse(url)
            val launched = try {
                CustomTabsIntent.Builder()
                    .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(toolbarColor).build())
                    .setShowTitle(true)
                    .build()
                    .launchUrl(context, uri)
                true
            } catch (_: ActivityNotFoundException) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (_: ActivityNotFoundException) {
                    false
                }
            }
            if (!launched) viewModel.onNoBrowser()
        }
    }
    (state.phase as? LoginPhase.SignedIn)?.let { signedIn ->
        LaunchedEffect(signedIn) { onSignedIn(signedIn.accountName) }
    }

    Scaffold(modifier = modifier.fillMaxSize(), containerColor = MaterialTheme.colorScheme.surface) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
            val wide = maxWidth > maxHeight && maxWidth >= 600.dp
            val compactHeight = maxHeight < 520.dp
            val heroSize = if (compactHeight) 112.dp else 220.dp
            val waitingPhase = state.phase as? LoginPhase.WaitingForBrowser
            val host = state.server.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').ifBlank { null }

            val formContent: @Composable () -> Unit = {
                AnimatedContent(waitingPhase != null, label = "login-phase") { waiting ->
                    if (waiting) {
                        WaitingForBrowser(
                            host = host,
                            onReopen = viewModel::reopenBrowser,
                            onCopyLink = waitingPhase?.loginUrl?.let { url ->
                                {
                                    context.getSystemService(ClipboardManager::class.java)
                                        ?.setPrimaryClip(ClipData.newPlainText("Nextcloud sign-in", url))
                                }
                            },
                            onCancel = viewModel::cancel,
                        )
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

            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Surface(
                        modifier = Modifier.fillMaxHeight().weight(1f),
                        shape = RoundedCornerShape(topEnd = 56.dp, bottomEnd = 56.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            onBack?.let { back ->
                                IconButton(
                                    onClick = back,
                                    modifier = Modifier.align(Alignment.TopStart).padding(top = padding.calculateTopPadding() + 8.dp, start = 8.dp),
                                ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                            }
                            Column(
                                Modifier.padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()).padding(horizontal = 24.dp).verticalScroll(rememberScrollState()).fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                MorphingHero(
                                    busy = state.busy,
                                    size = heroSize,
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayMediumEmphasized)
                                Text(stringResource(R.string.login_tagline), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                            }
                        }
                    }
                    Column(
                        Modifier.fillMaxHeight().weight(1f).verticalScroll(rememberScrollState())
                            .padding(top = padding.calculateTopPadding() + 24.dp, bottom = padding.calculateBottomPadding() + 24.dp, start = 24.dp, end = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) { formContent() }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    // Colour-block hero: a tall panel with only the bottom corners rounded, holding the morphing mark.
                    Surface(
                        modifier = Modifier.fillMaxWidth().weight(if (compactHeight) 0.45f else 1f, fill = true),
                        shape = RoundedCornerShape(bottomStart = 56.dp, bottomEnd = 56.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            onBack?.let { back ->
                                IconButton(
                                    onClick = back,
                                    modifier = Modifier.align(Alignment.TopStart).padding(top = padding.calculateTopPadding() + 8.dp, start = 8.dp),
                                ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                            }
                            Column(
                                Modifier.padding(top = padding.calculateTopPadding()).padding(horizontal = 24.dp).verticalScroll(rememberScrollState()).fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                MorphingHero(
                                    busy = state.busy,
                                    size = heroSize,
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.height(if (compactHeight) 12.dp else 24.dp))
                                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayMediumEmphasized)
                                Text(
                                    stringResource(R.string.login_tagline),
                                    style = MaterialTheme.typography.titleMedium,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }

                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 24.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) { formContent() }
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
            label = { Text(stringResource(R.string.login_server_label)) },
            placeholder = { Text(stringResource(R.string.login_server_placeholder)) },
            singleLine = true,
            isError = error != null,
            supportingText = { Text(error ?: stringResource(R.string.login_server_hint)) },
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
            if (starting) ContainedLoadingIndicator(Modifier.size(32.dp)) else Text(stringResource(R.string.login_continue), style = MaterialTheme.typography.titleMediumEmphasized)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WaitingForBrowser(host: String?, onReopen: () -> Unit, onCopyLink: (() -> Unit)?, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ContainedLoadingIndicator(Modifier.size(64.dp))
        Text(stringResource(R.string.login_waiting_title), style = MaterialTheme.typography.titleLargeEmphasized)
        host?.let {
            Text(stringResource(R.string.login_waiting_server, it), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            stringResource(R.string.login_waiting_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onReopen, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.login_reopen)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            onCopyLink?.let { copy ->
                TextButton(onClick = copy) { Text(stringResource(R.string.login_copy_link)) }
            }
            OutlinedButton(onClick = onCancel, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}
