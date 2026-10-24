package com.aether.nextcloud.data.cache

import com.aether.nextcloud.data.local.entity.DocumentEntity
import com.aether.nextcloud.provider.DocumentId
import java.io.File
import java.security.MessageDigest

/**
 * Local storage for file bytes (ADR-0004):
 *  - [cacheDir]: ETag-validated on-demand copies, evicted least-recently-used once over [maxCacheBytes].
 *  - [pinnedDir]: offline pins, never evicted automatically.
 *  - [stagingDir]: pending SAF write-backs awaiting upload; lives outside the evictable cache so a pending edit is never lost.
 *  - [thumbnailsDir]: provider thumbnails; regenerable, so never tracked in Room.
 *
 * New files go into one subdirectory per account (a hash of the account name), so signing out can remove everything an
 * account left behind with [forgetAccount], including files Room no longer knows about. Files directly in these
 * directories predate that layout and are still read, trimmed and counted.
 */
class DocumentDiskCache(
    private val cacheDir: File,
    private val pinnedDir: File,
    private val stagingDir: File,
    private val maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES,
    private val thumbnailsDir: File = File(cacheDir.parentFile, "thumbnails"),
) {
    fun cacheFileFor(documentId: String): File = File(accountDir(cacheDir, accountOf(documentId)), fileName(documentId))

    fun pinnedFileFor(documentId: String): File = File(accountDir(pinnedDir, accountOf(documentId)), fileName(documentId))

    fun thumbnailFor(accountName: String, fileId: String, size: Int): File =
        File(accountDir(thumbnailsDir, accountName), "${fileId}_$size")

    fun newStagingFile(documentId: String): File {
        val dir = accountDir(stagingDir, accountOf(documentId)).apply { mkdirs() }
        return File.createTempFile(fileName(documentId).take(16) + "-", ".staging", dir)
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

    fun isPinnedFile(file: File): Boolean = file.parentFile == pinnedDir || file.parentFile?.parentFile == pinnedDir

    /** Marks a cached file as recently used so [trimToSize] evicts it last. */
    fun touch(file: File) {
        file.setLastModified(System.currentTimeMillis())
    }

    /** Deletes least-recently-used cache files until the cache fits in [maxCacheBytes]; returns the deleted files. */
    fun trimToSize(protect: Set<File> = emptySet()): List<File> {
        val files = cacheDir.walkTopDown().filter { it.isFile && !it.name.endsWith(PART_SUFFIX) }.toMutableList()
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

    fun cacheSizeBytes(): Long = sizeOf(cacheDir)

    fun pinnedSizeBytes(): Long = sizeOf(pinnedDir)

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /**
     * Removes every file [accountName] has on disk: cached and pinned copies (including partial downloads), staged
     * uploads and thumbnails. Thumbnails from before the per-account layout cannot be attributed, so they are dropped
     * too; they are regenerated on demand.
     */
    fun forgetAccount(accountName: String) {
        listOf(cacheDir, pinnedDir, stagingDir, thumbnailsDir).forEach { accountDir(it, accountName).deleteRecursively() }
        thumbnailsDir.listFiles { f -> f.isFile }?.forEach { it.delete() }
    }

    private fun sizeOf(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun delete(file: File?) {
        file?.delete()
    }

    companion object {
        const val DEFAULT_MAX_CACHE_BYTES = 512L * 1024 * 1024
        private const val PART_SUFFIX = ".part"
        private const val UNKNOWN_ACCOUNT = "-"

        /** `<accountName>` of a document id such as `alice@host:/Docs/a.txt`; ids from elsewhere share one bucket. */
        private fun accountOf(documentId: String): String = DocumentId.parseOrNull(documentId)?.accountName ?: UNKNOWN_ACCOUNT

        private fun accountDir(base: File, accountName: String): File = File(base, accountKey(accountName))

        /** Opaque, stable directory name for an account. */
        fun accountKey(accountName: String): String = hash(accountName).take(16)

        private fun hash(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Opaque, filesystem-safe name; the real file name is irrelevant because Room tracks the mapping. */
        fun fileName(documentId: String): String = hash(documentId)
    }

    init {
        cacheDir.mkdirs()
        pinnedDir.mkdirs()
        stagingDir.mkdirs()
    }
}
