package com.aether.nextcloud.ui.dashboard

import com.aether.nextcloud.ui.components.rememberNotificationPermissionRequest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import com.aether.nextcloud.R
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aether.nextcloud.AetherConstants
import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.provider.DocumentId

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DashboardScreen(
    onAddAccount: () -> Unit,
    onSignInAgain: (AetherCredentials) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val noFileManager = stringResource(R.string.error_no_file_manager)
    var notice by remember { mutableStateOf<String?>(null) }

    val askForNotifications = rememberNotificationPermissionRequest()

    val listState = rememberLazyListState()
    val rootDocumentUri = state.selected?.let { DocumentsContract.buildDocumentUri(AetherConstants.AUTHORITY, DocumentId.root(it.accountName).toString()) }
    val pickFiles = rememberLauncherForActivityResult(PickAetherDocuments()) { viewModel.pin(it); if (it.isNotEmpty()) askForNotifications() }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) { viewModel.pin(listOf(uri)); askForNotifications() } }
    var fabMenuExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.tab) { fabMenuExpanded = false }
    BackHandler(fabMenuExpanded) { fabMenuExpanded = false }
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 } }

    Scaffold(
        modifier = modifier,
        bottomBar = { DashboardNavigationBar(selected = state.tab, onSelect = viewModel::selectTab) },
        floatingActionButton = {
            state.selected?.let { account ->
                val openFiles = { if (!openInSystemFiles(context, account)) notice = noFileManager }
                if (state.tab == DashboardTab.Pinned) {
                    PinnedFabMenu(
                        expanded = fabMenuExpanded,
                        onExpandedChange = { fabMenuExpanded = it },
                        canSync = state.pinned.isNotEmpty(),
                        onPinFiles = { pickFiles.launch(rootDocumentUri) },
                        onPinFolder = { pickFolder.launch(rootDocumentUri) },
                        onSyncNow = viewModel::syncPinsNow,
                        onOpenFiles = openFiles,
                    )
                } else {
                    ExtendedFloatingActionButton(
                        onClick = openFiles,
                        expanded = fabExpanded,
                        icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                        text = { Text(stringResource(R.string.action_files)) },
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 88.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Header(
                    account = state.selected,
                    avatar = state.avatar,
                    accounts = state.accounts,
                    onSelect = viewModel::selectAccount,
                    onAddAccount = onAddAccount,
                    onSignOut = viewModel::signOut,
                    directShareEnabled = state.directShareEnabled,
                    onDirectShareChange = viewModel::setDirectShare,
                )
            }
            items(state.needsSignIn, key = { it.accountName }) { account -> SignInBanner(account, onSignIn = { onSignInAgain(account) }) }
            item { StorageHero(state, onRetry = viewModel::refresh) }
            item {
                AnimatedContent(state.tab, label = "dashboard-tab") { tab ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (tab) {
                            DashboardTab.Transfers -> TransfersSection(state.activeTransfers, state.recentTransfers)
                            DashboardTab.Pinned -> PinnedSection(pinned = state.pinned, onUnpin = viewModel::unpin)
                            DashboardTab.Camera -> CameraSection(state)
                        }
                    }
                }
            }
            notice?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        }
    }
}

/** Shown while the server rejects an account's credentials; signing in again replaces the app password. */
@Composable
private fun SignInBanner(account: AetherCredentials, onSignIn: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.dashboard_sign_in_banner, account.accountName), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onSignIn) { Text(stringResource(R.string.dashboard_sign_in_action)) }
        }
    }
}

/** Section switcher: a short navigation bar with always-visible labels; the Files FAB floats above it. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DashboardNavigationBar(selected: DashboardTab, onSelect: (DashboardTab) -> Unit) {
    val tabs = listOf(
        Triple(DashboardTab.Pinned, stringResource(R.string.tab_pinned), Icons.Outlined.PushPin),
        Triple(DashboardTab.Camera, stringResource(R.string.tab_camera), Icons.Outlined.PhotoCamera),
        Triple(DashboardTab.Transfers, stringResource(R.string.tab_transfers), Icons.Outlined.Sync),
    )
    ShortNavigationBar {
        tabs.forEach { (tab, label, icon) ->
            ShortNavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(label) },
            )
        }
    }
}

/** Opens DocumentsUI at this account's root, as `DocumentsContract.buildRootUri` documents. */
private fun openInSystemFiles(context: android.content.Context, account: AetherCredentials): Boolean {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(DocumentsContract.buildRootUri(AetherConstants.AUTHORITY, account.accountName), DocumentsContract.Root.MIME_TYPE_ITEM)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
