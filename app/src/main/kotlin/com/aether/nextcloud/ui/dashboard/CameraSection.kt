package com.aether.nextcloud.ui.dashboard

import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.pluralStringResource
import com.aether.nextcloud.R
import androidx.compose.ui.res.stringResource
import android.text.format.Formatter
import androidx.compose.ui.semantics.contentDescription
import com.aether.nextcloud.ui.backup.CameraSettingsSheet
import com.aether.nextcloud.ui.backup.rememberBackupToggle
import com.aether.nextcloud.ui.backup.MediaSplit
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aether.nextcloud.ui.components.HeroShapeAlt
import com.aether.nextcloud.ui.components.MorphingShapeBadge
import androidx.compose.animation.animateContentSize
import android.content.ActivityNotFoundException
import android.content.Intent

/** The tile with its own settings sheet; the dashboard hoists that state instead, so Back up now can open it. */
@Composable
internal fun CameraSection(state: DashboardUiState, onBackupEnabledChange: (Boolean) -> Unit = {}) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    CameraSection(state, expanded, { expanded = it }, onBackupEnabledChange)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CameraSection(state: DashboardUiState, expanded: Boolean, onExpandedChange: (Boolean) -> Unit, onBackupEnabledChange: (Boolean) -> Unit = {}) {
    // Turning backup on is the end of setting it up, so fold the settings away and let the tile show the result.
    var wasEnabled by remember { mutableStateOf(state.backup.enabled) }
    LaunchedEffect(state.backup.enabled) {
        if (state.backup.enabled && !wasEnabled) onExpandedChange(false)
        wasEnabled = state.backup.enabled
    }
    val context = LocalContext.current
    val backup = state.backup
    val stats = state.backupStats
    val colors = MaterialTheme.colorScheme
    val toggle = rememberBackupToggle(backup.enabled, onBackupEnabledChange)
    val title = stringResource(R.string.camera_title)
    if (expanded) CameraSettingsSheet(onDismiss = { onExpandedChange(false) })
    Surface(
        shape = HeroShapeAlt,
        color = colors.tertiaryContainer,
        contentColor = colors.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (!backup.enabled) R.string.camera_title else if (backup.paused) R.string.camera_paused else R.string.camera_backing_up),
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onExpandedChange(true) }) {
                    Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.action_settings))
                }
                Switch(
                    checked = backup.enabled,
                    onCheckedChange = toggle.onToggle,
                    modifier = Modifier.semantics { contentDescription = title },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = colors.tertiary,
                        checkedThumbColor = colors.onTertiary,
                        uncheckedTrackColor = Color.Transparent,
                        uncheckedThumbColor = colors.onTertiaryContainer.copy(alpha = 0.7f),
                        uncheckedBorderColor = colors.onTertiaryContainer.copy(alpha = 0.7f),
                    ),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (backup.enabled) {
                        // The server's folder size also counts photos other devices put there; fall back to this phone's total until it arrives.
                        val shown = stats.serverBytes ?: stats.localBytes
                        val (value, unit) = splitSize(Formatter.formatShortFileSize(context, shown))
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(value, style = MaterialTheme.typography.displayLargeEmphasized, maxLines = 1)
                            Text(unit, style = MaterialTheme.typography.headlineMediumEmphasized, modifier = Modifier.padding(bottom = 10.dp))
                        }
                        Text(
                            when {
                                stats.serverBytes != null -> stringResource(R.string.camera_in_folder, backup.remoteFolder)
                                stats.totalCount == 0 -> stringResource(R.string.camera_waiting)
                                else -> pluralStringResource(R.plurals.camera_items_on_phone, stats.totalCount, stats.totalCount)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text(stringResource(R.string.camera_off), style = MaterialTheme.typography.displayMediumEmphasized)
                        Text(stringResource(R.string.camera_off_body), style = MaterialTheme.typography.titleMedium)
                    }
                }
                MorphingShapeBadge(
                    shape = if (backup.enabled && !backup.paused) MaterialShapes.Sunny else MaterialShapes.Cookie9Sided,
                    size = 84.dp,
                    container = colors.tertiary,
                    spinning = backup.enabled && state.backupRunning,
                ) {
                    Icon(Icons.Outlined.PhotoCamera, contentDescription = null, tint = colors.onTertiary, modifier = Modifier.size(34.dp))
                }
            }
            if (toggle.permissionDenied) {
                Text(stringResource(R.string.backup_permission_needed), style = MaterialTheme.typography.bodyMedium, color = colors.error)
            }
            if (backup.enabled && !toggle.hasPermission) {
                // Backup is on but the photo permission was taken away: without it every scan finds nothing.
                Surface(shape = MaterialTheme.shapes.large, color = colors.errorContainer, contentColor = colors.onErrorContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.backup_no_permission_notice), style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = toggle.requestAccess,
                            shapes = ButtonDefaults.shapes(),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.backup_allow_access)) }
                    }
                }
            }
            if (backup.enabled) {
                val albums = backup.albums
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (albums == null) stringResource(R.string.albums_all) else albums.take(3).joinToString(", ") { it.name } + if (albums.size > 3) " +${albums.size - 3}" else "",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOf(
                            stringResource(if (backup.unmeteredOnly) R.string.backup_wifi_only else R.string.backup_mobile_too),
                            if (backup.chargingOnly) stringResource(R.string.backup_charging_only) else null,
                            stringResource(if (backup.includeVideos) R.string.backup_videos_included else R.string.backup_photos_only),
                        ).filterNotNull().joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (stats.totalCount > 0) MediaSplit(stats)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.lastBackupAt?.let { stringResource(R.string.camera_last_backup, relative(it)) }.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { openPhotos(context) }) { Text(stringResource(R.string.camera_open_photos)) }
                }
            }
        }
    }
}

/** Opens the phone's default gallery app, so the photos being backed up are one tap away. */
private fun openPhotos(context: android.content.Context) {
    val intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        android.widget.Toast.makeText(context, R.string.camera_no_gallery, android.widget.Toast.LENGTH_SHORT).show()
    }
}
