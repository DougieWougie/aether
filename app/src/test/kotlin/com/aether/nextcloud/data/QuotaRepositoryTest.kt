package com.aether.nextcloud.data

import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.core.AetherError
import com.aether.nextcloud.core.AetherResult
import com.aether.nextcloud.dav.NextcloudOcsClient
import com.aether.nextcloud.dav.UserInfo
import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaRepositoryTest {
    private val ocs = mockk<NextcloudOcsClient>()
    private val repository = QuotaRepository(ocs)
    private val alice = AetherCredentials("alice@h", "https://h", "alice", "pw", "Alice")
    private val info = UserInfo("Alice", usedBytes = 10, freeBytes = 90, totalBytes = 100)

    @Test
    fun `nothing is cached before the first refresh and that counts as stale`() {
        assertNull(repository.cached("alice@h"))
        assertTrue(repository.isStale("alice@h"))
    }

    @Test
    fun `a successful refresh is cached and fresh for five minutes`() = runTest {
        coEvery { ocs.userInfo(alice) } returns AetherResult.Success(info)

        repository.refresh(alice)

        assertEquals(info, repository.cached("alice@h"))
        val fetchedAt = repository.snapshots.value.getValue("alice@h").fetchedAt
        assertFalse(repository.isStale("alice@h", now = fetchedAt + 4 * 60_000))
        assertTrue(repository.isStale("alice@h", now = fetchedAt + 5 * 60_000 + 1))
    }

    @Test
    fun `a failed refresh keeps the previous snapshot`() = runTest {
        coEvery { ocs.userInfo(alice) } returns AetherResult.Success(info)
        repository.refresh(alice)
        coEvery { ocs.userInfo(alice) } returns AetherResult.Failure(AetherError.Network(IOException("offline")))

        val result = repository.refresh(alice)

        assertTrue(result is AetherResult.Failure)
        assertEquals(info, repository.cached("alice@h"))
    }

    @Test
    fun `forgetting an account drops only its snapshot`() = runTest {
        val bob = alice.copy(accountName = "bob@h", loginName = "bob")
        coEvery { ocs.userInfo(any()) } returns AetherResult.Success(info)
        repository.refresh(alice)
        repository.refresh(bob)

        repository.forget("alice@h")

        assertNull(repository.cached("alice@h"))
        assertEquals(info, repository.cached("bob@h"))
    }
}
