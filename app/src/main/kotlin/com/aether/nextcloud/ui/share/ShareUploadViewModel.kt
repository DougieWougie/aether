package com.aether.nextcloud.ui.share

import com.aether.nextcloud.R
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
import com.aether.nextcloud.data.local.entity.TransferDirection
import com.aether.nextcloud.data.local.entity.TransferEntity
import com.aether.nextcloud.data.local.entity.TransferState
import com.aether.nextcloud.data.prefs.SettingsRepository
import com.aether.nextcloud.provider.DocumentId
import com.aether.nextcloud.sync.UploadDocumentWorker
import com.aether.nextcloud.sync.UploadRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One thing to upload. For a picked folder, [directory] is the item's parent folder relative to the folder's root
 * (`""` at the top, else `"a/b"`), and an [isFolder] item is an empty subfolder that is created but has no content.
 */
data class SharedItem(
    val uri: Uri?,
    val text: String?,
    val name: String,
    val mimeType: String?,
    val directory: String = "",
    val isFolder: Boolean = false,
) {
    val relativePath: String get() = if (directory.isEmpty()) name else "$directory/$name"
}

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
    /** Set when a whole folder was picked: its name, which becomes a new folder under [destination]. */
    val folderName: String? = null,
    val phase: SharePhase = SharePhase.Ready,
) {
    val fileCount: Int get() = items.count { !it.isFolder }
}

@HiltViewModel
class ShareUploadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountRepository: AccountRepository,
    private val settings: SettingsRepository,
    private val documents: DocumentRepository,
    private val diskCache: DocumentDiskCache,
    private val database: com.aether.nextcloud.data.local.AetherDatabase,
) : ViewModel() {

    private val _state = MutableStateFlow(ShareUiState())
    val state: StateFlow<ShareUiState> = _state.asStateFlow()
    private var uploadJob: Job? = null

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
            val tree = intent.data?.takeIf { intent.action == ACTION_UPLOAD_FOLDER && isShareable(it) }
            val items = withContext(Dispatchers.IO) { if (tree != null) readTree(tree) else readItems(intent) }
            val folderName = tree?.let { withContext(Dispatchers.IO) { treeName(it) } }
            _state.update {
                it.copy(
                    accounts = accounts,
                    account = accounts.firstOrNull { account -> account.accountName == destination?.accountName },
                    destination = destination,
                    recentFolders = recent,
                    items = items,
                    folderName = folderName,
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
            if (tree != null) _state.update { it.copy(phase = SharePhase.Failed(context.getString(R.string.share_pick_aether_folder))) }
            return
        }
        DocumentId.parseOrNull(DocumentsContract.getTreeDocumentId(tree))?.let(::selectFolder)
    }

    fun dismissError() = _state.update { if (it.phase is SharePhase.Failed) it.copy(phase = SharePhase.Ready) else it }

    /** Creates a new subfolder under the current [ShareUiState.destination] and selects it as the upload target. */
    fun createSubfolder(name: String) {
        val snapshot = _state.value
        val account = snapshot.account ?: return
        val parent = snapshot.destination ?: return
        val cleaned = name.trim()
        if (cleaned.isEmpty() || snapshot.phase is SharePhase.Preparing) return
        viewModelScope.launch {
            when (val result = documents.createDirectory(account, parent, cleaned)) {
                is AetherResult.Success -> {
                    val created = DocumentId.parse(result.value.documentId)
                    settings.rememberFolder(created.toString())
                    _state.update {
                        it.copy(
                            destination = created,
                            recentFolders = (listOf(created) + it.recentFolders.filter { f -> f != created }).take(SettingsRepository.MAX_RECENT_FOLDERS),
                            phase = if (it.phase is SharePhase.Failed) SharePhase.Ready else it.phase,
                        )
                    }
                }
                is AetherResult.Failure -> {
                    _state.update { it.copy(phase = SharePhase.Failed(context.getString(R.string.share_folder_failed, cleaned))) }
                }
            }
        }
    }

    /** Aborts in-flight staging and returns the sheet to [SharePhase.Ready]. */
    fun cancelPreparing() {
        uploadJob?.cancel()
        uploadJob = null
        _state.update { if (it.phase is SharePhase.Preparing) it.copy(phase = SharePhase.Ready) else it }
    }

    fun upload() {
        val snapshot = _state.value
        val account = snapshot.account ?: return
        val destination = snapshot.destination ?: return
        if (snapshot.phase is SharePhase.Preparing || snapshot.items.isEmpty()) return
        uploadJob = viewModelScope.launch {
            try {
                // A picked folder lands in a fresh, uniquely named folder, so nothing already on the server is merged into or overwritten.
                val base = snapshot.folderName?.let { name ->
                    when (val made = documents.createDirectory(account, destination, name)) {
                        is AetherResult.Success -> DocumentId.parse(made.value.documentId)
                        is AetherResult.Failure -> throw IOException(context.getString(R.string.share_folder_failed, name))
                    }
                } ?: destination
                val createdDirs = HashSet<DocumentId>().apply { add(base) }
                snapshot.items.forEachIndexed { index, item ->
                    coroutineContext.ensureActive()
                    _state.update { it.copy(phase = SharePhase.Preparing(index, snapshot.items.size)) }
                    if (item.isFolder) {
                        ensureSubfolders(account, base, item.relativePath, createdDirs)
                    } else {
                        if (snapshot.folderName != null && item.directory.isNotEmpty()) {
                            ensureSubfolders(account, base, item.directory, createdDirs)
                        }
                        stageAndEnqueue(account, base, item, nested = snapshot.folderName != null)
                    }
                }
                settings.rememberFolder(destination.toString())
                val recent = settings.recentFolders.first().mapNotNull(DocumentId::parseOrNull)
                DirectShareShortcuts.sync(context, settings.directShareEnabled.first(), recent)
                _state.update { it.copy(phase = SharePhase.Queued(snapshot.fileCount.coerceAtLeast(1))) }
            } catch (e: CancellationException) {
                _state.update { if (it.phase is SharePhase.Preparing) it.copy(phase = SharePhase.Ready) else it }
                throw e
            } catch (e: IOException) {
                _state.update { it.copy(phase = SharePhase.Failed(e.message ?: context.getString(R.string.share_read_failed))) }
            }
        }
    }

    /** `MKCOL`s each not-yet-created folder along [relativeDir] under [base] once per upload batch. */
    private suspend fun ensureSubfolders(account: AetherCredentials, base: DocumentId, relativeDir: String, created: MutableSet<DocumentId>) {
        var folder = base
        for (segment in relativeDir.split('/').filter { it.isNotEmpty() }) {
            folder = folder.child(segment)
            if (!created.add(folder)) continue
            if (documents.ensureDirectory(account, folder) is AetherResult.Failure) {
                created.remove(folder)
                throw IOException(context.getString(R.string.share_folder_failed, relativeDir))
            }
        }
    }

    /** Copies the content into app-private storage (the share's URI permission may not outlive this activity) and queues the upload. */
    private suspend fun stageAndEnqueue(account: AetherCredentials, destination: DocumentId, item: SharedItem, nested: Boolean) {
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
        // Inside a picked folder the layout is ours (a fresh top folder), so the relative path is used as is and missing
        // subfolders are created by the worker. Otherwise: best effort, if the server can't be reached the original name is
        // used and create-only upload protects existing files.
        val target = if (nested) item.relativePath.split('/').fold(destination) { dir, segment -> dir.child(segment) }
        else (documents.uniqueChild(account, destination, item.name) as? AetherResult.Success)?.value ?: destination.child(item.name)
        val mimeType = item.mimeType ?: MimeTypes.guess(item.name)
        // Row first, so the dashboard's Transfers list shows the file as queued before the worker starts; the worker adopts it.
        withContext(Dispatchers.IO) {
            database.transferDao().insert(
                TransferEntity(
                    accountName = account.accountName, remotePath = target.path, direction = TransferDirection.UPLOAD, state = TransferState.QUEUED,
                    totalBytes = staging.length(), createdAt = System.currentTimeMillis(), sourcePath = staging.absolutePath, mimeType = mimeType,
                ),
            )
        }
        UploadDocumentWorker.enqueue(context, UploadRequest(account.accountName, target.path, staging, mimeType, ifMatchEtag = null, createParents = false))
    }

    /**
     * `content://<userId>@<authority>` addresses the same provider as `content://<authority>`, so a plain authority comparison
     * is bypassable; user-id-prefixed authorities are rejected outright, and any provider owned by this app (including
     * `AetherDocumentsProvider` and our `captures` FileProvider) is rejected so external senders cannot read app-private files.
     */
    private fun isShareable(uri: Uri): Boolean {
        val authority = uri.authority ?: return false
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || '@' in authority) return false
        if (authority.equals(AetherConstants.AUTHORITY, ignoreCase = true)) return false
        if (authority.equals("${context.packageName}.captures", ignoreCase = true)) return false
        val provider = runCatching { context.packageManager.resolveContentProvider(authority, 0) }.getOrNull()
        if (provider?.packageName == context.packageName) return false
        return true
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
        return SharedItem(uri, null, safeName(name ?: uri.lastPathSegment, "shared-file"), context.contentResolver.getType(uri))
    }

    /** Display name of the picked folder, made safe to use as a remote folder name. */
    private fun treeName(tree: Uri): String {
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        var name: String? = null
        runCatching {
            context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) name = cursor.getString(0)
            }
        }
        return safeName(name, "Uploaded folder")
    }

    /** Every file under [tree] with its folder path, plus each empty subfolder; the picked folder itself is [ShareUiState.folderName]. */
    private fun readTree(tree: Uri): List<SharedItem> {
        val items = mutableListOf<SharedItem>()
        fun walk(documentId: String, directory: String, depth: Int) {
            val children = mutableListOf<Triple<String, String, String?>>()
            runCatching {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
                val columns = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                )
                context.contentResolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) children += Triple(cursor.getString(0), safeName(cursor.getString(1), "file"), cursor.getString(2))
                }
            }
            if (children.isEmpty() && directory.isNotEmpty()) {
                items += SharedItem(null, null, directory.substringAfterLast('/'), null, directory.substringBeforeLast('/', ""), isFolder = true)
            }
            for ((id, name, mime) in children) {
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (depth < MAX_TREE_DEPTH) walk(id, if (directory.isEmpty()) name else "$directory/$name", depth + 1)
                } else {
                    items += SharedItem(DocumentsContract.buildDocumentUriUsingTree(tree, id), null, name, mime, directory)
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), "", 0)
        return items
    }

    /** A single path segment without control characters or separators; [fallback] when nothing usable is left. */
    private fun safeName(raw: String?, fallback: String): String {
        val name = (raw ?: "").substringAfterLast('/').filter { it >= ' ' && it != '\u007F' }.trim()
        return if (name.isBlank() || name == "." || name == "..") fallback else name
    }

    companion object {
        /** Explicit-intent action for uploading a whole folder; `data` is the picked tree URI. Not offered to other apps. */
        const val ACTION_UPLOAD_FOLDER = "com.aether.nextcloud.action.UPLOAD_FOLDER"
        private const val MAX_TREE_DEPTH = 32
    }
}
