package com.aether.nextcloud.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.core.AetherError
import com.aether.nextcloud.core.AetherResult
import com.aether.nextcloud.provider.DocumentId
import com.aether.nextcloud.support.AetherTestRule
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentRepositoryTest {
    @get:Rule val env = AetherTestRule()

    private val documents get() = env.graph.documents()
    private val root get() = DocumentId.root(env.accountName)
    private fun id(path: String) = DocumentId(env.accountName, path)
    private val credentials get() = env.graph.accounts().credentials(env.accountName)!!

    private fun <T> ok(result: AetherResult<T>): T = (result as? AetherResult.Success)?.value ?: error("expected success but got $result")
    private fun failure(result: AetherResult<*>): AetherError = (result as? AetherResult.Failure)?.error ?: error("expected a failure but got $result")

    private fun list(path: String = "/") = runBlocking { ok(documents.refreshChildren(credentials, id(path))) }

    @Test
    fun listingAFolderCachesItsChildrenAndStampsTheFolder() {
        env.nextcloud.putFile("/a.txt", "a")
        env.nextcloud.mkdirs("/Docs")

        val folder = list()

        assertNotNull(folder.childrenSyncedAt)
        assertEquals(setOf("/a.txt", "/Docs"), documents.cachedChildren(root).map { it.path }.toSet())
        assertTrue(documents.cached(id("/Docs"))!!.isDirectory)
    }

    @Test
    fun aRefreshDropsChildrenThatDisappearedAndTheirLocalCopies() {
        env.nextcloud.putFile("/keep.txt", "k")
        env.nextcloud.putFile("/gone.txt", "g")
        list()
        val file = runBlocking { ok(documents.ensureLocalFile(credentials, id("/gone.txt"))) }
        assertTrue(file.isFile)

        env.nextcloud.delete("/gone.txt")
        list()

        assertNull(documents.cached(id("/gone.txt")))
        assertFalse("the orphaned bytes must be deleted", file.exists())
        assertNotNull(documents.cached(id("/keep.txt")))
    }

    @Test
    fun aRefreshKeepsPinFlagsAndLocalCopies() {
        env.nextcloud.putFile("/a.txt", "a")
        list()
        runBlocking { documents.setPinned(id("/a.txt"), true) }
        runBlocking { ok(documents.ensurePinnedCopy(credentials, id("/a.txt"))) }

        list()

        val row = documents.cached(id("/a.txt"))!!
        assertTrue(row.isPinned)
        assertNotNull(row.localCachePath)
    }

    @Test
    fun aMissingDocumentIsForgottenWhenTheServerReportsNotFound() {
        env.nextcloud.putFile("/a.txt", "a")
        list()
        env.nextcloud.delete("/a.txt")

        val error = failure(runBlocking { documents.refreshDocument(credentials, id("/a.txt")) })

        assertEquals(AetherError.NotFound, error)
        assertNull(documents.cached(id("/a.txt")))
    }

    @Test
    fun aValidCachedCopyIsServedWithoutDownloadingAgain() {
        env.nextcloud.putFile("/a.txt", "hello")
        list()

        val first = runBlocking { ok(documents.ensureLocalFile(credentials, id("/a.txt"))) }
        val second = runBlocking { ok(documents.ensureLocalFile(credentials, id("/a.txt"))) }

        assertEquals(first, second)
        assertEquals("hello", first.readText())
        assertEquals(1, env.nextcloud.requestsMatching("GET", "/a.txt").size)
    }

    @Test
    fun aCopyWhoseEtagChangedIsDownloadedAgain() {
        env.nextcloud.putFile("/a.txt", "v1")
        list()
        runBlocking { ok(documents.ensureLocalFile(credentials, id("/a.txt"))) }

        env.nextcloud.editRemotely("/a.txt", "v2")
        val file = runBlocking { ok(documents.ensureLocalFile(credentials, id("/a.txt"))) }

        assertEquals("v2", file.readText())
    }

    @Test
    fun offlineTheLastCopyIsStillServed() {
        env.nextcloud.putFile("/a.txt", "hello")
        list()
        runBlocking { ok(documents.ensureLocalFile(credentials, id("/a.txt"))) }
        env.nextcloud.close()

        val file = runBlocking { ok(documents.ensureLocalFile(credentials, id("/a.txt"))) }

        assertEquals("hello", file.readText())
    }

    @Test
    fun aFolderCannotBeOpenedAsAFile() {
        env.nextcloud.mkdirs("/Docs")
        list()

        assertTrue(failure(runBlocking { documents.ensureLocalFile(credentials, id("/Docs")) }) is AetherError.Malformed)
    }

    @Test
    fun pinnedBytesLiveInPinnedStorageAndUnpinningFreesThem() {
        env.nextcloud.putFile("/a.txt", "hello")
        list()
        runBlocking { documents.setPinned(id("/a.txt"), true) }
        val pinned = runBlocking { ok(documents.ensurePinnedCopy(credentials, id("/a.txt"))) }
        assertTrue(env.graph.diskCache().isPinnedFile(pinned))
        assertTrue(documents.hasCurrentPinnedCopy(documents.cached(id("/a.txt"))!!))

        runBlocking { documents.setPinned(id("/a.txt"), false) }

        assertFalse(pinned.exists())
        assertNull(documents.cached(id("/a.txt"))!!.localCachePath)
        assertFalse(documents.cached(id("/a.txt"))!!.isPinned)
    }

    @Test
    fun anExistingCacheCopyIsMigratedIntoPinnedStorageWhenPinned() {
        env.nextcloud.putFile("/a.txt", "hello")
        list()
        val cached = runBlocking { ok(documents.ensureLocalFile(credentials, id("/a.txt"))) }
        assertFalse(env.graph.diskCache().isPinnedFile(cached))

        val pinned = runBlocking { ok(documents.ensurePinnedCopy(credentials, id("/a.txt"))) }

        assertTrue(env.graph.diskCache().isPinnedFile(pinned))
        assertFalse("the evictable copy is gone", cached.exists())
        assertEquals("hello", pinned.readText())
    }

    @Test
    fun unpinningAFolderUnpinsEverythingBelowIt() {
        env.nextcloud.putFile("/Docs/a.txt", "a")
        list()
        list("/Docs")
        runBlocking {
            documents.setPinned(id("/Docs"), true)
            documents.pinChildren(id("/Docs"))
            documents.setPinned(id("/Docs"), false)
        }

        assertTrue(runBlocking { documents.pinnedDocuments() }.isEmpty())
    }

    @Test
    fun uniqueChildAddsANumberBeforeTheExtension() {
        env.nextcloud.putFile("/notes.md", "1")
        env.nextcloud.putFile("/notes (1).md", "2")

        assertEquals(id("/notes (2).md"), runBlocking { ok(documents.uniqueChild(credentials, root, "notes.md")) })
        assertEquals(id("/fresh.md"), runBlocking { ok(documents.uniqueChild(credentials, root, "fresh.md")) })
    }

    @Test
    fun uniqueChildRejectsEmptyNamesAndSlashes() {
        assertTrue(failure(runBlocking { documents.uniqueChild(credentials, root, "") }) is AetherError.Malformed)
        assertTrue(failure(runBlocking { documents.uniqueChild(credentials, root, "a/b") }) is AetherError.Malformed)
    }

    @Test
    fun createDirectoryPicksAFreeNameAndCreatesItOnTheServer() {
        env.nextcloud.mkdirs("/Photos")

        val created = runBlocking { ok(documents.createDirectory(credentials, root, "Photos")) }

        assertEquals("/Photos (1)", created.path)
        assertTrue(env.nextcloud.exists("/Photos (1)"))
    }

    @Test
    fun createEmptyFileUploadsAZeroByteFile() {
        val created = runBlocking { ok(documents.createEmptyFile(credentials, root, "new.txt", "text/plain")) }

        assertEquals("/new.txt", created.path)
        assertEquals("", env.nextcloud.text("/new.txt"))
    }

    @Test
    fun deletingRemovesItFromTheServerAndTheCacheIncludingChildren() {
        env.nextcloud.putFile("/Docs/a.txt", "a")
        list()
        list("/Docs")

        ok(runBlocking { documents.delete(credentials, id("/Docs")) })

        assertFalse(env.nextcloud.exists("/Docs"))
        assertNull(documents.cached(id("/Docs")))
        assertNull(documents.cached(id("/Docs/a.txt")))
    }

    @Test
    fun deletingSomethingAlreadyGoneCountsAsDeleted() {
        env.nextcloud.putFile("/a.txt", "a")
        list()
        env.nextcloud.delete("/a.txt")

        ok(runBlocking { documents.delete(credentials, id("/a.txt")) })

        assertNull(documents.cached(id("/a.txt")))
    }

    @Test
    fun aLockedFileCannotBeDeletedAndStaysCached() {
        env.nextcloud.putFile("/a.txt", "a")
        list()
        env.nextcloud.locked += "/a.txt"

        failure(runBlocking { documents.delete(credentials, id("/a.txt")) })

        assertNotNull(documents.cached(id("/a.txt")))
        assertTrue(env.nextcloud.exists("/a.txt"))
    }

    @Test
    fun renamingAFolderReKeysItsSubtreeAndKeepsPins() {
        env.nextcloud.putFile("/Docs/a.txt", "a")
        list()
        list("/Docs")
        runBlocking { documents.setPinned(id("/Docs/a.txt"), true) }

        val renamed = ok(runBlocking { documents.rename(credentials, id("/Docs"), "Papers") })

        assertEquals(id("/Papers"), renamed)
        assertTrue(env.nextcloud.exists("/Papers/a.txt"))
        assertNull(documents.cached(id("/Docs/a.txt")))
        val moved = documents.cached(id("/Papers/a.txt"))!!
        assertTrue("the pin follows the file", moved.isPinned)
        assertEquals("/Papers", moved.parentId?.let(DocumentId::parse)?.path)
        assertEquals("a.txt", moved.name)
    }

    @Test
    fun renamingToAnInvalidNameOrOntoARootIsRefused() {
        assertTrue(failure(runBlocking { documents.rename(credentials, root, "x") }) is AetherError.Malformed)
        env.nextcloud.putFile("/a.txt", "a")
        list()
        assertTrue(failure(runBlocking { documents.rename(credentials, id("/a.txt"), "b/c") }) is AetherError.Malformed)
    }

    @Test
    fun movingAFileIntoAFolderRelocatesIt() {
        env.nextcloud.putFile("/a.txt", "a")
        env.nextcloud.mkdirs("/Docs")
        list()

        val moved = ok(runBlocking { documents.move(credentials, id("/a.txt"), id("/Docs")) })

        assertEquals(id("/Docs/a.txt"), moved)
        assertTrue(env.nextcloud.exists("/Docs/a.txt"))
        assertFalse(env.nextcloud.exists("/a.txt"))
    }

    @Test
    fun aFolderCannotBeMovedIntoItselfOrItsOwnChild() {
        env.nextcloud.mkdirs("/Docs/Inner")

        assertTrue(failure(runBlocking { documents.move(credentials, id("/Docs"), id("/Docs")) }) is AetherError.Malformed)
        assertTrue(failure(runBlocking { documents.move(credentials, id("/Docs"), id("/Docs/Inner")) }) is AetherError.Malformed)
        assertTrue(env.nextcloud.requestsMatching("MOVE").isEmpty())
    }

    @Test
    fun adoptingAnUploadedFileSkipsTheNextDownload() {
        env.nextcloud.putFile("/a.txt", "old")
        list()
        val staged = File.createTempFile("adopt", ".tmp").apply { writeText("new") }
        env.nextcloud.editRemotely("/a.txt", "new")
        val etag = env.nextcloud.etag("/a.txt")

        runBlocking { documents.adoptUploadedFile(credentials, id("/a.txt"), staged, etag) }
        val served = runBlocking { ok(documents.ensureLocalFile(credentials, id("/a.txt"))) }

        assertEquals("new", served.readText())
        assertTrue("no GET needed", env.nextcloud.requestsMatching("GET", "/a.txt").isEmpty())
        staged.delete()
    }

    @Test
    fun anUploadWhoseEtagDoesNotMatchIsNotAdopted() {
        env.nextcloud.putFile("/a.txt", "old")
        list()
        val staged = File.createTempFile("adopt", ".tmp").apply { writeText("mine") }

        runBlocking { documents.adoptUploadedFile(credentials, id("/a.txt"), staged, "somebody-else") }

        assertNull(documents.cached(id("/a.txt"))!!.localCachePath)
        staged.delete()
    }

    @Test
    fun forgettingAnAccountRemovesItsRowsAndFiles() {
        env.nextcloud.putFile("/a.txt", "a")
        list()
        val file = runBlocking { ok(documents.ensureLocalFile(credentials, id("/a.txt"))) }

        runBlocking { documents.forgetAccount(env.accountName) }

        assertNull(documents.cached(id("/a.txt")))
        assertFalse(file.exists())
    }
}
