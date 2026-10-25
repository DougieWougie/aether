package com.aether.nextcloud.sync

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.aether.nextcloud.AetherApp
import com.aether.nextcloud.data.local.entity.TransferState
import com.aether.nextcloud.support.AetherTestRule
import com.aether.nextcloud.support.awaitTrue
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** [UploadDocumentWorker] run directly, so every way an upload can end is reachable. */
@RunWith(AndroidJUnit4::class)
class UploadDocumentWorkerFailureTest {
    @get:Rule val env = AetherTestRule()

    private val app: AetherApp = ApplicationProvider.getApplicationContext()
    private val staged = mutableListOf<File>()

    @After
    fun cleanUp() {
        staged.forEach { it.delete() }
        runBlocking { env.graph.authIssues().resolve(env.accountName) }
    }

    private fun stage(text: String = "payload", name: String = "staged"): File =
        File.createTempFile(name, ".tmp", app.cacheDir).apply { writeText(text); staged += this }

    private fun input(file: File, path: String = "/doc.txt", ifMatch: String? = null): Data = UploadDocumentWorker.inputData(
        UploadRequest(env.accountName, path, file, "text/plain", ifMatch),
    )

    private fun run(data: Data, attempt: Int = 0): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<UploadDocumentWorker>(app).setWorkerFactory(app.workerFactory).setInputData(data).setRunAttemptCount(attempt).build().doWork()
    }

    private fun transfers() = runBlocking { env.graph.database().transferDao().observeRecent(50).first() }.filter { it.accountName == env.accountName }

    private fun queued() = runBlocking { env.graph.database().transferDao().observeActive().first() }.filter { it.accountName == env.accountName }

    @Test
    fun anUploadReachesTheServerAndCleansUpAfterItself() {
        val file = stage("hello")

        assertEquals(ListenableWorker.Result.success(), run(input(file)))

        assertEquals("hello", env.nextcloud.text("/doc.txt"))
        assertFalse("the staged copy is deleted", file.exists())
        assertEquals(TransferState.COMPLETED, transfers().single().state)
    }

    @Test
    fun incompleteInstructionsFailImmediately() {
        val file = stage()

        assertEquals(ListenableWorker.Result.failure(), run(workDataOf()))
        assertEquals(ListenableWorker.Result.failure(), run(workDataOf(UploadDocumentWorker.KEY_ACCOUNT to env.accountName)))
        assertEquals(
            ListenableWorker.Result.failure(),
            run(workDataOf(UploadDocumentWorker.KEY_ACCOUNT to env.accountName, UploadDocumentWorker.KEY_REMOTE_PATH to "/x")),
        )
        assertTrue(file.exists())
        assertTrue(env.nextcloud.requests.isEmpty())
    }

    @Test
    fun aStagedFileThatIsGoneFails() {
        val file = stage().also { it.delete() }

        assertEquals(ListenableWorker.Result.failure(), run(input(file)))
        assertTrue(env.nextcloud.requests.isEmpty())
    }

    @Test
    fun anAccountThatWasRemovedDiscardsTheStagedFile() {
        val file = stage()
        env.graph.accounts().removeAccount(env.accountName)

        assertEquals(ListenableWorker.Result.failure(), run(input(file)))

        assertFalse(file.exists())
    }

    @Test
    fun aRejectedPasswordFailsButKeepsTheBytesAndFlagsTheAccount() {
        val file = stage()
        env.nextcloud.forcedStatus = 401

        assertEquals(ListenableWorker.Result.failure(), run(input(file)))

        assertTrue("the user can fix this by signing in, so the bytes stay", file.exists())
        awaitTrue("the account to be flagged") { runBlocking { env.graph.authIssues().accounts.first() }.contains(env.accountName) }
        assertEquals(TransferState.FAILED, transfers().single().state)
    }

    @Test
    fun aServerOutageIsRetriedAndShownAsQueued() {
        val file = stage()
        env.nextcloud.forcedStatus = 503

        assertEquals(ListenableWorker.Result.retry(), run(input(file)))

        assertTrue(file.exists())
        val row = queued().single()
        assertEquals(TransferState.QUEUED, row.state)
        assertNotNull(row.error)
    }

    @Test
    fun aLockedFileIsRetriedBecauseTheOtherEditorWillLetGo() {
        val file = stage()
        env.nextcloud.putFile("/doc.txt", "old")
        env.nextcloud.locked += "/doc.txt"

        assertEquals(ListenableWorker.Result.retry(), run(input(file)))
    }

    @Test
    fun afterTheLastAttemptARetryableErrorBecomesAFailureAndTheBytesAreKeptForRetry() {
        val file = stage()
        env.nextcloud.forcedStatus = 503

        assertEquals(ListenableWorker.Result.failure(), run(input(file), attempt = 5))

        assertTrue(file.exists())
        val row = transfers().single()
        assertEquals(TransferState.FAILED, row.state)
        assertEquals(file.absolutePath, row.sourcePath)
        assertEquals("text/plain", row.mimeType)
    }

    @Test
    fun aFullQuotaFailsAtOnceAndKeepsTheBytesForRetry() {
        val file = stage()
        env.nextcloud.quotaFull = true

        assertEquals(ListenableWorker.Result.failure(), run(input(file)))

        assertTrue(file.exists())
        assertEquals(1, env.nextcloud.requestsMatching("PUT").size)
    }

    @Test
    fun aConflictCopyThatCannotBeSavedFailsTheUpload() {
        val file = stage("mine")
        env.nextcloud.putFile("/doc.txt", "theirs")
        env.nextcloud.putScript += listOf(412, 507)

        val result = run(input(file, ifMatch = "stale-etag"))

        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals("theirs", env.nextcloud.text("/doc.txt"))
    }

    @Test
    fun aStaleEditIsSavedAsAConflictCopyAndRecordedAsOne() {
        val file = stage("mine")
        env.nextcloud.putFile("/doc.txt", "theirs")

        assertEquals(ListenableWorker.Result.success(), run(input(file, ifMatch = "stale-etag")))

        assertEquals("theirs", env.nextcloud.text("/doc.txt"))
        val copy = env.nextcloud.paths().single { it.contains("(Conflict ") }
        assertEquals("mine", env.nextcloud.text(copy))
        assertEquals(TransferState.CONFLICT, transfers().single().state)
    }

    @Test
    fun anEditOfARootLevelFileThatConflictsStillFindsAPlaceForTheCopy() {
        val file = stage("mine")
        env.nextcloud.putFile("/top.txt", "theirs")

        assertEquals(ListenableWorker.Result.success(), run(input(file, path = "/top.txt", ifMatch = "stale-etag")))

        assertTrue(env.nextcloud.paths().any { it.startsWith("/top (Conflict ") })
    }

    @Test
    fun aLargeUploadRunsAsAForegroundTransfer() {
        val file = stage("x").also { it.writeBytes(ByteArray(11 * 1024 * 1024) { i -> (i % 251).toByte() }) }

        assertEquals(ListenableWorker.Result.success(), run(input(file, path = "/big.bin")))

        assertEquals(11 * 1024 * 1024, env.nextcloud.bytes("/big.bin")?.size)
    }

    @Test
    fun cancellingAnAccountsUploadsStopsQueuedWork() {
        val file = stage()
        env.nextcloud.forcedStatus = 503
        val id = UploadDocumentWorker.enqueue(app, UploadRequest(env.accountName, "/queued.txt", file, "text/plain", null))
        val work = WorkManager.getInstance(app)

        UploadDocumentWorker.cancelAll(app, env.accountName)

        awaitTrue("the work to be cancelled") { work.getWorkInfoById(id).get()?.state == WorkInfo.State.CANCELLED }
    }
}
