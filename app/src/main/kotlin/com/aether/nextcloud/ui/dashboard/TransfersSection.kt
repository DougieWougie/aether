package com.aether.nextcloud.ui.dashboard

import android.content.res.Resources
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import com.aether.nextcloud.sync.RetryOutcome
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
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.stateDescription
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
        TransferGroup(active, highlighted = true, key = "active") { ActiveSummary(active) }
    }
    if (recent.isNotEmpty()) {
        SectionTitle("Recent")
        TransferGroup(recent, highlighted = false, key = "recent") { RecentSummary(recent) }
    }
}

/** A summary card that expands, on tap, into the per-file rows it stands for. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TransferGroup(transfers: List<TransferEntity>, highlighted: Boolean, key: String, summary: @Composable () -> Unit) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
        Surface(
            onClick = { expanded = !expanded },
            shape = if (expanded) groupedShape(0, transfers.size + 1) else groupedShape(0, 1),
            color = if (highlighted) colors.secondaryContainer else colors.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { summary() }
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
            }
        }
        if (expanded) {
            transfers.forEachIndexed { index, transfer -> TransferRow(transfer, groupedShape(index + 1, transfers.size + 1), highlighted) }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActiveSummary(active: List<TransferEntity>) {
    val context = LocalContext.current
    Text(pluralStringResource(R.plurals.transfers_active, active.size, active.size), style = MaterialTheme.typography.titleMediumEmphasized)
    // Only transfers whose size is known count towards the bar, so one unsized upload doesn't stall it at 0.
    val sized = active.filter { it.totalBytes > 0 }
    if (sized.isNotEmpty()) {
        val done = sized.sumOf { it.bytesTransferred.coerceAtMost(it.totalBytes) }
        val total = sized.sumOf { it.totalBytes }
        LinearWavyProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        Text(stringResource(R.string.transfer_progress, Formatter.formatShortFileSize(context, done), Formatter.formatShortFileSize(context, total)), style = MaterialTheme.typography.bodySmall)
    }
    DirectionCounts(active)
    if (active.none { it.state == TransferState.RUNNING }) {
        Text(active.firstNotNullOfOrNull { it.error } ?: stringResource(R.string.transfer_waiting_network), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RecentSummary(recent: List<TransferEntity>) {
    val colors = MaterialTheme.colorScheme
    val finished = recent.filter { it.state == TransferState.COMPLETED }
    val failed = recent.count { it.state == TransferState.FAILED }
    val conflicts = recent.count { it.state == TransferState.CONFLICT }
    Text(
        listOfNotNull(
            finished.count { it.direction == TransferDirection.UPLOAD }.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.transfer_summary_uploaded, it, it) },
            finished.count { it.direction == TransferDirection.DOWNLOAD }.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.transfer_summary_downloaded, it, it) },
        ).joinToString(" · ").ifEmpty { stringResource(R.string.transfer_summary_nothing_finished) },
        style = MaterialTheme.typography.titleMediumEmphasized,
    )
    if (failed > 0) Text(pluralStringResource(R.plurals.transfer_summary_failed, failed, failed), style = MaterialTheme.typography.bodyMedium, color = colors.error)
    if (conflicts > 0) Text(pluralStringResource(R.plurals.transfer_summary_conflicts, conflicts, conflicts), style = MaterialTheme.typography.bodyMedium, color = colors.error)
    Text(stringResource(R.string.transfer_summary_last_activity, relative(recent.maxOf { it.updatedAt })), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
}

@Composable
private fun DirectionCounts(transfers: List<TransferEntity>) {
    val uploads = transfers.count { it.direction == TransferDirection.UPLOAD }
    val downloads = transfers.size - uploads
    Text(
        listOfNotNull(
            uploads.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.transfer_summary_uploads, it, it) },
            downloads.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.transfer_summary_downloads, it, it) },
        ).joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
    )
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
            // Pictures show themselves; everything else (and any picture that can't be read) keeps the direction badge.
            // Keyed by what changes the picture, not by progress, so it is not reloaded on every update.
            val thumbnail by produceState<ImageBitmap?>(null, transfer.id, transfer.state, transfer.sourcePath, transfer.remotePath) {
                value = loadTransferThumbnail(context, transfer, THUMBNAIL_PX)
            }
            val direction = stringResource(if (upload) R.string.transfer_upload else R.string.transfer_download)
            thumbnail?.let {
                Image(
                    it,
                    contentDescription = direction,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).testTag(TRANSFER_THUMBNAIL_TAG),
                )
            } ?: MorphingShapeBadge(
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
                    contentDescription = direction,
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

private const val THUMBNAIL_PX = 192
internal const val TRANSFER_THUMBNAIL_TAG = "transfer-thumbnail"

/** "Retrying 3 transfers", plus a second sentence when some could not be queued again. */
internal fun retryMessage(resources: Resources, outcome: RetryOutcome): String = listOfNotNull(
    resources.getQuantityString(R.plurals.transfers_retrying, outcome.retried, outcome.retried).takeIf { outcome.retried > 0 },
    resources.getQuantityString(R.plurals.transfers_retry_skipped, outcome.skipped, outcome.skipped).takeIf { outcome.skipped > 0 },
).joinToString(". ")
