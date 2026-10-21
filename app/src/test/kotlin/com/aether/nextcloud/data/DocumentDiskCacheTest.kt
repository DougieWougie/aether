package com.aether.nextcloud.data

import com.aether.nextcloud.data.cache.DocumentDiskCache
import com.aether.nextcloud.data.local.entity.DocumentEntity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocumentDiskCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var cache: DocumentDiskCache

    @Before
    fun setUp() {
        cache = DocumentDiskCache(File(tmp.root, "cache"), File(tmp.root, "pinned"), File(tmp.root, "staging"), maxCacheBytes = 100)
    }

    private fun entity(etag: String? = "e1", cachedEtag: String? = "e1", path: String? = null) = DocumentEntity(
        documentId = "a@h:/f.txt", accountName = "a@h", path = "/f.txt", parentId = "a@h:/", name = "f.txt",
        isDirectory = false, mimeType = "text/plain", size = 10, lastModified = 1, etag = etag, fileId = "1",
        permissions = "RGDNVW", hasPreview = false, localCachePath = path, cachedEtag = cachedEtag,
    )

    private fun cached(name: String, bytes: Int, age: Long): File =
        File(tmp.root, "cache/$name").apply {
            writeBytes(ByteArray(bytes))
            setLastModified(System.currentTimeMillis() - age)
        }

    @Test
    fun `file names are stable, opaque and distinct per document`() {
        val a = cache.cacheFileFor("a@h:/f.txt")
        assertEquals(a, cache.cacheFileFor("a@h:/f.txt"))
        assertNotEquals(a, cache.cacheFileFor("a@h:/g.txt"))
        assertFalse(a.name.contains("f.txt"))
        assertEquals(a.name, cache.pinnedFileFor("a@h:/f.txt").name)
        assertNotEquals(a.parentFile, cache.pinnedFileFor("a@h:/f.txt").parentFile)
    }

    @Test
    fun `copy is valid only while the etag matches and the file exists`() {
        val file = cached("copy", 5, 0)
        assertEquals(file, cache.validCopy(entity(path = file.path)))
        assertNull("etag changed on server", cache.validCopy(entity(etag = "e2", path = file.path)))
        assertNull("no etag known", cache.validCopy(entity(etag = null, cachedEtag = null, path = file.path)))
        assertNull("never downloaded", cache.validCopy(entity(cachedEtag = null, path = file.path)))
        assertNull("no local path", cache.validCopy(entity(path = null)))
        file.delete()
        assertNull("evicted from disk", cache.validCopy(entity(path = file.path)))
    }

    @Test
    fun `an explicit remote etag overrides the stored one`() {
        val file = cached("copy", 5, 0)
        assertNull(cache.validCopy(entity(path = file.path), remoteEtag = "newer"))
        assertEquals(file, cache.validCopy(entity(path = file.path), remoteEtag = "e1"))
    }

    @Test
    fun `trim evicts least recently used first until under the limit`() {
        val oldest = cached("oldest", 40, 30_000)
        val middle = cached("middle", 40, 20_000)
        val newest = cached("newest", 40, 10_000)

        val evicted = cache.trimToSize()

        assertEquals(listOf(oldest), evicted)
        assertFalse(oldest.exists())
        assertTrue(middle.exists() && newest.exists())
        assertTrue(cache.cacheSizeBytes() <= 100)
    }

    @Test
    fun `touching a file protects it from eviction`() {
        val a = cached("a", 60, 30_000)
        val b = cached("b", 60, 20_000)
        cache.touch(a)

        cache.trimToSize()

        assertTrue(a.exists())
        assertFalse(b.exists())
    }

    @Test
    fun `trim never evicts protected files or pinned storage`() {
        val protected = cached("protected", 90, 30_000)
        val other = cached("other", 90, 10_000)
        val pinned = File(tmp.root, "pinned/p").apply { writeBytes(ByteArray(500)) }

        cache.trimToSize(protect = setOf(protected))

        assertTrue(protected.exists())
        assertFalse(other.exists())
        assertTrue(pinned.exists())
        assertEquals(500, cache.pinnedSizeBytes())
    }

    @Test
    fun `partial downloads are ignored by trim`() {
        val partial = cached("x.part", 500, 99_999)
        cache.trimToSize()
        assertTrue(partial.exists())
    }

    @Test
    fun `staging files are unique and live outside the evictable cache`() {
        val one = cache.newStagingFile("a@h:/f.txt")
        val two = cache.newStagingFile("a@h:/f.txt")
        assertNotEquals(one, two)
        assertEquals(File(tmp.root, "staging"), one.parentFile)
        cache.clearCache()
        assertTrue(one.exists())
    }

    @Test
    fun `isPinnedFile distinguishes the two stores`() {
        assertTrue(cache.isPinnedFile(cache.pinnedFileFor("x")))
        assertFalse(cache.isPinnedFile(cache.cacheFileFor("x")))
    }
}
