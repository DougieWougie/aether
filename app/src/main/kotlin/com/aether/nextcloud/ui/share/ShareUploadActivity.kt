package com.aether.nextcloud.ui.share

import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.foundation.layout.heightIn
import com.aether.nextcloud.ui.components.MorphingShapeBadge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aether.nextcloud.AetherConstants
import com.aether.nextcloud.MainActivity
import com.aether.nextcloud.provider.DocumentId
import com.aether.nextcloud.ui.share.DirectShareShortcuts.label
import com.aether.nextcloud.ui.theme.AetherTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay

/** Share Sheet target (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`): picks a Nextcloud folder and queues the uploads (ADR-0007). */
@AndroidEntryPoint
class ShareUploadActivity : ComponentActivity() {
    private val viewModel: ShareUploadViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.load(intent)
        setContent {
            AetherTheme {
                ShareSheet(
                    viewModel = viewModel,
                    onFinished = { finish() },
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShareSheet(viewModel: ShareUploadViewModel, onFinished: () -> Unit, onOpenApp: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { viewModel.onFolderPicked(it) }

    (state.phase as? SharePhase.Queued)?.let { queued ->
        LaunchedEffect(queued) {
            delay(1_400)
            onFinished()
        }
    }

    ModalBottomSheet(onDismissRequest = onFinished, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                !state.ready -> Loading()
                state.accounts.isEmpty() -> {
                    Text("Sign in first", style = MaterialTheme.typography.titleLargeEmphasized)
                    Text("Open Aether and connect your Nextcloud account, then share again.")
                    Button(onClick = onOpenApp) { Text("Open Aether") }
                }
                state.items.isEmpty() -> {
                    Text("Nothing to upload", style = MaterialTheme.typography.titleLargeEmphasized)
                    Text("The sharing app didn't send any files.")
                    TextButton(onClick = onFinished) { Text("Close") }
                }
                else -> AnimatedContent(state.phase is SharePhase.Queued, label = "share-phase") { queued ->
                    if (queued) QueuedContent((state.phase as? SharePhase.Queued)?.count ?: state.items.size)
                    else Chooser(
                        state = state,
                        onSelectAccount = viewModel::selectAccount,
                        onSelectFolder = viewModel::selectFolder,
                        onBrowse = {
                            val account = state.account?.accountName ?: state.accounts.first().accountName
                            pickFolder.launch(DocumentsContract.buildDocumentUri(AetherConstants.AUTHORITY, DocumentId.root(account).toString()))
                        },
                        onUpload = viewModel::upload,
                        onCancel = onFinished,
                        onDismissError = viewModel::dismissError,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Loading() {
    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { LoadingIndicator() }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Chooser(
    state: ShareUiState,
    onSelectAccount: (String) -> Unit,
    onSelectFolder: (DocumentId) -> Unit,
    onBrowse: () -> Unit,
    onUpload: () -> Unit,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
) {
    val preparing = state.phase as? SharePhase.Preparing
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MorphingShapeBadge(
                shape = if (preparing != null) MaterialShapes.Sunny else MaterialShapes.Cookie9Sided,
                size = 64.dp,
                container = MaterialTheme.colorScheme.primary,
                spinning = preparing != null,
            ) {
                Icon(Icons.Outlined.CloudUpload, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
            }
            Column {
                Text("Save to Nextcloud", style = MaterialTheme.typography.headlineSmallEmphasized)
                Text(
                    if (state.items.size == 1) state.items.first().name else "${state.items.size} files",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        if (state.accounts.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.accounts.forEach { account ->
                    FilterChip(
                        selected = account.accountName == state.account?.accountName,
                        onClick = { onSelectAccount(account.accountName) },
                        label = { Text(account.accountName) },
                        enabled = preparing == null,
                    )
                }
            }
        }

        Text("Destination", style = MaterialTheme.typography.labelLargeEmphasized)
        val destination = state.destination
        val choices = (listOfNotNull(destination) + state.recentFolders.filter { it.accountName == state.account?.accountName })
            .distinct()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { folder ->
                FilterChip(
                    selected = folder == destination,
                    onClick = { onSelectFolder(folder) },
                    leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    label = { Text(folder.label()) },
                    enabled = preparing == null,
                )
            }
            AssistChip(
                onClick = onBrowse,
                leadingIcon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text("Choose folder…") },
                enabled = preparing == null,
            )
        }
        destination?.let {
            Text(
                if (it.path == "/") "Saving to the top level of your Nextcloud" else "Saving to ${it.path}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        (state.phase as? SharePhase.Failed)?.let { failed ->
            Text(failed.message, color = MaterialTheme.colorScheme.error)
            LaunchedEffect(failed) {
                delay(4_000)
                onDismissError()
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel, enabled = preparing == null) { Text("Cancel") }
            Spacer(Modifier.size(8.dp))
            Button(
                onClick = onUpload,
                enabled = preparing == null && destination != null,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                modifier = Modifier.heightIn(min = ButtonDefaults.MediumContainerHeight),
            ) {
                if (preparing != null) {
                    LoadingIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("${preparing.done + 1} of ${preparing.total}")
                } else {
                    Text("Upload")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QueuedContent(count: Int) {
    // Spring-in the check mark using the expressive motion scheme rather than a tween.
    val scale = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(Unit) { scale.animateTo(1f, spec) }
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MorphingShapeBadge(MaterialShapes.Cookie12Sided, size = 112.dp, container = MaterialTheme.colorScheme.primary, modifier = Modifier.scale(scale.value)) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(52.dp))
        }
        Text(if (count == 1) "Upload started" else "$count uploads started", style = MaterialTheme.typography.titleLargeEmphasized)
        Text("Progress shows in your notifications.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
