package com.aether.nextcloud.ui.dashboard

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.DocumentsContract
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Close
import androidx.activity.compose.BackHandler
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import kotlin.math.roundToInt
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aether.nextcloud.AetherConstants
import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.data.local.entity.DocumentEntity
import com.aether.nextcloud.data.local.entity.TransferDirection
import com.aether.nextcloud.data.local.entity.TransferEntity
import com.aether.nextcloud.data.local.entity.TransferState
import com.aether.nextcloud.dav.UserInfo
import com.aether.nextcloud.provider.DocumentId
import com.aether.nextcloud.ui.components.HeroShape
import com.aether.nextcloud.ui.components.HeroShapeAlt
import com.aether.nextcloud.ui.components.MorphingShapeBadge
import com.aether.nextcloud.ui.components.groupedShape
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ToggleButtonDefaults

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DashboardScreen(
    onOpenCameraBackup: () -> Unit,
    onAddAccount: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var notice by remember { mutableStateOf<String?>(null) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val listState = rememberLazyListState()
    val rootDocumentUri = state.selected?.let { DocumentsContract.buildDocumentUri(AetherConstants.AUTHORITY, DocumentId.root(it.accountName).toString()) }
    val pickFiles = rememberLauncherForActivityResult(PickAetherDocuments()) { viewModel.pin(it) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) viewModel.pin(listOf(uri)) }
    var fabMenuExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.tab) { fabMenuExpanded = false }
    BackHandler(fabMenuExpanded) { fabMenuExpanded = false }
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 } }

    Scaffold(
        modifier = modifier,
        bottomBar = { DashboardNavigationBar(selected = state.tab, onSelect = viewModel::selectTab) },
        floatingActionButton = {
            state.selected?.let { account ->
                val openFiles = { if (!openInSystemFiles(context, account)) notice = "No file manager found that can open Aether." }
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
                        text = { Text("Files") },
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
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
                )
            }
            item { StorageHero(state, onRetry = viewModel::refresh) }
            item {
                AnimatedContent(state.tab, label = "dashboard-tab") { tab ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (tab) {
                            DashboardTab.Transfers -> TransfersSection(state.activeTransfers, state.recentTransfers)
                            DashboardTab.Pinned -> PinnedSection(pinned = state.pinned, onUnpin = viewModel::unpin)
                            DashboardTab.Camera -> CameraSection(state, onOpenCameraBackup)
                        }
                    }
                }
            }
            notice?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        }
    }
}

/** Section switcher: a short navigation bar with always-visible labels; the Files FAB floats above it. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DashboardNavigationBar(selected: DashboardTab, onSelect: (DashboardTab) -> Unit) {
    val tabs = listOf(
        Triple(DashboardTab.Pinned, "Pinned", Icons.Outlined.PushPin),
        Triple(DashboardTab.Camera, "Camera", Icons.Outlined.PhotoCamera),
        Triple(DashboardTab.Transfers, "Transfers", Icons.Outlined.Sync),
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Header(
    account: AetherCredentials?,
    avatar: ImageBitmap?,
    accounts: List<AetherCredentials>,
    onSelect: (String) -> Unit,
    onAddAccount: () -> Unit,
    onSignOut: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        MorphingShapeBadge(MaterialShapes.Cookie6Sided, size = 52.dp, container = MaterialTheme.colorScheme.tertiaryContainer) {
            if (avatar != null) {
                Image(
                    avatar,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(5.dp).clip(CircleShape),
                )
            } else {
                Text(
                    (account?.displayName ?: "A").take(1).uppercase(),
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(account?.displayName ?: "Aether", style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
            account?.let { Text(it.serverUrl.removePrefix("https://"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        AccountMenu(accounts, account, onSelect, onAddAccount, onSignOut)
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

@Composable
private fun AccountMenu(
    accounts: List<AetherCredentials>,
    selected: AetherCredentials?,
    onSelect: (String) -> Unit,
    onAddAccount: () -> Unit,
    onSignOut: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Accounts") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            if (accounts.size > 1) {
                accounts.forEach { account ->
                    DropdownMenuItem(
                        text = { Text(account.accountName) },
                        onClick = { expanded = false; onSelect(account.accountName) },
                        enabled = account != selected,
                    )
                }
            }
            DropdownMenuItem(text = { Text("Add account") }, onClick = { expanded = false; onAddAccount() })
            selected?.let { account ->
                DropdownMenuItem(
                    text = { Text("Sign out ${account.accountName}") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null) },
                    onClick = { expanded = false; onSignOut(account.accountName) },
                )
            }
        }
    }
}

/** "5.4 GB" -> ("5.4", "GB"); locales use a regular or non-breaking space between number and unit. */
private fun splitSize(formatted: String): Pair<String, String> {
    val clean = formatted.replace('\u00A0', ' ').replace('\u202F', ' ')
    val at = clean.lastIndexOf(' ')
    return if (at < 0) clean to "" else clean.substring(0, at) to clean.substring(at + 1)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun StorageHero(state: DashboardUiState, onRetry: () -> Unit) {
    val context = LocalContext.current
    val quota = state.quota
    val total = quota?.totalBytes
    val syncing = state.activeTransfers.isNotEmpty()
    val failed = state.quotaError != null
    val colors = MaterialTheme.colorScheme
    val fraction = if (quota != null && total != null && total > 0) (quota.usedBytes.toFloat() / total).coerceIn(0f, 1f) else null

    Surface(shape = HeroShape, color = colors.primaryContainer, contentColor = colors.onPrimaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Storage", style = MaterialTheme.typography.labelLargeEmphasized)
                    when {
                        quota != null -> {
                            val (value, unit) = splitSize(Formatter.formatShortFileSize(context, quota.usedBytes))
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(value, style = MaterialTheme.typography.displayLargeEmphasized, maxLines = 1)
                                Text(unit, style = MaterialTheme.typography.headlineMediumEmphasized, modifier = Modifier.padding(bottom = 10.dp))
                            }
                            Text(
                                if (total != null) "used of ${Formatter.formatShortFileSize(context, total)}" else "used · unlimited quota",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        failed -> {
                            Text("Couldn't load", style = MaterialTheme.typography.headlineMediumEmphasized)
                            Text(state.quotaError ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = onRetry) { Text("Retry") }
                        }
                        else -> Text("Loading…", style = MaterialTheme.typography.headlineMediumEmphasized)
                    }
                }
                Box(Modifier.size(116.dp), contentAlignment = Alignment.Center) {
                    QuotaArcGauge(fraction = fraction, modifier = Modifier.size(116.dp))
                    when {
                        failed -> MorphingShapeBadge(shape = MaterialShapes.Pentagon, size = 76.dp, container = colors.errorContainer) {
                            Text("!", style = MaterialTheme.typography.headlineMediumEmphasized, color = colors.onErrorContainer)
                        }
                        syncing -> MorphingShapeBadge(shape = MaterialShapes.Sunny, size = 76.dp, container = colors.primary, spinning = true) {
                            Text("${state.activeTransfers.size}", style = MaterialTheme.typography.titleLargeEmphasized, color = colors.onPrimary)
                        }
                        quota != null -> Text(
                            if (fraction != null) "${(fraction * 100).roundToInt()}%" else "∞",
                            style = MaterialTheme.typography.headlineMediumEmphasized,
                        )
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.storage.pinnedBytes > 0) StatChip("${Formatter.formatShortFileSize(context, state.storage.pinnedBytes)} pinned")
                if (state.storage.cacheBytes > 0) StatChip("${Formatter.formatShortFileSize(context, state.storage.cacheBytes)} cache")
            }
        }
    }
}

/** Open 270° gauge: primary normally, tertiary from 80% and error from 95% so a nearly full account stands out. No fraction (unlimited quota) draws the track only. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QuotaArcGauge(fraction: Float?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val sweepFraction by animateFloatAsState(fraction ?: 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "quotaSweep")
    val track = colors.onPrimaryContainer.copy(alpha = 0.16f)
    val progress = when {
        fraction == null -> colors.primary
        fraction >= 0.95f -> colors.error
        fraction >= 0.8f -> colors.tertiary
        else -> colors.primary
    }
    Canvas(modifier) {
        val stroke = 12.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val style = Stroke(width = stroke, cap = StrokeCap.Round)
        drawArc(track, startAngle = 135f, sweepAngle = 270f, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = style)
        if (fraction != null && sweepFraction > 0f) {
            drawArc(progress, startAngle = 135f, sweepAngle = 270f * sweepFraction, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = style)
        }
    }
}

@Composable
private fun StatChip(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.10f)) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

/** Pin actions live in a FAB menu on the Pinned tab; "Files" stays reachable from it, as on the other tabs. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PinnedFabMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    canSync: Boolean,
    onPinFiles: () -> Unit,
    onPinFolder: () -> Unit,
    onSyncNow: () -> Unit,
    onOpenFiles: () -> Unit,
) {
    fun item(action: () -> Unit): () -> Unit = { onExpandedChange(false); action() }
    FloatingActionButtonMenu(
        expanded = expanded,
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = onExpandedChange,
                modifier = Modifier.semantics {
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                    contentDescription = "Pin menu"
                },
            ) {
                val icon by remember { derivedStateOf { if (checkedProgress > 0.5f) Icons.Outlined.Close else Icons.Outlined.PushPin } }
                Icon(rememberVectorPainter(icon), contentDescription = null, modifier = Modifier.animateIcon({ checkedProgress }))
            }
        },
    ) {
        FloatingActionButtonMenuItem(onClick = item(onPinFiles), text = { Text("Pin files") }, icon = { Icon(Icons.Outlined.PushPin, contentDescription = null) })
        FloatingActionButtonMenuItem(onClick = item(onPinFolder), text = { Text("Pin folder") }, icon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) })
        if (canSync) {
            FloatingActionButtonMenuItem(onClick = item(onSyncNow), text = { Text("Sync now") }, icon = { Icon(Icons.Outlined.Sync, contentDescription = null) })
        }
        FloatingActionButtonMenuItem(onClick = item(onOpenFiles), text = { Text("Files") }, icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) })
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.padding(start = 8.dp, top = 8.dp))
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TransfersSection(active: List<TransferEntity>, recent: List<TransferEntity>) {
    if (active.isEmpty() && recent.isEmpty()) {
        EmptyState(MaterialShapes.Clover4Leaf, Icons.Outlined.Sync, "No transfers", "Uploads from other apps and pinned files show up here.")
        return
    }
    if (active.isNotEmpty()) {
        SectionTitle("In progress")
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            active.forEachIndexed { index, transfer -> TransferRow(transfer, groupedShape(index, active.size), highlighted = true) }
        }
    }
    if (recent.isNotEmpty()) {
        SectionTitle("Recent")
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            recent.forEachIndexed { index, transfer -> TransferRow(transfer, groupedShape(index, recent.size), highlighted = false) }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TransferRow(transfer: TransferEntity, shape: androidx.compose.ui.graphics.Shape, highlighted: Boolean) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val failed = transfer.state == TransferState.FAILED
    val running = transfer.state == TransferState.RUNNING
    val upload = transfer.direction == TransferDirection.UPLOAD
    Surface(shape = shape, color = if (highlighted) colors.secondaryContainer else colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MorphingShapeBadge(
                shape = when {
                    failed -> MaterialShapes.Pentagon
                    upload -> MaterialShapes.Cookie4Sided
                    else -> MaterialShapes.Clover4Leaf
                },
                size = 48.dp,
                container = if (failed) colors.errorContainer else colors.tertiaryContainer,
                spinning = running,
            ) {
                Icon(
                    if (upload) Icons.Outlined.CloudUpload else Icons.Outlined.CloudDownload,
                    contentDescription = null,
                    tint = if (failed) colors.onErrorContainer else colors.onTertiaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(transfer.remotePath.substringAfterLast('/').ifEmpty { "/" }, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when (transfer.state) {
                    TransferState.RUNNING, TransferState.QUEUED -> {
                        if (transfer.totalBytes > 0 && running) {
                            LinearWavyProgressIndicator(
                                progress = { (transfer.bytesTransferred.toFloat() / transfer.totalBytes).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            )
                            Text(
                                "${Formatter.formatShortFileSize(context, transfer.bytesTransferred)} of ${Formatter.formatShortFileSize(context, transfer.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Text(if (transfer.state == TransferState.QUEUED) transfer.error ?: "Waiting for network" else "Starting…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    TransferState.COMPLETED -> Text("Done " + relative(transfer.updatedAt), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    TransferState.CONFLICT -> Text("Saved as a conflict copy · " + relative(transfer.updatedAt), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    TransferState.FAILED -> Text(transfer.error ?: "Failed", style = MaterialTheme.typography.bodyMedium, color = colors.error)
                }
            }
        }
    }
}

/** `ACTION_OPEN_DOCUMENT` limited to one provider's root, returning every picked document. */
private class PickAetherDocuments : ActivityResultContract<Uri?, List<Uri>>() {
    override fun createIntent(context: android.content.Context, input: Uri?): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .apply { input?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) } }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != android.app.Activity.RESULT_OK || intent == null) return emptyList()
        val clip = intent.clipData
        return if (clip != null) (0 until clip.itemCount).map { clip.getItemAt(it).uri } else listOfNotNull(intent.data)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PinnedSection(
    pinned: List<DocumentEntity>,
    onUnpin: (DocumentEntity) -> Unit,
) {
    if (pinned.isEmpty()) {
        EmptyState(MaterialShapes.Cookie9Sided, Icons.Outlined.PushPin, "Nothing pinned yet", "Pinned files and folders are kept on this device and stay up to date.")
        return
    }
    SectionTitle("Kept on this device")
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        pinned.forEachIndexed { index, doc ->
            Surface(shape = groupedShape(index, pinned.size), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MorphingShapeBadge(
                        shape = if (doc.isDirectory) MaterialShapes.Square else MaterialShapes.Cookie6Sided,
                        size = 48.dp,
                        container = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Icon(
                            if (doc.isDirectory) Icons.Outlined.FolderOpen else Icons.Outlined.PushPin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(doc.name.ifEmpty { "/" }, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(doc.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { onUnpin(doc) }) { Text("Unpin") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CameraSection(state: DashboardUiState, onConfigure: () -> Unit) {
    val backup = state.backup
    val colors = MaterialTheme.colorScheme
    Surface(shape = HeroShapeAlt, color = colors.tertiaryContainer, contentColor = colors.onTertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (backup.enabled) "Backing up" else "Camera backup", style = MaterialTheme.typography.labelLargeEmphasized)
                    if (backup.enabled) {
                        Text("${state.backedUpCount}", style = MaterialTheme.typography.displayLargeEmphasized)
                        Text("${if (state.backedUpCount == 1) "item" else "items"} saved to ${backup.remoteFolder}", style = MaterialTheme.typography.titleMedium)
                    } else {
                        Text("Off", style = MaterialTheme.typography.displayMediumEmphasized)
                        Text("Upload new photos and videos automatically.", style = MaterialTheme.typography.titleMedium)
                    }
                }
                MorphingShapeBadge(
                    shape = if (backup.enabled) MaterialShapes.Sunny else MaterialShapes.Cookie9Sided,
                    size = 84.dp,
                    container = colors.tertiary,
                    spinning = backup.enabled && state.backupRunning,
                ) {
                    Icon(Icons.Outlined.PhotoCamera, contentDescription = null, tint = colors.onTertiary, modifier = Modifier.size(34.dp))
                }
            }
            state.lastBackupAt?.takeIf { backup.enabled }?.let { Text("Last backup " + relative(it), style = MaterialTheme.typography.bodyMedium) }
            Button(
                onClick = onConfigure,
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(containerColor = colors.tertiary, contentColor = colors.onTertiary),
            ) { Text(if (backup.enabled) "Settings" else "Set up") }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptyState(shape: androidx.graphics.shapes.RoundedPolygon, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MorphingShapeBadge(shape, size = 112.dp, container = MaterialTheme.colorScheme.secondaryContainer) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.headlineSmallEmphasized)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

private fun relative(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
