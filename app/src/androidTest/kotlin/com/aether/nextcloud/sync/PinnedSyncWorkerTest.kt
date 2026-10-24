package com.aether.nextcloud.sync

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.aether.nextcloud.data.local.entity.TransferDirection
import com.aether.nextcloud.data.local.entity.TransferState
import com.aether.nextcloud.provider.DocumentId
import com.aether.nextcloud.support.AetherTestRule
import com.aether.nextcloud.support.awaitTrue
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the real [PinnedSyncWorker] against [com.aether.nextcloud.support.FakeNextcloud] with the app's own repositories. */
@RunWith(AndroidJUnit4::class)
class PinnedSyncWorkerTest {
    @get:Rule val env = AetherTestRule()

    private val root get() = DocumentId.root(env.accountName)
    private fun id(path: String) = DocumentId(env.accountName, path)
    private val credentials get() = env.graph.accounts().credentials(env.accountName)!!

    /** Lists the root so the pin has a cached row to attach to, as the picker does through the provider. */
    private fun pin(path: String) = runBlocking {
        val documents = env.graph.documents()
        documents.refreshChildren(credentials, root)
        if (path != "/") {
            val parent = id(path).parent!!
            if (parent != root) documents.refreshChildren(credentials, parent)
        }
        documents.setPinned(id(path), true)
    }

    private fun runWorker(): ListenableWorker.Result = runBlocking {
        val graph = env.graph
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                PinnedSyncWorker(appContext, workerParameters, graph.accounts(), graph.documents(), graph.database(), graph.notifications(), graph.authIssues())
        }
        TestListenableWorkerBuilder<PinnedSyncWorker>(env.context).setWorkerFactory(factory).build().doWork()
    }

    private fun pinnedBytes(path: String): ByteArray? =
        env.graph.documents().cached(id(path))?.localCachePath?.let(::File)?.takeIf(File::isFile)?.readBytes()

    private fun downloads() = env.graph.database().transferDao().let { dao ->
        runBlocking { dao.observeRecent(50).first() }.filter { it.accountName == env.accountName && it.direction == TransferDirection.DOWNLOAD }
    }

    @Test
    fun aPinnedFileIsDownloadedIntoPinnedStorageAndRecordedAsACompletedTransfer() {
        env.nextcloud.putFile("/notes.txt", "hello")
        pin("/notes.txt")

        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertEquals("hello", pinnedBytes("/notes.txt")?.decodeToString())
        val local = File(env.graph.documents().cached(id("/notes.txt"))!!.localCachePath!!)
        assertTrue("copy should live in pinned storage, not the evictable cache", env.graph.diskCache().isPinnedFile(local))
        assertEquals(listOf(TransferState.COMPLETED), downloads().map { it.state })
        assertEquals("/notes.txt", downloads().single().remotePath)
    }

    @Test
    fun aFileThatIsAlreadyCurrentIsNotDownloadedAgain() {
        env.nextcloud.putFile("/notes.txt", "hello")
        pin("/notes.txt")
        runWorker()
        val gets = env.nextcloud.requestsMatching("GET", "/notes.txt").size

        runWorker()

        assertEquals("an unchanged pinned file must not be fetched again", gets, env.nextcloud.requestsMatching("GET", "/notes.txt").size)
        assertEquals("no second transfer row", 1, downloads().size)
    }

    @Test
    fun aFileEditedOnTheServerIsDownloadedAgain() {
        env.nextcloud.putFile("/notes.txt", "v1")
        pin("/notes.txt")
        runWorker()

        env.nextcloud.editRemotely("/notes.txt", "v2")
        runWorker()

        assertEquals("v2", pinnedBytes("/notes.txt")?.decodeToString())
    }

    @Test
    fun aPinnedFolderPullsEverythingBelowIt() {
        env.nextcloud.putFile("/Docs/a.txt", "a")
        env.nextcloud.putFile("/Docs/deep/b.txt", "b")
        pin("/Docs")

        runWorker()

        assertEquals("a", pinnedBytes("/Docs/a.txt")?.decodeToString())
        assertEquals("b", pinnedBytes("/Docs/deep/b.txt")?.decodeToString())
        assertTrue(env.graph.documents().cached(id("/Docs/deep"))!!.isPinned)
    }

    @Test
    fun filesAddedToAPinnedFolderLaterArePickedUp() {
        env.nextcloud.putFile("/Docs/a.txt", "a")
        pin("/Docs")
        runWorker()

        env.nextcloud.putFile("/Docs/new.txt", "fresh")
        // Adding a file changes the folder's ETag on a real server; the fake leaves it alone, so do it explicitly.
        env.nextcloud.editRemotely("/Docs/a.txt", "a2")
        env.nextcloud.touchFolder("/Docs")
        runWorker()

        assertEquals("fresh", pinnedBytes("/Docs/new.txt")?.decodeToString())
        assertEquals("a2", pinnedBytes("/Docs/a.txt")?.decodeToString())
    }

    @Test
    fun aFolderWhoseEtagHasNotChangedIsNotListedAgain() {
        env.nextcloud.putFile("/Docs/a.txt", "a")
        pin("/Docs")
        runWorker()
        val listings = env.nextcloud.requestsMatching("PROPFIND", "/Docs").count { it.getHeader("Depth") == "1" }

        runWorker()

        assertEquals("ADR-0004: an unchanged ETag skips the subtree", listings, env.nextcloud.requestsMatching("PROPFIND", "/Docs").count { it.getHeader("Depth") == "1" })
    }

    @Test
    fun anItemPinnedInsideAPinnedFolderIsSyncedOnceThroughItsAncestor() {
        env.nextcloud.putFile("/Docs/a.txt", "a")
        pin("/Docs")
        runBlocking { env.graph.documents().refreshChildren(credentials, id("/Docs")) }
        runBlocking { env.graph.documents().setPinned(id("/Docs/a.txt"), true) }

        runWorker()

        assertEquals(1, env.nextcloud.requestsMatching("GET", "/Docs/a.txt").size)
    }

    @Test
    fun aFileDeletedOnTheServerIsDroppedFromTheCache() {
        env.nextcloud.putFile("/gone.txt", "bye")
        pin("/gone.txt")
        env.nextcloud.delete("/gone.txt")

        runWorker()

        assertNull(env.graph.documents().cached(id("/gone.txt")))
    }

    @Test
    fun aRevokedAppPasswordIsReportedAndNotRetried() {
        env.nextcloud.putFile("/notes.txt", "hello")
        pin("/notes.txt")
        env.nextcloud.forcedStatus = 401

        val result = runWorker()

        assertEquals("a bad password will not fix itself", ListenableWorker.Result.success(), result)
        awaitTrue("the account to be flagged as needing sign-in") { runBlocking { env.graph.authIssues().accounts.first() }.contains(env.accountName) }
        runBlocking { env.graph.authIssues().resolve(env.accountName) }
    }

    @Test
    fun aServerOutageAsksWorkManagerToRetry() {
        env.nextcloud.putFile("/notes.txt", "hello")
        pin("/notes.txt")
        env.nextcloud.forcedStatus = 503

        assertEquals(ListenableWorker.Result.retry(), runWorker())
        assertFalse("nothing should be downloaded", pinnedBytes("/notes.txt") != null)
    }

    @Test
    fun aFailedDownloadIsRecordedAsFailedWithAReason() {
        env.nextcloud.putFile("/notes.txt", "hello")
        pin("/notes.txt")
        env.nextcloud.failDownloads = true

        runWorker()

        val row = downloads().single()
        assertEquals(TransferState.FAILED, row.state)
        assertNotNull(row.error)
    }

    @Test
    fun accountsWithoutCredentialsAreSkipped() {
        env.nextcloud.putFile("/notes.txt", "hello")
        pin("/notes.txt")
        env.graph.accounts().removeAccount(env.accountName)

        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertTrue("no request should be made without credentials", env.nextcloud.requestsMatching("GET").isEmpty())
    }

    @Test
    fun finishedTransfersOlderThanADayArePruned() {
        val dao = env.graph.database().transferDao()
        val dayAndABit = System.currentTimeMillis() - 25 * 3_600_000L
        val oldId = dao.insert(com.aether.nextcloud.data.local.entity.TransferEntity(accountName = env.accountName, remotePath = "/old", direction = TransferDirection.DOWNLOAD, state = TransferState.COMPLETED, createdAt = dayAndABit))
        dao.setState(oldId, TransferState.COMPLETED, null, dayAndABit)

        runWorker()

        assertNull(dao.get(oldId))
    }
}
