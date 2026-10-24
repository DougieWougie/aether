package com.aether.nextcloud.ui.backup

import com.aether.nextcloud.provider.DocumentId
import com.aether.nextcloud.AetherConstants
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Check
import android.provider.DocumentsContract
import com.aether.nextcloud.ui.components.rememberNotificationPermissionRequest
import com.aether.nextcloud.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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

/** Camera backup settings, laid out to sit inside the expanded Camera tile (tertiary container). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun CameraBackupSettings(modifier: Modifier = Modifier, viewModel: CameraBackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings
    var folder by remember(settings.remoteFolder) { mutableStateOf(settings.remoteFolder) }
    var permissionDenied by remember { mutableStateOf(false) }
    var showAlbums by remember { mutableStateOf(false) }
    val availableAlbums by viewModel.albums.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme

    if (showAlbums) {
        AlbumSheet(
            selected = settings.albums,
            available = availableAlbums,
            uploads = state.albumUploads,
            onChange = viewModel::setAlbums,
            onDismiss = { showAlbums = false },
        )
    }

    val askForNotifications = rememberNotificationPermissionRequest()
    val requestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        // Partial photo access ("selected photos") still counts: backup then uploads what the user shared with Aether.
        val ok = granted.values.any { it }
        permissionDenied = !ok
        if (ok) {
            viewModel.setEnabled(true)
            askForNotifications()
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (permissionDenied) {
            Text(stringResource(R.string.backup_permission_needed), style = MaterialTheme.typography.bodyMedium, color = colors.error)
        }
        if (settings.enabled) {
            OutlinedButton(onClick = { viewModel.setEnabled(false) }, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.backup_turn_off)) }
        } else {
            Button(
                onClick = { requestPermissions.launch(mediaPermissions) },
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(containerColor = colors.tertiary, contentColor = colors.onTertiary),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.backup_turn_on)) }
            Text(stringResource(R.string.backup_new_only), style = MaterialTheme.typography.bodySmall)
        }

        if (state.accounts.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_upload_to), style = MaterialTheme.typography.labelLargeEmphasized)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.accounts.forEach { account ->
                        FilterChip(
                            selected = account.accountName == settings.accountName,
                            onClick = { viewModel.setAccount(account.accountName) },
                            label = { Text(account.accountName) },
                            leadingIcon = if (account.accountName == settings.accountName) {
                                { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                            } else null,
                        )
                    }
                }
            }
        }

        AlbumsCard(settings.albums, onClick = { viewModel.loadAlbums(); showAlbums = true })

        val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { viewModel.onFolderPicked(it) }
        val pickerAccount = settings.accountName ?: state.accounts.firstOrNull()?.accountName
        OutlinedTextField(
            value = folder,
            onValueChange = { folder = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.backup_destination)) },
            supportingText = { Text(stringResource(R.string.backup_destination_hint, folder)) },
            singleLine = true,
            trailingIcon = pickerAccount?.let { account ->
                {
                    IconButton(onClick = { pickFolder.launch(DocumentsContract.buildDocumentUri(AetherConstants.AUTHORITY, DocumentId.root(account).toString())) }) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = stringResource(R.string.backup_choose_folder))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { viewModel.setFolder(folder) }),
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.backup_only_when), style = MaterialTheme.typography.labelLargeEmphasized)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionChip(stringResource(R.string.option_wifi), Icons.Outlined.Wifi, settings.unmeteredOnly, viewModel::setUnmeteredOnly)
                OptionChip(stringResource(R.string.option_charging), Icons.Outlined.BatteryChargingFull, settings.chargingOnly, viewModel::setChargingOnly)
                OptionChip(stringResource(R.string.option_videos), Icons.Outlined.Videocam, settings.includeVideos, viewModel::setIncludeVideos)
            }
            Text(
                listOf(
                    stringResource(if (settings.unmeteredOnly) R.string.backup_wifi_only else R.string.backup_mobile_too),
                    if (settings.chargingOnly) stringResource(R.string.backup_charging_only) else null,
                    stringResource(if (settings.includeVideos) R.string.backup_videos_included else R.string.backup_photos_only),
                ).filterNotNull().joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun OptionChip(label: String, icon: ImageVector, checked: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = checked,
        onClick = { onChange(!checked) },
        label = { Text(label) },
        leadingIcon = { Icon(if (checked) Icons.Outlined.Check else icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
    )
}
