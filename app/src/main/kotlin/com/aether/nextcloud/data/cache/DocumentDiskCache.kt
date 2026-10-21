package com.aether.nextcloud.data.cache

import com.aether.nextcloud.data.local.entity.DocumentEntity
import java.io.File
import java.security.MessageDigest

/**
 * Local storage for file bytes (ADR-0004):
 *  - [cacheDir]: ETag-validated on-demand copies, evicted least-recently-used once over [maxCacheBytes].
 *  - [pinnedDir]: offline pins, never evicted automatically.
 *  - [stagingDir]: pending SAF write-backs awaiting upload; lives outside the evictable cache so a pending edit is never lost.
 */
class DocumentDiskCache(
    private val cacheDir: File,
    private val pinnedDir: File,
    private val stagingDir: File,
    private val maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES,
) {
    fun cacheFileFor(documentId: String): File = File(cacheDir, fileName(documentId))

    fun pinnedFileFor(documentId: String): File = File(pinnedDir, fileName(documentId))

    fun newStagingFile(documentId: String): File {
        stagingDir.mkdirs()
        return File.createTempFile(fileName(documentId).take(16) + "-", ".staging", stagingDir)
    }

    /**
     * The local copy of [entity] if it exists and was downloaded for the server's current ETag, otherwise null.
     * Callers pass the freshest [remoteEtag] they know (from PROPFIND); a null ETag never validates.
     */
    fun validCopy(entity: DocumentEntity, remoteEtag: String? = entity.etag): File? {
        if (remoteEtag == null || entity.cachedEtag != remoteEtag) return null
        val file = entity.localCachePath?.let(::File) ?: return null
        return file.takeIf { it.isFile }
    }

    fun isPinnedFile(file: File): Boolean = file.parentFile == pinnedDir

    /** Marks a cached file as recently used so [trimToSize] evicts it last. */
    fun touch(file: File) {
        file.setLastModified(System.currentTimeMillis())
    }

    /** Deletes least-recently-used cache files until the cache fits in [maxCacheBytes]; returns the deleted files. */
    fun trimToSize(protect: Set<File> = emptySet()): List<File> {
        val files = cacheDir.listFiles { f -> f.isFile && !f.name.endsWith(PART_SUFFIX) }?.toMutableList() ?: return emptyList()
        var total = files.sumOf { it.length() }
        val evicted = mutableListOf<File>()
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= maxCacheBytes) break
            if (file in protect) continue
            total -= file.length()
            if (file.delete()) evicted += file
        }
        return evicted
    }

    fun cacheSizeBytes(): Long = cacheDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    fun pinnedSizeBytes(): Long = pinnedDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    fun delete(file: File?) {
        file?.delete()
    }

    companion object {
        const val DEFAULT_MAX_CACHE_BYTES = 512L * 1024 * 1024
        private const val PART_SUFFIX = ".part"

        /** Opaque, filesystem-safe name; the real file name is irrelevant because Room tracks the mapping. */
        fun fileName(documentId: String): String =
            MessageDigest.getInstance("SHA-256").digest(documentId.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    init {
        cacheDir.mkdirs()
        pinnedDir.mkdirs()
        stagingDir.mkdirs()
    }
}
