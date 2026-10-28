package com.aether.nextcloud.ui.dashboard

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import com.aether.nextcloud.ui.backup.CameraBackupViewModel
import com.aether.nextcloud.ui.components.rememberNotificationPermissionRequest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aether.nextcloud.AetherConstants
import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.data.local.entity.TransferState
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
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.action_undo)
    val unpinnedFormat = stringResource(R.string.pinned_unpinned)
    val resources = LocalResources.current
    // Hoisted from the Camera tile so the Back up now snackbar can open its settings.
    var cameraSettingsOpen by rememberSaveable { mutableStateOf(false) }
    val cameraBackup: CameraBackupViewModel = hiltViewModel()
    val backUpNowMessages = mapOf(
        BackUpNowEvent.Started to stringResource(R.string.backup_now_started),
        BackUpNowEvent.NothingNew to stringResource(R.string.backup_nothing_new),
        BackUpNowEvent.TurnedOff to stringResource(R.string.backup_off_notice),
        BackUpNowEvent.Paused to stringResource(R.string.backup_paused_notice),
        BackUpNowEvent.NoPermission to stringResource(R.string.backup_no_permission_notice),
    )
    val setUpLabel = stringResource(R.string.action_set_up)
    val resumeLabel = stringResource(R.string.action_resume)

    val tabs = DashboardTab.entries
    val pagerState = rememberPagerState(initialPage = tabs.indexOf(state.tab)) { tabs.size }
    val listStates = tabs.map { rememberLazyListState() }
    val rootDocumentUri = state.selected?.let { DocumentsContract.buildDocumentUri(AetherConstants.AUTHORITY, DocumentId.root(it.accountName).toString()) }
    val pickFiles = rememberLauncherForActivityResult(PickAetherDocuments()) { viewModel.pin(it); if (it.isNotEmpty()) askForNotifications() }
    val pickFolder = rememberLauncherForActivityResult(PickAetherFolder()) { uri -> if (uri != null) { viewModel.pin(listOf(uri)); askForNotifications() } }

    // The view model owns the selected tab (nav bar taps, saved state); the pager follows it, and a settled swipe reports back.
    // The first settled page is skipped: the view model's real tab may not have arrived yet.
    LaunchedEffect(state.tab) {
        val target = tabs.indexOf(state.tab)
        if (pagerState.settledPage != target || pagerState.currentPage != target) pagerState.animateScrollToPage(target)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.drop(1).collect {
            // Transient UI belongs to the tab it was opened on.
            cameraSettingsOpen = false
            snackbars.currentSnackbarData?.dismiss()
            viewModel.selectTab(tabs[it])
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbars) },
        bottomBar = { DashboardNavigationBar(selected = state.tab, onSelect = viewModel::selectTab) },
        floatingActionButton = {
            state.selected?.let { account ->
                val openFiles = { if (!openInSystemFiles(context, account)) notice = noFileManager }
                DashboardToolbar(
                    tab = tabs[pagerState.currentPage],
                    canSync = state.pinned.isNotEmpty(),
                    canBackUpNow = !state.backupRunning,
                    backupPaused = state.backup.paused,
                    canPauseBackup = state.backup.enabled,
                    canRetryFailed = state.recentTransfers.any { it.state == TransferState.FAILED },
                    canClearFinished = state.recentTransfers.any { it.state == TransferState.COMPLETED },
                    onPinFiles = { pickFiles.launch(rootDocumentUri) },
                    onPinFolder = { pickFolder.launch(rootDocumentUri) },
                    onSyncNow = viewModel::syncPinsNow,
                    onBackUpNow = {
                        viewModel.backUpNow { event ->
                            scope.launch {
                                snackbars.currentSnackbarData?.dismiss()
                                val needsSetUp = event == BackUpNowEvent.TurnedOff || event == BackUpNowEvent.NoPermission
                                val action = if (event == BackUpNowEvent.Paused) resumeLabel else setUpLabel.takeIf { needsSetUp }
                                val result = snackbars.showSnackbar(backUpNowMessages.getValue(event), actionLabel = action, duration = SnackbarDuration.Long)
                                if (result == SnackbarResult.ActionPerformed) {
                                    if (event == BackUpNowEvent.Paused) viewModel.setBackupPaused(false) else cameraSettingsOpen = true
                                }
                            }
                        }
                    },
                    onRetryFailed = {
                        viewModel.retryFailedTransfers { outcome ->
                            scope.launch {
                                snackbars.currentSnackbarData?.dismiss()
                                snackbars.showSnackbar(retryMessage(resources, outcome))
                            }
                        }
                    },
                    onTogglePauseBackup = { viewModel.setBackupPaused(!state.backup.paused) },
                    onClearFinished = viewModel::clearFinishedTransfers,
                    onOpenFiles = openFiles,
                )
            }
        },
    ) { padding ->
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val tab = tabs[page]
            LazyColumn(
                state = listStates[page],
                modifier = Modifier.fillMaxSize().imePadding(),
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
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (tab) {
                            DashboardTab.Transfers -> TransfersSection(state.activeTransfers, state.recentTransfers)
                            DashboardTab.Pinned -> PinnedSection(
                                pinned = state.pinned,
                                onUnpin = { doc ->
                                    viewModel.unpin(doc)
                                    scope.launch {
                                        snackbars.currentSnackbarData?.dismiss()
                                        if (snackbars.showSnackbar(unpinnedFormat.format(doc.name), actionLabel = undoLabel, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) viewModel.repin(doc)
                                    }
                                },
                            )
                            DashboardTab.Camera -> CameraSection(state, cameraSettingsOpen, { cameraSettingsOpen = it }, cameraBackup::setEnabled)
                        }
                    }
                }
                notice?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            }
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
