package com.aether.nextcloud.data

import androidx.room.withTransaction
import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.core.AetherError
import com.aether.nextcloud.core.AetherResult
import com.aether.nextcloud.core.getOrNull
import com.aether.nextcloud.data.cache.DocumentDiskCache
import com.aether.nextcloud.data.local.AetherDatabase
import com.aether.nextcloud.data.local.entity.DocumentEntity
import com.aether.nextcloud.dav.SearchQuery
import com.aether.nextcloud.dav.WebDavClientFactory
import com.aether.nextcloud.dav.ProgressListener
import com.aether.nextcloud.provider.DocumentId
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single entry point for document metadata and bytes, shared by the SAF provider, the workers and the UI.
 * Reads come from Room first; every method that touches the network is a suspend function and runs on IO.
 */
@Singleton
class DocumentRepository @Inject constructor(
    private val database: AetherDatabase,
    private val davFactory: WebDavClientFactory,
    private val diskCache: DocumentDiskCache,
) {
    private val dao = database.documentDao()

    fun cached(id: DocumentId): DocumentEntity? = dao.get(id.toString())

    fun cachedChildren(id: DocumentId): List<DocumentEntity> = dao.children(id.toString())

    /**
     * Cached rows that match [query], for answering a search at once while [search] runs. A name filter is applied in SQL;
     * the rest of the query is applied to what it returns.
     */
    fun cachedMatches(accountName: String, query: SearchQuery): List<DocumentEntity> =
        dao.searchByName(accountName, "%" + escapeLike(query.name.trim()) + "%", CACHED_SEARCH_LIMIT).filter { it.matches(query) }

    /** The cached rows for [ids], in that order; ids that have since left the cache are skipped. */
    fun cachedAll(ids: List<String>): List<DocumentEntity> {
        val byId = dao.getAll(ids).associateBy { it.documentId }
        return ids.mapNotNull(byId::get)
    }

    /**
     * Server-side search of the whole account. Hits are stored with the same local state a listing would keep (pin flag,
     * local copy), but no folder is marked as listed, so a later listing still diffs against the server. Returns the hits
     * in the server's order.
     */
    suspend fun search(credentials: AetherCredentials, query: SearchQuery): AetherResult<List<DocumentEntity>> {
        val resources = when (val result = davFactory.forAccount(credentials).search(query)) {
            is AetherResult.Success -> result.value
            is AetherResult.Failure -> return result
        }
        return withContext(Dispatchers.IO) {
            database.withTransaction {
                val hits = resources.map { it.toEntity(credentials.accountName, credentials.loginName) }
                    .filter { it.parentId != null && it.matches(query) }
                val existing = dao.getAll(hits.map { it.documentId }).associateBy { it.documentId }
                val merged = hits.map { it.withLocalStateOf(existing[it.documentId]) }
                dao.upsertAll(merged)
                AetherResult.Success(merged)
            }
        }
    }

    private fun DocumentEntity.matches(query: SearchQuery) = query.matches(name, mimeType, isDirectory, size, lastModified)

    /** `PROPFIND Depth: 1` on a folder; replaces the cached child list and stamps the folder's `childrenSyncedAt`. */
    suspend fun refreshChildren(credentials: AetherCredentials, dir: DocumentId): AetherResult<DocumentEntity> {
        val url = credentials.fileUrl(if (dir.isRoot) "/" else dir.path + "/")
        val resources = when (val result = davFactory.forAccount(credentials).propfind(url, depth = 1)) {
            is AetherResult.Success -> result.value
            is AetherResult.Failure -> return result.also { if (it.error == AetherError.NotFound) forget(dir) }
        }
        val now = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            database.withTransaction {
                val existing = dao.children(dir.toString()).associateBy { it.documentId }
                val parsed = resources.map { it.toEntity(credentials.accountName, credentials.loginName) }
                val self = parsed.firstOrNull { it.documentId == dir.toString() }
                    ?: return@withTransaction AetherResult.Failure(AetherError.Malformed("Listing did not include the folder itself"))
                val children = parsed.filter { it.documentId != self.documentId && it.parentId == self.documentId }
                    .map { it.withLocalStateOf(existing[it.documentId]) }

                val keep = children.mapTo(HashSet()) { it.documentId }
                existing.values.filter { it.documentId !in keep }.forEach { removeLocal(it) }
                dao.upsertAll(children)
                val folder = self.withLocalStateOf(dao.get(self.documentId)).copy(childrenSyncedAt = now)
                dao.upsert(folder)
                AetherResult.Success(folder)
            }
        }
    }

    /** `PROPFIND Depth: 0`: refreshes one document's metadata. Drops it from the cache when the server says it is gone. */
    suspend fun refreshDocument(credentials: AetherCredentials, id: DocumentId): AetherResult<DocumentEntity> {
        val url = credentials.fileUrl(id.path)
        val resources = when (val result = davFactory.forAccount(credentials).propfind(url, depth = 0)) {
            is AetherResult.Success -> result.value
            is AetherResult.Failure -> return result.also { if (it.error == AetherError.NotFound) forget(id) }
        }
        val resource = resources.firstOrNull() ?: return AetherResult.Failure(AetherError.Malformed("Empty multistatus"))
        return withContext(Dispatchers.IO) {
            val fresh = resource.toEntity(credentials.accountName, credentials.loginName)
                .let { it.copy(documentId = id.toString(), path = id.path, name = id.name, parentId = id.parent?.toString()) }
                .withLocalStateOf(dao.get(id.toString()))
            dao.upsert(fresh)
            AetherResult.Success(fresh)
        }
    }

    /**
     * Returns a local file with the document's current bytes: the pinned/cached copy when its ETag matches the server's,
     * otherwise a fresh download. Offline, falls back to the last copy we have.
     */
    suspend fun ensureLocalFile(
        credentials: AetherCredentials,
        id: DocumentId,
        onProgress: ProgressListener = { _, _ -> },
    ): AetherResult<File> {
        val known = withContext(Dispatchers.IO) { dao.get(id.toString()) }
        val entity = when (val refreshed = refreshDocument(credentials, id)) {
            is AetherResult.Success -> refreshed.value
            is AetherResult.Failure -> {
                val offlineCopy = known?.localCachePath?.let(::File)?.takeIf(File::isFile)
                return if (refreshed.error is AetherError.Network && offlineCopy != null) AetherResult.Success(offlineCopy) else refreshed
            }
        }
        if (entity.isDirectory) return AetherResult.Failure(AetherError.Malformed("${id.path} is a folder"))

        diskCache.validCopy(entity)?.let { copy ->
            diskCache.touch(copy)
            return AetherResult.Success(copy)
        }

        val target = if (entity.isPinned) diskCache.pinnedFileFor(entity.documentId) else diskCache.cacheFileFor(entity.documentId)
        return when (val download = davFactory.forAccount(credentials).downloadFile(credentials.fileUrl(id.path), target, onProgress)) {
            is AetherResult.Failure -> download
            is AetherResult.Success -> withContext(Dispatchers.IO) {
                dao.upsert(
                    entity.copy(
                        localCachePath = target.path,
                        cachedEtag = download.value ?: entity.etag,
                        lastAccessed = System.currentTimeMillis(),
                    ),
                )
                if (!entity.isPinned) diskCache.trimToSize(protect = setOf(target))
                AetherResult.Success(target)
            }
        }
    }

    /** True when [entity]'s bytes are in persistent pinned storage and match the server's current ETag. */
    fun hasCurrentPinnedCopy(entity: DocumentEntity): Boolean =
        diskCache.validCopy(entity)?.let(diskCache::isPinnedFile) ?: false

    /** Makes sure a pinned document's bytes live in persistent storage (migrating an existing cache copy). */
    suspend fun ensurePinnedCopy(credentials: AetherCredentials, id: DocumentId, onProgress: ProgressListener = { _, _ -> }): AetherResult<File> {
        val file = when (val local = ensureLocalFile(credentials, id, onProgress)) {
            is AetherResult.Success -> local.value
            is AetherResult.Failure -> return local
        }
        val pinned = diskCache.pinnedFileFor(id.toString())
        if (file == pinned) return AetherResult.Success(file)
        return withContext(Dispatchers.IO) {
            file.copyTo(pinned, overwrite = true)
            file.delete()
            dao.get(id.toString())?.let { dao.upsert(it.copy(localCachePath = pinned.path)) }
            AetherResult.Success(pinned)
        }
    }

    /** Adopts [file] (just uploaded; [etag] is the server's ETag for those bytes) as the local copy so reads skip a download. */
    suspend fun adoptUploadedFile(credentials: AetherCredentials, id: DocumentId, file: File, etag: String?) {
        val refreshed = refreshDocument(credentials, id).getOrNull()
        if (refreshed == null || etag == null || refreshed.etag != etag) return
        withContext(Dispatchers.IO) {
            val target = if (refreshed.isPinned) diskCache.pinnedFileFor(id.toString()) else diskCache.cacheFileFor(id.toString())
            file.copyTo(target, overwrite = true)
            dao.upsert(refreshed.copy(localCachePath = target.path, cachedEtag = etag, lastAccessed = System.currentTimeMillis()))
            if (!refreshed.isPinned) diskCache.trimToSize(protect = setOf(target))
        }
    }

    /** Creates a folder, picking a unique name if [name] is taken. */
    suspend fun createDirectory(credentials: AetherCredentials, parent: DocumentId, name: String): AetherResult<DocumentEntity> {
        val dav = davFactory.forAccount(credentials)
        val id = when (val free = uniqueChild(credentials, parent, name)) {
            is AetherResult.Success -> free.value
            is AetherResult.Failure -> return free
        }
        return when (val created = dav.mkcol(credentials.fileUrl(id.path + "/"))) {
            is AetherResult.Failure -> created
            is AetherResult.Success -> refreshDocument(credentials, id).also { invalidate(parent) }
        }
    }

    /** Creates an empty file so SAF has a document id straight away; the caller then writes content via `openDocument`. */
    suspend fun createEmptyFile(credentials: AetherCredentials, parent: DocumentId, name: String, mimeType: String): AetherResult<DocumentEntity> {
        val id = when (val free = uniqueChild(credentials, parent, name)) {
            is AetherResult.Success -> free.value
            is AetherResult.Failure -> return free
        }
        val empty = withContext(Dispatchers.IO) { File.createTempFile("empty", ".tmp") }
        try {
            return when (val put = davFactory.forAccount(credentials).uploadFile(credentials.fileUrl(id.path), empty, mimeType)) {
                is AetherResult.Failure -> put
                is AetherResult.Success -> refreshDocument(credentials, id).also { invalidate(parent) }
            }
        } finally {
            empty.delete()
        }
    }

    suspend fun delete(credentials: AetherCredentials, id: DocumentId): AetherResult<Unit> {
        val url = credentials.fileUrl(if (id.isRoot) "/" else id.path)
        return when (val result = davFactory.forAccount(credentials).delete(url)) {
            is AetherResult.Failure -> if (result.error == AetherError.NotFound) AetherResult.Success(Unit).also { forget(id) } else result
            is AetherResult.Success -> AetherResult.Success(Unit).also {
                forget(id)
                id.parent?.let { invalidate(it) }
            }
        }
    }

    suspend fun rename(credentials: AetherCredentials, id: DocumentId, newName: String): AetherResult<DocumentId> {
        val parent = id.parent ?: return AetherResult.Failure(AetherError.Malformed("Cannot rename a root"))
        return relocate(credentials, id, runCatching { parent.child(newName) }.getOrElse {
            return AetherResult.Failure(AetherError.Malformed("Invalid name"))
        })
    }

    suspend fun move(credentials: AetherCredentials, id: DocumentId, newParent: DocumentId): AetherResult<DocumentId> {
        if (id == newParent || id.isAncestorOf(newParent)) return AetherResult.Failure(AetherError.Malformed("Cannot move a folder into itself"))
        return relocate(credentials, id, newParent.child(id.name))
    }

    private suspend fun relocate(credentials: AetherCredentials, from: DocumentId, to: DocumentId): AetherResult<DocumentId> {
        if (from == to) return AetherResult.Success(to)
        val isDir = withContext(Dispatchers.IO) { dao.get(from.toString())?.isDirectory } ?: false
        val suffix = if (isDir) "/" else ""
        return when (val result = davFactory.forAccount(credentials).move(credentials.fileUrl(from.path + suffix), credentials.fileUrl(to.path + suffix), overwrite = false)) {
            is AetherResult.Failure -> result
            is AetherResult.Success -> {
                moveSubtreeRows(from, to)
                from.parent?.let { invalidate(it) }
                to.parent?.let { invalidate(it) }
                AetherResult.Success(to)
            }
        }
    }

    /** Re-keys cached rows after a server-side move, keeping pin flags and local copies. */
    private suspend fun moveSubtreeRows(from: DocumentId, to: DocumentId) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val rows = dao.subtree(from.accountName, from.path, subtreeLikePattern(from.path))
            dao.deleteAll(rows.map { it.documentId })
            dao.upsertAll(
                rows.map { row ->
                    val newId = DocumentId(to.accountName, to.path + row.path.removePrefix(from.path))
                    row.copy(
                        documentId = newId.toString(),
                        path = newId.path,
                        parentId = newId.parent?.toString(),
                        name = newId.name,
                    )
                },
            )
        }
    }

    suspend fun setPinned(id: DocumentId, pinned: Boolean) = withContext(Dispatchers.IO) {
        if (pinned) {
            dao.setPinned(id.toString(), true)
            return@withContext
        }
        dao.subtree(id.accountName, id.path, subtreeLikePattern(id.path)).forEach { row ->
            dao.setPinned(row.documentId, false)
            // Pinned bytes are no longer protected, so free the space; the on-demand cache refills as needed.
            val local = row.localCachePath?.let(::File)
            if (local != null && diskCache.isPinnedFile(local)) {
                diskCache.delete(local)
                dao.clearLocalCopy(row.documentId)
            }
        }
    }

    /** Pinned folders propagate to whatever they contain, including items that appear later. */
    suspend fun pinChildren(dir: DocumentId) = withContext(Dispatchers.IO) { dao.pinChildren(dir.toString()) }

    /** For folders, [DocumentEntity.cachedEtag] records the ETag at which the whole subtree was last fully synced. */
    suspend fun markSubtreeSynced(dir: DocumentId, etag: String?) = withContext(Dispatchers.IO) { dao.setCachedEtag(dir.toString(), etag) }

    suspend fun pinnedDocuments(): List<DocumentEntity> = withContext(Dispatchers.IO) { dao.pinned() }

    /** Removes [id] and everything below it from the cache, deleting their local bytes. */
    suspend fun forget(id: DocumentId) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val rows = dao.subtree(id.accountName, id.path, subtreeLikePattern(id.path))
            rows.forEach { diskCache.delete(it.localCachePath?.let(::File)) }
            dao.deleteAll(rows.map { it.documentId })
        }
    }

    suspend fun forgetAccount(accountName: String) = withContext(Dispatchers.IO) {
        dao.withLocalCopy(accountName).forEach { diskCache.delete(it.localCachePath?.let(::File)) }
        dao.deleteAccount(accountName)
    }

    private suspend fun invalidate(id: DocumentId) = withContext(Dispatchers.IO) { dao.invalidateChildren(id.toString()) }

    private fun removeLocal(entity: DocumentEntity) {
        val subtree = dao.subtree(entity.accountName, entity.path, subtreeLikePattern(entity.path))
        subtree.forEach { diskCache.delete(it.localCachePath?.let(::File)) }
        dao.deleteAll(subtree.map { it.documentId })
    }

    private fun DocumentEntity.withLocalStateOf(existing: DocumentEntity?): DocumentEntity =
        if (existing == null) this else copy(
            isPinned = existing.isPinned,
            localCachePath = existing.localCachePath,
            cachedEtag = existing.cachedEtag,
            childrenSyncedAt = existing.childrenSyncedAt,
            lastAccessed = existing.lastAccessed,
        )

    /** Picks a free child id under [parent]. SAF expects a unique name when the requested one exists: "notes.md" → "notes (1).md". */
    suspend fun uniqueChild(credentials: AetherCredentials, parent: DocumentId, name: String): AetherResult<DocumentId> {
        if (name.isEmpty() || '/' in name) return AetherResult.Failure(AetherError.Malformed("Invalid name: $name"))
        val dav = davFactory.forAccount(credentials)
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        for (attempt in 0..MAX_NAME_ATTEMPTS) {
            val candidate = parent.child(if (attempt == 0) name else "$base ($attempt)$extension")
            when (val probe = dav.propfind(credentials.fileUrl(candidate.path), depth = 0)) {
                is AetherResult.Success -> continue
                is AetherResult.Failure -> return if (probe.error == AetherError.NotFound) AetherResult.Success(candidate) else probe
            }
        }
        return AetherResult.Failure(AetherError.Malformed("Could not find a free name for $name"))
    }

    private companion object {
        const val MAX_NAME_ATTEMPTS = 100
        const val CACHED_SEARCH_LIMIT = 100
    }
}
