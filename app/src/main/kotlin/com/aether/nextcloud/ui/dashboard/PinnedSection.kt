package com.aether.nextcloud.ui.dashboard

import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import android.text.format.Formatter
import com.aether.nextcloud.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.provider.DocumentsContract
import androidx.compose.material.icons.outlined.CreateNewFolder
import android.net.Uri
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Replay
import androidx.annotation.StringRes
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.derivedStateOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aether.nextcloud.data.local.entity.DocumentEntity
import com.aether.nextcloud.ui.components.MorphingShapeBadge
import com.aether.nextcloud.ui.components.groupedShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.aether.nextcloud.AetherConstants

/**
 * The dashboard's floating toolbar: "Files" is the FAB on every tab and the actions beside it belong to the tab showing.
 * The Camera and Transfers actions are placeholders that report through [onNotReady] until they are wired up.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DashboardToolbar(
    tab: DashboardTab,
    canSync: Boolean,
    onPinFiles: () -> Unit,
    onPinFolder: () -> Unit,
    onSyncNow: () -> Unit,
    onOpenFiles: () -> Unit,
    onNotReady: (String) -> Unit,
) {
    HorizontalFloatingToolbar(
        expanded = true,
        floatingActionButton = {
            ToolbarTooltip(stringResource(R.string.action_files)) {
                FloatingToolbarDefaults.VibrantFloatingActionButton(onClick = onOpenFiles) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = stringResource(R.string.action_files))
                }
            }
        },
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
    ) {
        when (tab) {
            DashboardTab.Pinned -> {
                ToolbarAction(R.string.pin_files, Icons.Outlined.PushPin, onPinFiles)
                ToolbarAction(R.string.pin_folder, Icons.Outlined.CreateNewFolder, onPinFolder)
                ToolbarAction(R.string.sync_now, Icons.Outlined.Sync, onSyncNow, enabled = canSync)
            }
            DashboardTab.Camera -> {
                ToolbarAction(R.string.backup_now, Icons.Outlined.CloudUpload, placeholder(R.string.backup_now, onNotReady))
                ToolbarAction(R.string.backup_pause, Icons.Outlined.Pause, placeholder(R.string.backup_pause, onNotReady))
            }
            DashboardTab.Transfers -> {
                ToolbarAction(R.string.transfers_retry_failed, Icons.Outlined.Replay, placeholder(R.string.transfers_retry_failed, onNotReady))
                ToolbarAction(R.string.transfers_clear_finished, Icons.Outlined.ClearAll, placeholder(R.string.transfers_clear_finished, onNotReady))
            }
        }
    }
}

/** A click handler that reports the action's name through [onNotReady]; the text is read in composition. */
@Composable
private fun placeholder(@StringRes label: Int, onNotReady: (String) -> Unit): () -> Unit {
    val text = stringResource(label)
    return { onNotReady(text) }
}

@Composable
private fun ToolbarAction(@StringRes label: Int, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true) {
    val text = stringResource(label)
    ToolbarTooltip(text) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = text) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolbarTooltip(text: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
        content = content,
    )
}

/**
 * `ACTION_OPEN_DOCUMENT` limited to one provider's root, returning every picked document. The picker's confirm button
 * reads "Pin" (`EXTRA_PROMPT`), the only place the system picker lets an app say what the selection is for.
 */
internal class PickAetherDocuments : ActivityResultContract<Uri?, List<Uri>>() {
    override fun createIntent(context: android.content.Context, input: Uri?): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .putExtra(DocumentsContract.EXTRA_PROMPT, context.getString(R.string.pick_prompt_pin))
            .apply { input?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) } }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != android.app.Activity.RESULT_OK || intent == null) return emptyList()
        val clip = intent.clipData
        return if (clip != null) (0 until clip.itemCount).map { clip.getItemAt(it).uri } else listOfNotNull(intent.data)
    }
}

/** [ActivityResultContracts.OpenDocumentTree] whose confirm button reads "Pin". */
internal class PickAetherFolder : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: android.content.Context, input: Uri?): Intent =
        super.createIntent(context, input).putExtra(DocumentsContract.EXTRA_PROMPT, context.getString(R.string.pick_prompt_pin))
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PinnedSection(
    pinned: List<DocumentEntity>,
    onUnpin: (DocumentEntity) -> Unit,
) {
    if (pinned.isEmpty()) {
        EmptyState(MaterialShapes.Cookie9Sided, Icons.Outlined.PushPin, stringResource(R.string.pinned_empty_title), stringResource(R.string.pinned_empty_body))
        return
    }
    SectionTitle(stringResource(R.string.pinned_section_title))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        pinned.forEachIndexed { index, doc ->
            val context = LocalContext.current
            Surface(
                onClick = { openPinned(context, doc) },
                shape = groupedShape(index, pinned.size),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val preview by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, doc.documentId, doc.etag) {
                        value = if (doc.isDirectory || !doc.hasPreview) null else withContext(Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.loadThumbnail(DocumentsContract.buildDocumentUri(AetherConstants.AUTHORITY, doc.documentId), Size(PREVIEW_PX, PREVIEW_PX), null).asImageBitmap()
                            }.getOrNull()
                        }
                    }
                    preview?.let {
                        Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)))
                    } ?: MorphingShapeBadge(
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
                        val size = Formatter.formatShortFileSize(LocalContext.current, doc.size)
                        val status = pinnedStatus(doc)
                        Text(
                            when (status) {
                                PinnedStatus.OnDevice -> stringResource(R.string.pinned_status_on_device, size)
                                PinnedStatus.UpdateWaiting -> stringResource(R.string.pinned_status_update_waiting)
                                PinnedStatus.Waiting -> stringResource(R.string.pinned_status_waiting)
                                PinnedStatus.FolderSynced -> stringResource(R.string.pinned_status_folder_synced, size)
                                PinnedStatus.FolderSyncing -> stringResource(R.string.pinned_status_folder_syncing)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status == PinnedStatus.OnDevice || status == PinnedStatus.FolderSynced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onUnpin(doc) }) { Text(stringResource(R.string.action_unpin)) }
                }
            }
        }
    }
}

private const val PREVIEW_PX = 192

/** Opens a pinned file in whichever app handles its type, or a pinned folder in DocumentsUI. */
private fun openPinned(context: android.content.Context, doc: DocumentEntity) {
    val uri = DocumentsContract.buildDocumentUri(AetherConstants.AUTHORITY, doc.documentId)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, if (doc.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else doc.mimeType)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(context, R.string.pinned_no_app_to_open, android.widget.Toast.LENGTH_SHORT).show()
    }
}
