package com.aether.nextcloud.ui.dashboard

import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.pluralStringResource
import com.aether.nextcloud.R
import androidx.compose.ui.res.stringResource
import android.text.format.Formatter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.animation.AnimatedVisibility
import com.aether.nextcloud.ui.backup.CameraBackupSettings
import com.aether.nextcloud.ui.backup.MediaSplit
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import android.content.ActivityNotFoundException
import android.content.Intent

@Composable
internal fun CameraChip(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.10f)) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

/** The tile with its own settings fold; the dashboard hoists that state instead, so Back up now can open it. */
@Composable
internal fun CameraSection(state: DashboardUiState) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    CameraSection(state, expanded) { expanded = it }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CameraSection(state: DashboardUiState, expanded: Boolean, onExpandedChange: (Boolean) -> Unit) {
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
    Surface(
        shape = HeroShapeAlt,
        color = colors.tertiaryContainer,
        contentColor = colors.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = if (backup.enabled) Modifier.clip(MaterialTheme.shapes.large).clickable { openPhotos(context) } else Modifier,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(if (!backup.enabled) R.string.camera_title else if (backup.paused) R.string.camera_paused else R.string.camera_backing_up), style = MaterialTheme.typography.labelLargeEmphasized)
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
            // The expanded settings show the album choice in full, so the summary chips would only repeat it.
            if (backup.enabled && !expanded) {
                val albums = backup.albums
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (albums == null) {
                        CameraChip(stringResource(R.string.albums_all))
                    } else {
                        albums.take(3).forEach { CameraChip(it.name) }
                        if (albums.size > 3) CameraChip("+${albums.size - 3}")
                    }
                }
            }
            if (backup.enabled && stats.totalCount > 0) {
                MediaSplit(stats)
            }
            state.lastBackupAt?.takeIf { backup.enabled }?.let { Text(stringResource(R.string.camera_last_backup, relative(it)), style = MaterialTheme.typography.bodyMedium) }
            AnimatedVisibility(expanded) { CameraBackupSettings() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = { onExpandedChange(!expanded) },
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.tertiary, contentColor = colors.onTertiary),
                ) { Text(if (expanded && !backup.enabled) stringResource(R.string.action_cancel) else if (expanded) stringResource(R.string.action_done) else if (backup.enabled) stringResource(R.string.action_settings) else stringResource(R.string.action_set_up)) }
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
