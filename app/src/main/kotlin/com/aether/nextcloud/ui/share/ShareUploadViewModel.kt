package com.aether.nextcloud.ui.share

import android.content.Context
import android.content.Intent
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aether.nextcloud.AetherConstants
import com.aether.nextcloud.auth.AccountRepository
import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.core.AetherResult
import com.aether.nextcloud.data.DocumentRepository
import com.aether.nextcloud.data.MimeTypes
import com.aether.nextcloud.data.cache.DocumentDiskCache
import com.aether.nextcloud.data.prefs.SettingsRepository
import com.aether.nextcloud.provider.DocumentId
import com.aether.nextcloud.sync.UploadDocumentWorker
import com.aether.nextcloud.sync.UploadRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SharedItem(val uri: Uri?, val text: String?, val name: String, val mimeType: String?)

sealed interface SharePhase {
    data object Ready : SharePhase
    data class Preparing(val done: Int, val total: Int) : SharePhase
    data class Queued(val count: Int) : SharePhase
    data class Failed(val message: String) : SharePhase
}

data class ShareUiState(
    val loaded: Boolean = false,
    /** True once accounts, destination and shared items have been read. */
    val ready: Boolean = false,
    val accounts: List<AetherCredentials> = emptyList(),
    val account: AetherCredentials? = null,
    val destination: DocumentId? = null,
    val recentFolders: List<DocumentId> = emptyList(),
    val items: List<SharedItem> = emptyList(),
    val phase: SharePhase = SharePhase.Ready,
)

@HiltViewModel
class ShareUploadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountRepository: AccountRepository,
    private val settings: SettingsRepository,
    private val documents: DocumentRepository,
    private val diskCache: DocumentDiskCache,
) : ViewModel() {

    private val _state = MutableStateFlow(ShareUiState())
    val state: StateFlow<ShareUiState> = _state.asStateFlow()

    /** Reads what was shared and where it should go. Only the first call per ViewModel has an effect. */
    fun load(intent: Intent) {
        if (_state.value.loaded) return
        _state.update { it.copy(loaded = true) }
        viewModelScope.launch {
            val accounts = accountRepository.allCredentials()
            val recent = settings.recentFolders.first().mapNotNull(DocumentId::parseOrNull).filter { id -> accounts.any { it.accountName == id.accountName } }
            // A tapped Direct Share target names its folder; otherwise default to the last used one, else the first account's root.
            val requested = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)?.let(DocumentId::parseOrNull)?.takeIf { id -> accounts.any { it.accountName == id.accountName } }
            val destination = requested ?: recent.firstOrNull() ?: accounts.firstOrNull()?.let { DocumentId.root(it.accountName) }
            val items = withContext(Dispatchers.IO) { readItems(intent) }
            _state.update {
                it.copy(
                    accounts = accounts,
                    account = accounts.firstOrNull { account -> account.accountName == destination?.accountName },
                    destination = destination,
                    recentFolders = recent,
                    items = items,
                    ready = true,
                )
            }
        }
    }

    fun selectAccount(accountName: String) {
        val account = _state.value.accounts.firstOrNull { it.accountName == accountName } ?: return
        _state.update {
            val recentHere = it.recentFolders.firstOrNull { id -> id.accountName == accountName }
            it.copy(account = account, destination = recentHere ?: DocumentId.root(accountName))
        }
    }

    fun selectFolder(folder: DocumentId) {
        _state.update { it.copy(destination = folder, account = it.accounts.firstOrNull { a -> a.accountName == folder.accountName } ?: it.account) }
    }

    /** Result of the system folder picker; only Aether folders are accepted. */
    fun onFolderPicked(tree: Uri?) {
        if (tree == null || tree.authority != AetherConstants.AUTHORITY) {
            if (tree != null) _state.update { it.copy(phase = SharePhase.Failed("Pick a folder inside Aether")) }
            return
        }
        DocumentId.parseOrNull(DocumentsContract.getTreeDocumentId(tree))?.let(::selectFolder)
    }

    fun dismissError() = _state.update { if (it.phase is SharePhase.Failed) it.copy(phase = SharePhase.Ready) else it }

    fun upload() {
        val snapshot = _state.value
        val account = snapshot.account ?: return
        val destination = snapshot.destination ?: return
        if (snapshot.phase is SharePhase.Preparing || snapshot.items.isEmpty()) return
        viewModelScope.launch {
            try {
                snapshot.items.forEachIndexed { index, item ->
                    _state.update { it.copy(phase = SharePhase.Preparing(index, snapshot.items.size)) }
                    stageAndEnqueue(account, destination, item)
                }
                settings.rememberFolder(destination.toString())
                val recent = settings.recentFolders.first().mapNotNull(DocumentId::parseOrNull)
                DirectShareShortcuts.publish(context, recent)
                _state.update { it.copy(phase = SharePhase.Queued(snapshot.items.size)) }
            } catch (e: IOException) {
                _state.update { it.copy(phase = SharePhase.Failed(e.message ?: "Couldn't read the shared file")) }
            }
        }
    }

    /** Copies the content into app-private storage (the share's URI permission may not outlive this activity) and queues the upload. */
    private suspend fun stageAndEnqueue(account: AetherCredentials, destination: DocumentId, item: SharedItem) {
        val staging = withContext(Dispatchers.IO) {
            val file = diskCache.newStagingFile("${destination}/${item.name}")
            try {
                when {
                    item.uri != null -> context.contentResolver.openInputStream(item.uri)?.use { input -> file.outputStream().use(input::copyTo) }
                        ?: throw IOException("Cannot open ${item.name}")
                    item.text != null -> file.writeText(item.text)
                }
            } catch (e: Throwable) {
                file.delete()
                throw e
            }
            file
        }
        // Best effort: if the server can't be reached the original name is used and create-only upload protects existing files.
        val target = (documents.uniqueChild(account, destination, item.name) as? AetherResult.Success)?.value
            ?: destination.child(item.name)
        UploadDocumentWorker.enqueue(
            context,
            UploadRequest(account.accountName, target.path, staging, item.mimeType ?: MimeTypes.guess(item.name), ifMatchEtag = null),
        )
    }

    /**
     * `content://<userId>@<authority>` addresses the same provider as `content://<authority>`, so a plain authority comparison
     * is bypassable; user-id-prefixed authorities are rejected outright and the rest compared case-insensitively.
     */
    private fun isShareable(uri: Uri): Boolean {
        val authority = uri.authority ?: return false
        return uri.scheme == ContentResolver.SCHEME_CONTENT && '@' !in authority &&
            !authority.equals(AetherConstants.AUTHORITY, ignoreCase = true)
    }

    private fun readItems(intent: Intent): List<SharedItem> {
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }.ifEmpty {
            intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
        }
        // Only content:// URIs go through a provider that enforces the sender's grant. A file:// URI would be opened with
        // this app's own access, letting any sender make Aether read its private storage; Aether's own provider is pointless here.
        val shareable = uris.filter(::isShareable)
        if (shareable.isNotEmpty()) return shareable.map(::describe)
        if (uris.isNotEmpty()) return emptyList()

        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (text.isNullOrBlank()) return emptyList()
        val title = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }?.replace('/', '-') ?: "Shared text"
        return listOf(SharedItem(null, text, "$title.txt", "text/plain"))
    }

    private fun describe(uri: Uri): SharedItem {
        var name: String? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) name = cursor.getString(0)
            }
        }
        val safeName = (name ?: uri.lastPathSegment ?: "shared-file").substringAfterLast('/').ifBlank { "shared-file" }
        return SharedItem(uri, null, safeName, context.contentResolver.getType(uri))
    }
}
