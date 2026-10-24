package com.aether.nextcloud.data

import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.core.AetherError
import com.aether.nextcloud.core.AetherResult
import com.aether.nextcloud.data.local.AetherDatabase
import com.aether.nextcloud.data.local.dao.BackedUpMediaDao
import com.aether.nextcloud.data.local.dao.MediaTotals
import com.aether.nextcloud.dav.WebDavClient
import com.aether.nextcloud.dav.WebDavClientFactory
import com.aether.nextcloud.dav.model.DavResource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraBackupStatsRepositoryTest {
    private val totals = MutableStateFlow<List<MediaTotals>>(emptyList())
    private val dao = mockk<BackedUpMediaDao> { every { observeTotals("alice@h") } returns totals }
    private val database = mockk<AetherDatabase> { every { backedUpMediaDao() } returns dao }
    private val dav = mockk<WebDavClient>()
    private val factory = mockk<WebDavClientFactory>()
    private val alice = AetherCredentials("alice@h", "https://h", "alice", "pw", "Alice")
    private val repository = CameraBackupStatsRepository(database, factory)

    init {
        every { factory.forAccount(alice) } returns dav
    }

    private fun folder(size: Long?) = DavResource(href = "/remote.php/dav/files/alice/Photos/", isDirectory = true, size = size)

    @Test
    fun `nothing uploaded yet means zero everywhere and no server size`() = runTest {
        assertEquals(BackupStats(), repository.observe("alice@h").first())
    }

    @Test
    fun `photos and videos are split into their own counts and sizes`() = runTest {
        totals.value = listOf(MediaTotals(isVideo = false, count = 3, bytes = 300), MediaTotals(isVideo = true, count = 1, bytes = 9_000))

        val stats = repository.observe("alice@h").first()

        assertEquals(3, stats.photoCount)
        assertEquals(300L, stats.photoBytes)
        assertEquals(1, stats.videoCount)
        assertEquals(9_000L, stats.videoBytes)
        assertEquals(4, stats.totalCount)
        assertEquals(9_300L, stats.localBytes)
    }

    @Test
    fun `only photos uploaded leaves the video side at zero`() = runTest {
        totals.value = listOf(MediaTotals(isVideo = false, count = 2, bytes = 50))

        val stats = repository.observe("alice@h").first()

        assertEquals(0, stats.videoCount)
        assertEquals(0L, stats.videoBytes)
    }

    @Test
    fun `the server folder size appears once fetched`() = runTest {
        coEvery { dav.propfind("https://h/remote.php/dav/files/alice/Photos/", depth = 0) } returns AetherResult.Success(listOf(folder(123_456)))
        assertNull(repository.observe("alice@h").first().serverBytes)

        repository.refreshServerSize(alice, "/Photos")

        assertEquals(123_456L, repository.observe("alice@h").first().serverBytes)
    }

    @Test
    fun `a failed or empty answer keeps the last known size`() = runTest {
        coEvery { dav.propfind(any(), depth = 0) } returns AetherResult.Success(listOf(folder(500)))
        repository.refreshServerSize(alice, "/Photos")

        coEvery { dav.propfind(any(), depth = 0) } returns AetherResult.Failure(AetherError.NotFound)
        repository.refreshServerSize(alice, "/Photos")
        coEvery { dav.propfind(any(), depth = 0) } returns AetherResult.Success(emptyList())
        repository.refreshServerSize(alice, "/Photos")
        coEvery { dav.propfind(any(), depth = 0) } returns AetherResult.Success(listOf(folder(null)))
        repository.refreshServerSize(alice, "/Photos")

        assertEquals(500L, repository.observe("alice@h").first().serverBytes)
    }

    @Test
    fun `a trailing slash on the folder is not doubled`() = runTest {
        coEvery { dav.propfind("https://h/remote.php/dav/files/alice/Photos/", depth = 0) } returns AetherResult.Success(listOf(folder(1)))

        repository.refreshServerSize(alice, "/Photos/")

        assertEquals(1L, repository.observe("alice@h").first().serverBytes)
    }
}
