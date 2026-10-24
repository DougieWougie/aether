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
        assertEquals(File(tmp.root, "staging/${DocumentDiskCache.accountKey("a@h")}"), one.parentFile)
        cache.clearCache()
        assertTrue(one.exists())
    }

    @Test
    fun `isPinnedFile distinguishes the two stores`() {
        assertTrue(cache.isPinnedFile(cache.pinnedFileFor("x")))
        assertFalse(cache.isPinnedFile(cache.cacheFileFor("x")))
    }

    @Test
    fun `files are grouped per account and never share a directory`() {
        val alice = cache.cacheFileFor("alice@h:/f.txt")
        val bob = cache.cacheFileFor("bob@h:/f.txt")

        assertEquals(File(tmp.root, "cache/${DocumentDiskCache.accountKey("alice@h")}"), alice.parentFile)
        assertNotEquals(alice.parentFile, bob.parentFile)
        assertEquals(File(tmp.root, "pinned/${DocumentDiskCache.accountKey("alice@h")}"), cache.pinnedFileFor("alice@h:/f.txt").parentFile)
        assertFalse(alice.parentFile!!.name.contains("alice"))
    }

    @Test
    fun `an account with a port in its name is still grouped by the whole name`() {
        assertEquals(
            File(tmp.root, "cache/${DocumentDiskCache.accountKey("alice@h:8443")}"),
            cache.cacheFileFor("alice@h:8443:/f.txt").parentFile,
        )
    }

    @Test
    fun `thumbnails are separated per account even for the same file id`() {
        val a = cache.thumbnailFor("alice@h", "1050", 256, "etag")
        val b = cache.thumbnailFor("bob@h", "1050", 256, "etag")

        assertNotEquals(a, b)
        assertNotEquals(a.parentFile, b.parentFile)
        assertTrue(a.name.startsWith("1050_256_"))
        assertEquals(File(tmp.root, "thumbnails"), a.parentFile!!.parentFile)
    }

    @Test
    fun `a changed etag gives a different thumbnail file`() {
        val before = cache.thumbnailFor("alice@h", "1050", 256, "\"v1\"")
        val after = cache.thumbnailFor("alice@h", "1050", 256, "\"v2\"")

        assertNotEquals(before, after)
        assertEquals(before, cache.thumbnailFor("alice@h", "1050", 256, "\"v1\""))
    }

    @Test
    fun `outdated thumbnails of the same file and size are removed, others kept`() {
        val old = write(cache.thumbnailFor("alice@h", "7", 256, "v1"))
        val current = write(cache.thumbnailFor("alice@h", "7", 256, "v2"))
        val otherSize = write(cache.thumbnailFor("alice@h", "7", 128, "v1"))
        val otherFile = write(cache.thumbnailFor("alice@h", "77", 256, "v1"))
        val otherAccount = write(cache.thumbnailFor("bob@h", "7", 256, "v1"))

        cache.removeOutdatedThumbnails(current)

        assertFalse(old.exists())
        assertTrue(listOf(current, otherSize, otherFile, otherAccount).all { it.exists() })
    }

    private fun write(file: File, bytes: Int = 10): File = file.apply { parentFile!!.mkdirs(); writeBytes(ByteArray(bytes)) }

    @Test
    fun `forgetAccount removes everything the account has on disk and nothing else`() {
        val mine = listOf(
            write(cache.cacheFileFor("alice@h:/a.txt")),
            write(File(cache.cacheFileFor("alice@h:/b.txt").path + ".part")),
            write(cache.pinnedFileFor("alice@h:/p.txt")),
            cache.newStagingFile("alice@h:/s.txt").also { it.writeBytes(ByteArray(5)) },
            write(cache.thumbnailFor("alice@h", "7", 128, "etag")),
        )
        val theirs = listOf(
            write(cache.cacheFileFor("bob@h:/a.txt")),
            write(cache.pinnedFileFor("bob@h:/p.txt")),
            cache.newStagingFile("bob@h:/s.txt").also { it.writeBytes(ByteArray(5)) },
            write(cache.thumbnailFor("bob@h", "7", 128, "etag")),
        )

        cache.forgetAccount("alice@h")

        assertTrue(mine.none { it.exists() })
        assertTrue(theirs.all { it.exists() })
        assertFalse("account directories are removed too", File(tmp.root, "cache/${DocumentDiskCache.accountKey("alice@h")}").exists())
    }

    @Test
    fun `forgetAccount also drops thumbnails from before the per-account layout`() {
        val legacy = write(File(tmp.root, "thumbnails/12345_7_128"))

        cache.forgetAccount("alice@h")

        assertFalse(legacy.exists())
    }

    @Test
    fun `forgetAccount is harmless when the account has no files`() {
        cache.forgetAccount("nobody@h")
        assertEquals(0, cache.cacheSizeBytes())
    }

    @Test
    fun `sizes and trimming see files in account directories and old flat files alike`() {
        val old = cached("legacy", 60, 30_000)
        val fresh = write(cache.cacheFileFor("alice@h:/new.bin"), 60).apply { setLastModified(System.currentTimeMillis()) }
        val pinned = write(cache.pinnedFileFor("alice@h:/p.bin"), 25)

        assertEquals(120, cache.cacheSizeBytes())
        assertEquals(25, cache.pinnedSizeBytes())

        val evicted = cache.trimToSize()

        assertEquals(listOf(old), evicted)
        assertTrue(fresh.exists() && pinned.exists())
    }

    @Test
    fun `clearCache empties account directories but keeps pinned and staged files`() {
        val cachedFile = write(cache.cacheFileFor("alice@h:/a.txt"))
        val pinned = write(cache.pinnedFileFor("alice@h:/p.txt"))
        val staged = cache.newStagingFile("alice@h:/s.txt")

        cache.clearCache()

        assertFalse(cachedFile.exists())
        assertTrue(pinned.exists() && staged.exists())
        assertEquals(0, cache.cacheSizeBytes())
    }

    @Test
    fun `isPinnedFile recognises pinned files in account directories and the old flat layout`() {
        assertTrue(cache.isPinnedFile(cache.pinnedFileFor("alice@h:/p.txt")))
        assertTrue(cache.isPinnedFile(File(tmp.root, "pinned/legacy")))
        assertFalse(cache.isPinnedFile(cache.cacheFileFor("alice@h:/p.txt")))
    }

    private fun thumbnailCache(maxBytes: Long) =
        DocumentDiskCache(File(tmp.root, "cache"), File(tmp.root, "pinned"), File(tmp.root, "staging"), maxCacheBytes = 1_000, maxThumbnailBytes = maxBytes)

    private fun thumbnail(cache: DocumentDiskCache, account: String, fileId: String, bytes: Int, age: Long): File =
        write(cache.thumbnailFor(account, fileId, 256, "etag"), bytes).apply { setLastModified(System.currentTimeMillis() - age) }

    @Test
    fun `thumbnails are trimmed oldest first across accounts until they fit`() {
        val small = thumbnailCache(maxBytes = 100)
        val oldest = thumbnail(small, "alice@h", "1", 40, 30_000)
        val middle = thumbnail(small, "bob@h", "2", 40, 20_000)
        val newest = thumbnail(small, "alice@h", "3", 40, 10_000)

        val evicted = small.trimThumbnails()

        assertEquals(listOf(oldest), evicted)
        assertTrue(middle.exists() && newest.exists())
    }

    @Test
    fun `a thumbnail that was just used survives trimming`() {
        val small = thumbnailCache(maxBytes = 60)
        val used = thumbnail(small, "alice@h", "1", 40, 30_000)
        val other = thumbnail(small, "alice@h", "2", 40, 20_000)
        small.touch(used)

        small.trimThumbnails()

        assertTrue(used.exists())
        assertFalse(other.exists())
    }

    @Test
    fun `the thumbnail being served is protected even when it alone exceeds the limit`() {
        val small = thumbnailCache(maxBytes = 10)
        val fresh = thumbnail(small, "alice@h", "1", 40, 0)
        val old = thumbnail(small, "alice@h", "2", 5, 30_000)

        small.trimThumbnails(protect = setOf(fresh))

        assertTrue(fresh.exists())
        assertFalse(old.exists())
    }

    @Test
    fun `trimming thumbnails leaves file copies alone and vice versa`() {
        val small = thumbnailCache(maxBytes = 10)
        val copy = write(small.cacheFileFor("alice@h:/a.bin"), 500)
        val thumb = thumbnail(small, "alice@h", "1", 40, 30_000)

        small.trimThumbnails()
        assertTrue(copy.exists())
        assertFalse(thumb.exists())

        val thumb2 = thumbnail(small, "alice@h", "2", 5, 0)
        small.trimToSize()
        assertTrue(thumb2.exists())
    }

    @Test
    fun `trimming thumbnails with none stored does nothing`() {
        assertEquals(emptyList<File>(), thumbnailCache(maxBytes = 10).trimThumbnails())
    }
}
