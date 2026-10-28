package com.aether.nextcloud.ui.backup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aether.nextcloud.AetherConstants
import com.aether.nextcloud.R
import com.aether.nextcloud.provider.DocumentId
import com.aether.nextcloud.ui.components.rememberNotificationPermissionRequest

private val mediaPermissions: Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun hasMediaPermission(context: Context) = mediaPermissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/** The camera backup on/off switch and the photo-permission flow behind it, shared by the tile and the settings sheet. */
class BackupToggle internal constructor(
    /** False when backup is on but the photo permission was taken away: without it every scan finds nothing. */
    val hasPermission: Boolean,
    /** The user refused the photo permission when turning backup on. */
    val permissionDenied: Boolean,
    val onToggle: (Boolean) -> Unit,
    val requestAccess: () -> Unit,
)

/** Turning on asks for photo access first; [onSetEnabled] only hears `true` once it is granted. */
@Composable
fun rememberBackupToggle(enabled: Boolean, onSetEnabled: (Boolean) -> Unit): BackupToggle {
    val context = LocalContext.current
    var hasPermission by remember(enabled) { mutableStateOf(hasMediaPermission(context)) }
    var denied by remember { mutableStateOf(false) }
    val askForNotifications = rememberNotificationPermissionRequest()
    val setEnabled by rememberUpdatedState(onSetEnabled)
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        // Partial photo access ("selected photos") still counts: backup then uploads what the user shared with Aether.
        val ok = granted.values.any { it }
        denied = !ok
        hasPermission = ok
        if (ok) {
            setEnabled(true)
            askForNotifications()
        }
    }
    return BackupToggle(
        hasPermission = hasPermission,
        permissionDenied = denied,
        onToggle = { on -> if (on) request.launch(mediaPermissions) else { denied = false; setEnabled(false) } },
        requestAccess = { request.launch(mediaPermissions) },
    )
}

/** The Camera tile's settings in a bottom sheet, so opening them never reflows the dashboard. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CameraSettingsSheet(onDismiss: () -> Unit, viewModel: CameraBackupViewModel = hiltViewModel()) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))) {
        CameraBackupSettings(
            viewModel = viewModel,
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding().imePadding(),
        )
    }
}

/** Camera backup settings: what to back up, where it goes, and when. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun CameraBackupSettings(modifier: Modifier = Modifier, viewModel: CameraBackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings
    var folder by remember(settings.remoteFolder) { mutableStateOf(settings.remoteFolder) }
    var showAlbums by remember { mutableStateOf(false) }
    val availableAlbums by viewModel.albums.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val toggle = rememberBackupToggle(settings.enabled, viewModel::setEnabled)

    if (showAlbums) {
        AlbumSheet(
            selected = settings.albums,
            available = availableAlbums,
            uploads = state.albumUploads,
            onChange = viewModel::setAlbums,
            onDismiss = { showAlbums = false },
        )
    }

    // An edit that never got its keyboard Done is still saved when the field loses focus or the sheet goes away.
    val saveFolder by rememberUpdatedState { if (folder != settings.remoteFolder) viewModel.setFolder(folder) }
    DisposableEffect(Unit) { onDispose { saveFolder() } }
    var folderFocused by remember { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.backup_settings_title), style = MaterialTheme.typography.titleLargeEmphasized)
        if (toggle.permissionDenied) {
            Text(stringResource(R.string.backup_permission_needed), style = MaterialTheme.typography.bodyMedium, color = colors.error)
        }

        SectionTitle(R.string.backup_section_what)
        AlbumChips(settings.albums, onClick = { viewModel.loadAlbums(); showAlbums = true })
        SwitchRow(stringResource(R.string.option_videos), Icons.Outlined.Videocam, settings.includeVideos, viewModel::setIncludeVideos, hint = stringResource(R.string.option_videos_hint))

        SectionTitle(R.string.backup_section_where)
        if (state.accounts.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_upload_to), style = MaterialTheme.typography.labelLarge)
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
        val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { viewModel.onFolderPicked(it) }
        val pickerAccount = settings.accountName ?: state.accounts.firstOrNull()?.accountName
        OutlinedTextField(
            value = folder,
            onValueChange = { folder = it },
            modifier = Modifier.fillMaxWidth().onFocusChanged {
                if (folderFocused && !it.isFocused) saveFolder()
                folderFocused = it.isFocused
            },
            label = { Text(stringResource(R.string.backup_destination)) },
            supportingText = { Text(stringResource(R.string.backup_destination_hint)) },
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

        SectionTitle(R.string.backup_section_when)
        SwitchRow(stringResource(R.string.option_wifi), Icons.Outlined.Wifi, settings.unmeteredOnly, viewModel::setUnmeteredOnly)
        SwitchRow(stringResource(R.string.option_charging), Icons.Outlined.BatteryChargingFull, settings.chargingOnly, viewModel::setChargingOnly)

        if (!settings.enabled) {
            // Setting up ends here; once it is on, the switch on the tile is the way to turn it off.
            Button(
                onClick = { toggle.onToggle(true) },
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(containerColor = colors.tertiary, contentColor = colors.onTertiary),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.backup_turn_on)) }
            Text(stringResource(R.string.backup_new_only), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SectionTitle(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.labelLargeEmphasized, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

/** A whole-row switch: the label, not just the thumb, flips it. */
@Composable
private fun SwitchRow(label: String, icon: ImageVector, checked: Boolean, onChange: (Boolean) -> Unit, hint: String? = null) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        supportingContent = hint?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    ) { Text(label) }
}
