package com.aether.nextcloud.ui.backup

import android.Manifest
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.platform.LocalContext
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import com.aether.nextcloud.ui.components.HeroShape
import com.aether.nextcloud.ui.components.MorphingShapeBadge
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val mediaPermissions: Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CameraBackupSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CameraBackupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings
    var folder by remember(settings.remoteFolder) { mutableStateOf(settings.remoteFolder) }
    var permissionDenied by remember { mutableStateOf(false) }
    var showAlbums by remember { mutableStateOf(false) }
    val availableAlbums by viewModel.albums.collectAsStateWithLifecycle()
    if (showAlbums) {
        AlbumSheet(
            selected = settings.albums,
            available = availableAlbums,
            uploads = state.albumUploads,
            onChange = viewModel::setAlbums,
            onDismiss = { showAlbums = false },
        )
    }

    val requestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        // Partial photo access ("selected photos") still counts: backup then uploads what the user shared with Aether.
        val ok = granted.values.any { it }
        permissionDenied = !ok
        if (ok) viewModel.setEnabled(true)
    }

    Scaffold(modifier = modifier) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                    FilledTonalIconButton(
                        onClick = onBack,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                    Text("Camera backup", style = MaterialTheme.typography.displaySmallEmphasized, modifier = Modifier.padding(start = 8.dp))
                }
            }
            item {
                val colors = MaterialTheme.colorScheme
                Surface(shape = HeroShape, color = colors.tertiaryContainer, contentColor = colors.onTertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Back up photos and videos", style = MaterialTheme.typography.titleLargeEmphasized)
                            when {
                                permissionDenied -> Text("Allow access to photos and videos to turn this on.", style = MaterialTheme.typography.bodyMedium)
                                settings.enabled -> {
                                    Text("${state.backedUpCount}", style = MaterialTheme.typography.displayLargeEmphasized)
                                    Text("${if (state.backedUpCount == 1) "item" else "items"} backed up so far", style = MaterialTheme.typography.bodyLarge)
                                }
                                else -> Text("Only new photos and videos taken after you turn this on are uploaded.", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        MorphingShapeBadge(
                            shape = if (settings.enabled) MaterialShapes.Sunny else MaterialShapes.Cookie9Sided,
                            size = 72.dp,
                            container = colors.tertiary,
                            spinning = settings.enabled && state.running,
                        ) {
                            Icon(Icons.Outlined.PhotoCamera, contentDescription = null, tint = colors.onTertiary, modifier = Modifier.size(30.dp))
                        }
                    }
                }
            }
            if (settings.enabled && state.stats.totalCount > 0) {
                item {
                    val colors = MaterialTheme.colorScheme
                    Surface(shape = HeroShape, color = colors.tertiaryContainer, contentColor = colors.onTertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            state.stats.serverBytes?.let {
                                Text("${Formatter.formatShortFileSize(LocalContext.current, it)} in ${settings.remoteFolder}", style = MaterialTheme.typography.titleMediumEmphasized)
                            }
                            Text("Uploaded from this phone", style = MaterialTheme.typography.labelLargeEmphasized)
                            MediaSplit(state.stats)
                            Text("Recent uploads", style = MaterialTheme.typography.labelLargeEmphasized)
                            RecentUploads(state.stats.recent, viewModel::thumbnail, thumbSize = 72.dp)
                        }
                    }
                }
            }
            item {
                val enabled = settings.enabled
                Surface(
                    onClick = { if (!enabled) requestPermissions.launch(mediaPermissions) else viewModel.setEnabled(false) },
                    shape = CircleShape,
                    color = if (enabled) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primary,
                    contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (enabled) "Turn off backup" else "Turn on backup",
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    )
                }
            }

            if (state.accounts.size > 1) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Upload to", style = MaterialTheme.typography.labelLargeEmphasized)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.accounts.forEach { account ->
                                FilterChip(
                                    selected = account.accountName == settings.accountName,
                                    onClick = { viewModel.setAccount(account.accountName) },
                                    label = { Text(account.accountName) },
                                )
                            }
                        }
                    }
                }
            }

            item {
                AlbumsCard(settings.albums, onClick = { viewModel.loadAlbums(); showAlbums = true })
            }

            item {
                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Destination folder") },
                    supportingText = { Text("Files go into year/month subfolders, e.g. $folder/2026/10") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { viewModel.setFolder(folder) }),
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Only back up when…", style = MaterialTheme.typography.labelLargeEmphasized, modifier = Modifier.padding(start = 8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                        OptionToggle("Wi-Fi", Icons.Outlined.Wifi, settings.unmeteredOnly, viewModel::setUnmeteredOnly, ButtonGroupDefaults.connectedLeadingButtonShapes(), Modifier.weight(1f))
                        OptionToggle("Charging", Icons.Outlined.BatteryChargingFull, settings.chargingOnly, viewModel::setChargingOnly, ButtonGroupDefaults.connectedMiddleButtonShapes(), Modifier.weight(1f))
                        OptionToggle("Videos", Icons.Outlined.Videocam, settings.includeVideos, viewModel::setIncludeVideos, ButtonGroupDefaults.connectedTrailingButtonShapes(), Modifier.weight(1f))
                    }
                    Text(
                        buildString {
                            append(if (settings.unmeteredOnly) "Waits for Wi-Fi" else "Uses mobile data too")
                            append(if (settings.chargingOnly) " · only while charging" else "")
                            append(if (settings.includeVideos) " · videos included (large ones upload in chunks)" else " · photos only")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OptionToggle(
    label: String,
    icon: ImageVector,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    shapes: ToggleButtonShapes,
    modifier: Modifier = Modifier,
) {
    ToggleButton(checked = checked, onCheckedChange = onChange, shapes = shapes, modifier = modifier.heightIn(min = ButtonDefaults.MediumContainerHeight)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ToggleButtonDefaults.IconSize))
        Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
        Text(label, maxLines = 1)
    }
}
