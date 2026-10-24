package com.aether.nextcloud.ui.backup

import com.aether.nextcloud.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aether.nextcloud.data.MediaAlbum
import com.aether.nextcloud.data.prefs.BackupAlbum
import com.aether.nextcloud.ui.components.HeroShape

/** Selected albums as chips; tapping anywhere on the card opens the picker. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AlbumsCard(albums: List<BackupAlbum>?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = HeroShape, color = colors.onTertiaryContainer.copy(alpha = 0.08f), contentColor = colors.onTertiaryContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.albums_title), style = MaterialTheme.typography.labelLargeEmphasized)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (albums == null) {
                    AssistChip(onClick = onClick, label = { Text(stringResource(R.string.albums_all)) }, leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) })
                } else {
                    albums.forEach { AssistChip(onClick = onClick, label = { Text(it.name) }) }
                }
            }
            Text(
                if (albums == null) stringResource(R.string.albums_all_summary) else stringResource(R.string.albums_some_summary),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Chooses which albums to back up. "All albums" stores no list so albums created later are included. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AlbumSheet(
    selected: List<BackupAlbum>?,
    available: List<MediaAlbum>?,
    uploads: Map<Long, Int>,
    onChange: (List<BackupAlbum>?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))) {
        Column(Modifier.navigationBarsPadding()) {
            Text(stringResource(R.string.albums_picker_title), style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            ListItem(
                supportingContent = { Text(stringResource(R.string.albums_all_hint)) },
                trailingContent = {
                    Switch(
                        checked = selected == null,
                        onCheckedChange = { all -> onChange(if (all) null else available.orEmpty().map { BackupAlbum(it.id, it.name) }) },
                    )
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) { Text(stringResource(R.string.albums_all)) }
            when {
                available == null -> Text(stringResource(R.string.albums_loading), modifier = Modifier.padding(24.dp))
                available.isEmpty() -> Text(stringResource(R.string.albums_none), modifier = Modifier.padding(24.dp))
                else -> LazyColumn {
                    items(available, key = { it.id }) { album ->
                        val checked = selected == null || selected.any { it.id == album.id }
                        val onlyOne = selected != null && selected.size == 1 && checked
                        val backedUp = uploads[album.id] ?: 0
                        ListItem(
                            supportingContent = {
                                Text(
                                    listOf(
                                        stringResource(R.string.album_on_phone, album.count),
                                        if (backedUp > 0) stringResource(R.string.album_backed_up, backedUp) else null,
                                        if (onlyOne) stringResource(R.string.album_keep_one) else null,
                                    ).filterNotNull().joinToString(" · "),
                                )
                            },
                            trailingContent = {
                                Checkbox(
                                    checked = checked,
                                    enabled = selected != null && !onlyOne,
                                    onCheckedChange = { on ->
                                        val current = selected.orEmpty()
                                        onChange(if (on) current + BackupAlbum(album.id, album.name) else current.filterNot { it.id == album.id })
                                    },
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        ) { Text(album.name) }
                    }
                }
            }
        }
    }
}
