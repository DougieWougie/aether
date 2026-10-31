package com.aether.nextcloud.ui.dashboard

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aether.nextcloud.R
import com.aether.nextcloud.ui.backup.CameraSettingsSheet
import com.aether.nextcloud.ui.backup.rememberBackupToggle

/** The tile with its own settings sheet; the dashboard hoists that state instead, so Back up now can open it. */
@Composable
internal fun CameraSection(state: DashboardUiState, onBackupEnabledChange: (Boolean) -> Unit = {}, onShowTransfers: () -> Unit = {}) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    CameraSection(state, expanded, { expanded = it }, onBackupEnabledChange, onShowTransfers)
}

/**
 * A compact status row: the title, one line saying what backup is doing right now, and the switch. Progress and problems
 * (a missing photo permission, items that failed) appear under it only when there is something to show or do; the numbers
 * and the photos/videos split live in the settings sheet.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CameraSection(
    state: DashboardUiState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onBackupEnabledChange: (Boolean) -> Unit = {},
    onShowTransfers: () -> Unit = {},
) {
    // Turning backup on is the end of setting it up, so fold the settings away and let the tile show the result.
    var wasEnabled by remember { mutableStateOf(state.backup.enabled) }
    LaunchedEffect(state.backup.enabled) {
        if (state.backup.enabled && !wasEnabled) onExpandedChange(false)
        wasEnabled = state.backup.enabled
    }
    val backup = state.backup
    val colors = MaterialTheme.colorScheme
    val toggle = rememberBackupToggle(backup.enabled, onBackupEnabledChange)
    val title = stringResource(R.string.camera_title)
    if (expanded) CameraSettingsSheet(onDismiss = { onExpandedChange(false) })
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.tertiaryContainer,
        contentColor = colors.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = colors.tertiary, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.PhotoCamera, contentDescription = null, tint = colors.onTertiary, modifier = Modifier.size(22.dp))
                    }
                }
                Column(Modifier.weight(1f).clickable { onExpandedChange(true) }) {
                    Text(title, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(statusLine(state), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
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
            val progress = state.backupProgress
            if (backup.enabled && !backup.paused && state.backupRunning) {
                // Determinate once the run has counted what to upload; until then it just shows that something is happening.
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.done / progress.total.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.tertiary,
                        trackColor = colors.onTertiaryContainer.copy(alpha = 0.12f),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.tertiary, trackColor = colors.onTertiaryContainer.copy(alpha = 0.12f))
                }
            }
            if (toggle.permissionDenied) {
                Text(stringResource(R.string.backup_permission_needed), style = MaterialTheme.typography.bodyMedium, color = colors.error)
            }
            if (backup.enabled && !toggle.hasPermission) {
                // Backup is on but the photo permission was taken away: without it every scan finds nothing.
                Notice(stringResource(R.string.backup_no_permission_notice), stringResource(R.string.backup_allow_access), toggle.requestAccess)
            }
            if (backup.enabled && state.backupFailedCount > 0) {
                Notice(
                    pluralStringResource(R.plurals.camera_failed, state.backupFailedCount, state.backupFailedCount),
                    stringResource(R.string.camera_failed_view),
                    onShowTransfers,
                )
            }
        }
    }
}

/** The one line that says what backup is doing: off, paused, uploading (with the count) or how long ago it last finished. */
@Composable
private fun statusLine(state: DashboardUiState): String {
    val backup = state.backup
    val progress = state.backupProgress
    return when {
        !backup.enabled -> stringResource(R.string.camera_off_body)
        backup.paused -> stringResource(R.string.camera_paused)
        state.backupRunning && progress != null -> stringResource(R.string.camera_progress, progress.done, progress.total)
        state.backupRunning -> stringResource(R.string.camera_backing_up)
        state.lastBackupAt != null -> stringResource(R.string.camera_up_to_date, relative(state.lastBackupAt))
        else -> stringResource(R.string.camera_waiting)
    }
}

/** A problem with its one action, in the error colours. */
@Composable
private fun Notice(text: String, action: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = colors.errorContainer, contentColor = colors.onErrorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = colors.error)) { Text(action) }
        }
    }
}
