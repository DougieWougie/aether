package com.aether.nextcloud.ui.dashboard

import androidx.compose.ui.semantics.semantics
import com.aether.nextcloud.R
import androidx.compose.ui.res.stringResource
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.DocumentsContract
import android.text.format.Formatter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aether.nextcloud.AetherConstants
import com.aether.nextcloud.data.local.entity.TransferDirection
import com.aether.nextcloud.data.local.entity.TransferEntity
import com.aether.nextcloud.data.local.entity.TransferState
import com.aether.nextcloud.provider.DocumentId
import com.aether.nextcloud.ui.components.MorphingShapeBadge
import com.aether.nextcloud.ui.components.groupedShape

/** Opens DocumentsUI inside [folder], so the original and its conflict copy sit side by side. */
internal fun openFolderInSystemFiles(context: android.content.Context, folder: DocumentId): Boolean {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(DocumentsContract.buildDocumentUri(AetherConstants.AUTHORITY, folder.toString()), DocumentsContract.Document.MIME_TYPE_DIR)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TransfersSection(active: List<TransferEntity>, recent: List<TransferEntity>) {
    if (active.isEmpty() && recent.isEmpty()) {
        EmptyState(MaterialShapes.Clover4Leaf, Icons.Outlined.Sync, stringResource(R.string.transfers_empty_title), stringResource(R.string.transfers_empty_body))
        return
    }
    if (active.isNotEmpty()) {
        SectionTitle(stringResource(R.string.transfers_in_progress))
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
internal fun TransferRow(transfer: TransferEntity, shape: androidx.compose.ui.graphics.Shape, highlighted: Boolean) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val failed = transfer.state == TransferState.FAILED
    val running = transfer.state == TransferState.RUNNING
    val upload = transfer.direction == TransferDirection.UPLOAD
    Surface(shape = shape, color = if (highlighted) colors.secondaryContainer else colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
                    contentDescription = stringResource(if (upload) R.string.transfer_upload else R.string.transfer_download),
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
                                stringResource(R.string.transfer_progress, Formatter.formatShortFileSize(context, transfer.bytesTransferred), Formatter.formatShortFileSize(context, transfer.totalBytes)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Text(if (transfer.state == TransferState.QUEUED) transfer.error ?: stringResource(R.string.transfer_waiting_network) else stringResource(R.string.transfer_starting), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    TransferState.COMPLETED -> Text(stringResource(R.string.transfer_done, relative(transfer.updatedAt)), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    TransferState.CONFLICT -> {
                        Text(stringResource(R.string.transfer_conflict_copy, relative(transfer.updatedAt)), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        val folder = DocumentId(transfer.accountName, DocumentId.normalizePath(transfer.remotePath)).parent
                        if (folder != null) {
                            TextButton(onClick = { openFolderInSystemFiles(context, folder) }, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.transfer_show_folder)) }
                        }
                    }
                    TransferState.FAILED -> Text(transfer.error ?: stringResource(R.string.transfer_failed), style = MaterialTheme.typography.bodyMedium, color = colors.error)
                }
            }
        }
    }
}
