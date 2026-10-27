package com.aether.nextcloud.provider

import android.app.PendingIntent
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import com.aether.nextcloud.AetherConstants
import com.aether.nextcloud.MainActivity
import com.aether.nextcloud.R
import com.aether.nextcloud.auth.AccountRepository
import com.aether.nextcloud.auth.AuthIssues
import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.core.AetherError
import com.aether.nextcloud.core.AetherResult
import com.aether.nextcloud.core.describe
import com.aether.nextcloud.data.DocumentRepository
import com.aether.nextcloud.data.MimeTypes
import com.aether.nextcloud.data.QuotaRepository
import com.aether.nextcloud.data.cache.DocumentDiskCache
import com.aether.nextcloud.data.local.entity.DocumentEntity
import com.aether.nextcloud.dav.NextcloudOcsClient
import com.aether.nextcloud.dav.SearchQuery
import com.aether.nextcloud.di.ApplicationScope
import com.aether.nextcloud.sync.UploadDocumentWorker
import com.aether.nextcloud.sync.UploadRequest
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.coroutineContext

/**
 * Exposes every Aether account as a root in the system file picker / Files app (ADR-0001).
 *
 * Binder-thread rules: listing and metadata queries are answered from Room immediately; stale data triggers a background
 * PROPFIND and a `notifyChange`. Operations that must return a result (open, create, delete, rename, move) block the
 * caller's binder thread, honouring the client's [CancellationSignal].
 */
class AetherDocumentsProvider : DocumentsProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun accounts(): AccountRepository
        fun documents(): DocumentRepository
        fun quotas(): QuotaRepository
        fun ocs(): NextcloudOcsClient
        fun diskCache(): DocumentDiskCache
        fun authIssues(): AuthIssues
        fun database(): com.aether.nextcloud.data.local.AetherDatabase
        fun notifications(): com.aether.nextcloud.sync.TransferNotificationHelper
        fun accountCleaner(): com.aether.nextcloud.auth.AccountCleaner

        @ApplicationScope
        fun scope(): CoroutineScope
    }

    private val deps by lazy {
        EntryPointAccessors.fromApplication(context!!.applicationContext, Dependencies::class.java)
    }
    private val accounts get() = deps.accounts()
    private val documents get() = deps.documents()
    private val quotas get() = deps.quotas()
    private val scope get() = deps.scope()

    private val pipes = StreamingPipeHelper()
    private val listingsInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val listingErrors = ConcurrentHashMap<String, Pair<String, Long>>()
    private val quotaInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val searchesInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val searchErrors = ConcurrentHashMap<String, Pair<String, Long>>()

    /** The server's last answer per search, so a repeat query (DocumentsUI re-queries on every notify) is not re-sent. */
    private val searchAnswers: MutableMap<String, SearchAnswer> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, SearchAnswer>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, SearchAnswer>) = size > MAX_REMEMBERED_SEARCHES
        },
    )

    private class SearchAnswer(val ids: List<String>, val at: Long)

    override fun onCreate(): Boolean = true

    // region queries

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: DEFAULT_ROOT_PROJECTION
        val cursor = MatrixCursor(columns)
        val title = context!!.getString(R.string.root_title)
        val all = accounts.allCredentials()
        for (credentials in all) {
            val quota = quotas.cached(credentials.accountName)
            if (quotas.isStale(credentials.accountName)) refreshQuota(credentials)
            val capacity = quota?.totalBytes ?: quota?.freeBytes?.let { it + quota.usedBytes }
            val values = mapOf<String, Any?>(
                Root.COLUMN_ROOT_ID to credentials.accountName,
                Root.COLUMN_DOCUMENT_ID to DocumentId.root(credentials.accountName).toString(),
                Root.COLUMN_TITLE to title,
                Root.COLUMN_SUMMARY to rootSummary(credentials.accountName, all.size),
                Root.COLUMN_ICON to R.mipmap.ic_launcher,
                Root.COLUMN_FLAGS to (Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_SEARCH),
                Root.COLUMN_MIME_TYPES to "*/*",
                Root.COLUMN_AVAILABLE_BYTES to quota?.freeBytes,
                Root.COLUMN_CAPACITY_BYTES to capacity,
            )
            cursor.addRow(columns.map { values[it] })
        }
        cursor.setNotificationUri(context!!.contentResolver, DocumentsContract.buildRootsUri(AetherConstants.AUTHORITY))
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val id = parseId(documentId)
        val credentials = credentialsFor(id)
        val entity = documents.cached(id)
            ?: if (id.isRoot) rootPlaceholder(id, credentials.accountName)
            else blocking { documents.refreshDocument(credentials, id).orThrow(id.accountName) }
        return MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).also { it.addDocument(entity, projection ?: DEFAULT_DOCUMENT_PROJECTION) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val parent = parseId(parentDocumentId)
        val credentials = credentialsFor(parent)
        val columns = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val notifyUri = DocumentsContract.buildChildDocumentsUri(AetherConstants.AUTHORITY, parentDocumentId)
        val now = System.currentTimeMillis()

        val extras = Bundle()
        val recentError = listingErrors[parentDocumentId]?.takeIf { now - it.second < ERROR_BACKOFF_MS }
        if (recentError != null) {
            extras.putString(DocumentsContract.EXTRA_ERROR, recentError.first)
        } else {
            listingErrors.remove(parentDocumentId)
            val stale = documents.cached(parent)?.areChildrenStale(now) ?: true
            if (stale) refreshListing(parent, credentials, notifyUri)
            if (stale || parentDocumentId in listingsInFlight) extras.putBoolean(DocumentsContract.EXTRA_LOADING, true)
        }

        val cursor = ExtrasCursor(columns, extras)
        documents.cachedChildren(parent).forEach { cursor.addDocument(it, columns) }
        cursor.setNotificationUri(context!!.contentResolver, notifyUri)
        return cursor
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<out String>?): Cursor =
        search(rootId, SearchQuery(name = query), projection)

    /**
     * Android 11+: DocumentsUI sends the typed text and its file-type and date filters here, not in the URI. The system
     * file manager is the only caller (the provider requires `MANAGE_DOCUMENTS`), and it always fills the arguments in; a query
     * made with the URI alone reaches this with no arguments and is rejected.
     */
    @android.annotation.SuppressLint("Override")
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.R)
    override fun querySearchDocuments(rootId: String, projection: Array<out String>?, queryArgs: Bundle): Cursor =
        search(
            rootId,
            SearchQuery(
                name = queryArgs.getString(DocumentsContract.QUERY_ARG_DISPLAY_NAME).orEmpty(),
                mimeTypes = queryArgs.getStringArray(DocumentsContract.QUERY_ARG_MIME_TYPES)?.toList().orEmpty(),
                modifiedAfter = queryArgs.getLong(DocumentsContract.QUERY_ARG_LAST_MODIFIED_AFTER, -1L).takeIf { it >= 0 },
                sizeOver = queryArgs.getLong(DocumentsContract.QUERY_ARG_FILE_SIZE_OVER, -1L).takeIf { it >= 0 },
                excludeMedia = queryArgs.getBoolean(DocumentsContract.QUERY_ARG_EXCLUDE_MEDIA, false),
            ),
            projection,
        )

    /**
     * Same shape as [queryChildDocuments]: answer from Room now, ask the server in the background, and `notifyChange` the
     * search URI when its answer is stored. Once the server has answered, its hits replace the cached guess.
     */
    private fun search(rootId: String, query: SearchQuery, projection: Array<out String>?): Cursor {
        val credentials = accounts.credentials(rootId) ?: throw FileNotFoundException("Account $rootId is no longer signed in")
        val columns = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val notifyUri = DocumentsContract.buildSearchDocumentsUri(AetherConstants.AUTHORITY, rootId, query.name)
        val key = "$rootId|${query.key}"
        val now = System.currentTimeMillis()

        val answer = searchAnswers[key]
        val extras = Bundle()
        val recentError = searchErrors[key]?.takeIf { now - it.second < ERROR_BACKOFF_MS }
        if (recentError != null) {
            extras.putString(DocumentsContract.EXTRA_ERROR, recentError.first)
        } else {
            searchErrors.remove(key)
            val stale = answer == null || now - answer.at > DocumentEntity.CHILDREN_TTL_MS
            if (stale) refreshSearch(key, credentials, query, notifyUri)
            if (stale || key in searchesInFlight) extras.putBoolean(DocumentsContract.EXTRA_LOADING, true)
        }

        val rows = if (answer != null) documents.cachedAll(answer.ids) else documents.cachedMatches(rootId, query)
        val cursor = ExtrasCursor(columns, extras)
        rows.forEach { cursor.addDocument(it, columns) }
        cursor.setNotificationUri(context!!.contentResolver, notifyUri)
        return cursor
    }

    private fun refreshSearch(key: String, credentials: AetherCredentials, query: SearchQuery, notifyUri: Uri) {
        if (!searchesInFlight.add(key)) return
        scope.launch {
            try {
                when (val result = documents.search(credentials, query)) {
                    is AetherResult.Failure -> {
                        if (result.error == AetherError.Unauthorized) deps.authIssues().report(credentials.accountName)
                        searchErrors[key] = result.error.describe() to System.currentTimeMillis()
                    }
                    is AetherResult.Success -> {
                        searchErrors.remove(key)
                        searchAnswers[key] = SearchAnswer(result.value.map { it.documentId }, System.currentTimeMillis())
                    }
                }
            } finally {
                searchesInFlight.remove(key)
                context?.contentResolver?.notifyChange(notifyUri, null)
            }
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = DocumentId.parseOrNull(parentDocumentId) ?: return false
        val child = DocumentId.parseOrNull(documentId) ?: return false
        return parent.isAncestorOf(child)
    }

    /** Breadcrumb for DocumentsUI: ids from the account root (or [parentDocumentId]) down to [childDocumentId]. */
    override fun findDocumentPath(parentDocumentId: String?, childDocumentId: String): DocumentsContract.Path {
        val child = parseId(childDocumentId)
        val parent = parentDocumentId?.let(::parseId)
        if (parent != null && parent != child && !parent.isAncestorOf(child)) throw FileNotFoundException("$childDocumentId is not inside $parentDocumentId")
        val top = parent ?: DocumentId.root(child.accountName)
        val ids = generateSequence(child) { it.parent }.takeWhile { it == top || top.isAncestorOf(it) }.toList().reversed()
        return DocumentsContract.Path(if (parent == null) child.accountName else null, ids.map { it.toString() })
    }

    private fun refreshListing(parent: DocumentId, credentials: AetherCredentials, notifyUri: Uri) {
        val key = parent.toString()
        if (!listingsInFlight.add(key)) return
        scope.launch {
            try {
                when (val result = documents.refreshChildren(credentials, parent)) {
                    is AetherResult.Failure -> {
                        if (result.error == AetherError.Unauthorized) deps.authIssues().report(credentials.accountName)
                        listingErrors[key] = result.error.describe() to System.currentTimeMillis()
                    }
                    is AetherResult.Success -> listingErrors.remove(key)
                }
            } finally {
                listingsInFlight.remove(key)
                context?.contentResolver?.notifyChange(notifyUri, null)
            }
        }
    }

    private fun refreshQuota(credentials: AetherCredentials) {
        if (!quotaInFlight.add(credentials.accountName)) return
        scope.launch {
            try {
                if (quotas.refresh(credentials) is AetherResult.Success) {
                    context?.contentResolver?.notifyChange(DocumentsContract.buildRootsUri(AetherConstants.AUTHORITY), null)
                }
            } finally {
                quotaInFlight.remove(credentials.accountName)
            }
        }
    }

    // endregion

    // region content

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val id = parseId(documentId)
        val credentials = credentialsFor(id)
        return if (StreamingPipeHelper.isWriteMode(mode)) openForWrite(id, credentials, mode, signal)
        else pipes.openForRead(blocking(signal) { documents.ensureLocalFile(credentials, id).orThrow(id.accountName) })
    }

    private fun openForWrite(id: DocumentId, credentials: AetherCredentials, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val staging = deps.diskCache().newStagingFile(id.toString())
        val etag: String?
        val mimeType: String
        if (StreamingPipeHelper.needsExistingContent(mode)) {
            // Seed the staging file with the current bytes; this also refreshes the ETag we will send as If-Match.
            val local = blocking(signal) { documents.ensureLocalFile(credentials, id).orThrow(id.accountName) }
            local.copyTo(staging, overwrite = true)
        }
        val entity = documents.cached(id) ?: blocking(signal) { documents.refreshDocument(credentials, id).orThrow(id.accountName) }
        if ('W' !in entity.permissions.orEmpty()) {
            staging.delete()
            throw SecurityException("${entity.name} is read-only")
        }
        etag = entity.etag
        mimeType = entity.mimeType
        val seededAt = staging.lastModified()
        val seededSize = staging.length()
        val appContext = context!!.applicationContext

        return pipes.openForWrite(staging, mode) { error ->
            val unchanged = !StreamingPipeHelper.truncates(mode) && staging.lastModified() == seededAt && staging.length() == seededSize
            if (error != null || unchanged) {
                staging.delete()
            } else {
                UploadDocumentWorker.enqueue(appContext, UploadRequest(id.accountName, id.path, staging, mimeType, etag))
            }
        }
    }

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point, signal: CancellationSignal?): AssetFileDescriptor {
        val id = parseId(documentId)
        val credentials = credentialsFor(id)
        val entity = documents.cached(id) ?: throw FileNotFoundException("Unknown document $documentId")
        val fileId = entity.fileId?.takeIf { entity.hasPreview } ?: throw FileNotFoundException("No preview for $documentId")
        val size = maxOf(sizeHint.x, sizeHint.y).coerceIn(MIN_THUMBNAIL, MAX_THUMBNAIL)
        val diskCache = deps.diskCache()
        val thumbnail = diskCache.thumbnailFor(credentials.accountName, fileId, size, entity.etag)
        if (thumbnail.isFile) {
            diskCache.touch(thumbnail)
        } else {
            blocking(signal) { deps.ocs().downloadPreview(credentials, fileId, thumbnail, size).orThrow(credentials.accountName) }
            diskCache.removeOutdatedThumbnails(thumbnail)
            diskCache.trimThumbnails(protect = setOf(thumbnail))
        }
        return AssetFileDescriptor(pipes.openForRead(thumbnail), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    // endregion

    // region mutations

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = parseId(parentDocumentId)
        val credentials = credentialsFor(parent)
        val created = blocking {
            if (mimeType == Document.MIME_TYPE_DIR) documents.createDirectory(credentials, parent, displayName).orThrow(parent.accountName)
            else documents.createEmptyFile(credentials, parent, displayName, mimeType).orThrow(parent.accountName)
        }
        notifyChildren(parent)
        return created.documentId
    }

    override fun deleteDocument(documentId: String) {
        val id = parseId(documentId)
        if (id.isRoot) throw SecurityException("Cannot delete an account root")
        blocking { documents.delete(credentialsFor(id), id).orThrow(id.accountName) }
        id.parent?.let(::notifyChildren)
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        val id = parseId(documentId)
        if (id.isRoot) throw SecurityException("Cannot rename an account root")
        val renamed = blocking { documents.rename(credentialsFor(id), id, displayName).orThrow(id.accountName) }
        id.parent?.let(::notifyChildren)
        return renamed.toString()
    }

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        val source = parseId(sourceDocumentId)
        val target = parseId(targetParentDocumentId)
        if (source.accountName != target.accountName) throw FileNotFoundException("Cannot move between accounts")
        val moved = blocking { documents.move(credentialsFor(source), source, target).orThrow(source.accountName) }
        source.parent?.let(::notifyChildren)
        notifyChildren(target)
        return moved.toString()
    }

    private fun notifyChildren(parent: DocumentId) {
        context?.contentResolver?.notifyChange(
            DocumentsContract.buildChildDocumentsUri(AetherConstants.AUTHORITY, parent.toString()), null,
        )
    }

    // endregion

    // region helpers

    private fun parseId(documentId: String): DocumentId =
        DocumentId.parseOrNull(documentId) ?: throw FileNotFoundException("Malformed document id: $documentId")

    private fun credentialsFor(id: DocumentId): AetherCredentials =
        accounts.credentials(id.accountName) ?: throw FileNotFoundException("Account ${id.accountName} is no longer signed in")

    /** Runs [block] on the caller's binder thread, cancelling it if the client cancels [signal]. */
    private fun <T> blocking(signal: CancellationSignal? = null, block: suspend () -> T): T = try {
        runBlocking(Dispatchers.IO) {
            val job = coroutineContext.job
            signal?.setOnCancelListener { job.cancel() }
            block()
        }
    } catch (e: CancellationException) {
        throw OperationCanceledException()
    }

    /**
     * Binder can only marshal a few exception types. A missing file stays [FileNotFoundException]; failures that are not
     * "not found" (locked file, full quota, server errors) become [IllegalStateException] so callers do not read them
     * as a missing file. The message is the user-readable cause.
     */
    private fun <T> AetherResult<T>.orThrow(accountName: String): T = when (this) {
        is AetherResult.Success -> value
        is AetherResult.Failure -> when (error) {
            AetherError.Unauthorized -> {
                deps.authIssues().report(accountName)
                throw authenticationRequired()
            }
            AetherError.NotFound -> throw FileNotFoundException("Not found")
            // DocumentsUI only shows a generic failure for provider errors; the message still lands in logcat and the document service.
            else -> throw IllegalStateException(error.describe())
        }
    }

    private fun authenticationRequired(): android.app.AuthenticationRequiredException {
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return android.app.AuthenticationRequiredException(SecurityException("Sign in again"), pending)
    }

    private fun MatrixCursor.addDocument(entity: DocumentEntity, columns: Array<out String>) {
        val isRoot = entity.parentId == null
        val values = mapOf<String, Any?>(
            Document.COLUMN_DOCUMENT_ID to entity.documentId,
            Document.COLUMN_DISPLAY_NAME to if (isRoot) entity.accountName else entity.name,
            Document.COLUMN_MIME_TYPE to if (entity.isDirectory) MimeTypes.DIRECTORY else entity.mimeType,
            Document.COLUMN_SIZE to if (entity.isDirectory) null else entity.size,
            Document.COLUMN_LAST_MODIFIED to entity.lastModified.takeIf { it > 0 },
            Document.COLUMN_FLAGS to entity.safFlags(),
            Document.COLUMN_ICON to null,
        )
        addRow(columns.map { values[it] })
    }

    /** [MatrixCursor] drops extras by default; `EXTRA_LOADING` / `EXTRA_ERROR` must reach DocumentsUI. */
    private class ExtrasCursor(columns: Array<out String>, private val extras: Bundle) : MatrixCursor(columns) {
        override fun getExtras(): Bundle = extras
    }

    // endregion

    private companion object {
        val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_ICON,
            Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES, Root.COLUMN_CAPACITY_BYTES,
        )
        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_ICON,
        )
        const val ERROR_BACKOFF_MS = 10_000L
        const val MAX_REMEMBERED_SEARCHES = 16
        const val MIN_THUMBNAIL = 64
        const val MAX_THUMBNAIL = 512
    }
}

/** One account: the host alone (the user knows who they are). Several: the full account name tells them apart. */
internal fun rootSummary(accountName: String, accountCount: Int): String =
    if (accountCount > 1) accountName else accountName.substringAfterLast('@')
