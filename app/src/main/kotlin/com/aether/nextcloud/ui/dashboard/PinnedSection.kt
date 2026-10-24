package com.aether.nextcloud.ui.dashboard

import android.content.Intent
import android.provider.DocumentsContract
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Close
import android.net.Uri
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

/** Pin actions live in a FAB menu on the Pinned tab; "Files" stays reachable from it, as on the other tabs. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PinnedFabMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    canSync: Boolean,
    onPinFiles: () -> Unit,
    onPinFolder: () -> Unit,
    onSyncNow: () -> Unit,
    onOpenFiles: () -> Unit,
) {
    fun item(action: () -> Unit): () -> Unit = { onExpandedChange(false); action() }
    FloatingActionButtonMenu(
        expanded = expanded,
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = onExpandedChange,
                modifier = Modifier.semantics {
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                    contentDescription = "Pin menu"
                },
            ) {
                val icon by remember { derivedStateOf { if (checkedProgress > 0.5f) Icons.Outlined.Close else Icons.Outlined.PushPin } }
                Icon(rememberVectorPainter(icon), contentDescription = null, modifier = Modifier.animateIcon({ checkedProgress }))
            }
        },
    ) {
        FloatingActionButtonMenuItem(onClick = item(onPinFiles), text = { Text("Pin files") }, icon = { Icon(Icons.Outlined.PushPin, contentDescription = null) })
        FloatingActionButtonMenuItem(onClick = item(onPinFolder), text = { Text("Pin folder") }, icon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) })
        if (canSync) {
            FloatingActionButtonMenuItem(onClick = item(onSyncNow), text = { Text("Sync now") }, icon = { Icon(Icons.Outlined.Sync, contentDescription = null) })
        }
        FloatingActionButtonMenuItem(onClick = item(onOpenFiles), text = { Text("Files") }, icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) })
    }
}

/** `ACTION_OPEN_DOCUMENT` limited to one provider's root, returning every picked document. */
internal class PickAetherDocuments : ActivityResultContract<Uri?, List<Uri>>() {
    override fun createIntent(context: android.content.Context, input: Uri?): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .apply { input?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) } }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != android.app.Activity.RESULT_OK || intent == null) return emptyList()
        val clip = intent.clipData
        return if (clip != null) (0 until clip.itemCount).map { clip.getItemAt(it).uri } else listOfNotNull(intent.data)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PinnedSection(
    pinned: List<DocumentEntity>,
    onUnpin: (DocumentEntity) -> Unit,
) {
    if (pinned.isEmpty()) {
        EmptyState(MaterialShapes.Cookie9Sided, Icons.Outlined.PushPin, "Nothing pinned yet", "Pinned files and folders are kept on this device and stay up to date.")
        return
    }
    SectionTitle("Kept on this device")
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        pinned.forEachIndexed { index, doc ->
            Surface(shape = groupedShape(index, pinned.size), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MorphingShapeBadge(
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
                    }
                    TextButton(onClick = { onUnpin(doc) }) { Text("Unpin") }
                }
            }
        }
    }
}
