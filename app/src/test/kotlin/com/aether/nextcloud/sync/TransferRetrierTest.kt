package com.aether.nextcloud.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.data.local.entity.TransferDirection
import com.aether.nextcloud.data.local.entity.TransferEntity
import com.aether.nextcloud.data.local.entity.TransferState
import com.aether.nextcloud.support.RobolectricAetherRule
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferRetrierTest {
    @get:Rule val env = RobolectricAetherRule()

    @Test
    fun retriesStagedUploadCameraBackupAndPinnedDownloadAndSkipsMissingFile(): Unit = runBlocking {
        val scheduler = mockk<SyncScheduler>(relaxed = true)
        val retrier = TransferRetrier(env.context, env.graph.database(), scheduler)
        val dao = env.graph.database().transferDao()
        val staged = File.createTempFile("retry", ".bin", env.context.cacheDir).apply { writeText("ok") }
        val missing = File(env.context.cacheDir, "does-not-exist.bin").apply { delete() }

        // 1. Staged upload with existing file
        dao.insert(
            TransferEntity(
                accountName = env.accountName, remotePath = "/staged.txt", direction = TransferDirection.UPLOAD,
                state = TransferState.FAILED, createdAt = 1, sourcePath = staged.absolutePath, mimeType = "text/plain",
            ),
        )
        // 2. Camera backup item (mediaKey != null)
        dao.insert(
            TransferEntity(
                accountName = env.accountName, remotePath = "/Camera/1.jpg", direction = TransferDirection.UPLOAD,
                state = TransferState.FAILED, createdAt = 2, mediaKey = "image:42",
            ),
        )
        // 3. Pinned download
        dao.insert(
            TransferEntity(
                accountName = env.accountName, remotePath = "/pinned.pdf", direction = TransferDirection.DOWNLOAD,
                state = TransferState.FAILED, createdAt = 3,
            ),
        )
        // 4. Staged upload whose file is gone -> skipped
        dao.insert(
            TransferEntity(
                accountName = env.accountName, remotePath = "/gone.txt", direction = TransferDirection.UPLOAD,
                state = TransferState.FAILED, createdAt = 4, sourcePath = missing.absolutePath,
            ),
        )

        val outcome = retrier.retryFailed(env.accountName)

        assertEquals(RetryOutcome(retried = 3, skipped = 1), outcome)
        verify(exactly = 1) { scheduler.backUpNow() }
        verify(exactly = 1) { scheduler.syncPinsNow() }
        assertEquals(listOf("/gone.txt"), dao.failed(env.accountName).map { it.remotePath })
        staged.delete()
    }

    @Test
    fun retryOneHandlesSingleFailedRow(): Unit = runBlocking {
        val scheduler = mockk<SyncScheduler>(relaxed = true)
        val retrier = TransferRetrier(env.context, env.graph.database(), scheduler)
        val dao = env.graph.database().transferDao()

        val id = dao.insert(
            TransferEntity(
                accountName = env.accountName, remotePath = "/pinned.pdf", direction = TransferDirection.DOWNLOAD,
                state = TransferState.FAILED, createdAt = 1,
            ),
        )
        val row = dao.get(id)!!

        val outcome = retrier.retryOne(row)

        assertEquals(RetryOutcome(retried = 1, skipped = 0), outcome)
        verify(exactly = 1) { scheduler.syncPinsNow() }
        assertTrue(dao.failed(env.accountName).isEmpty())
    }
}
