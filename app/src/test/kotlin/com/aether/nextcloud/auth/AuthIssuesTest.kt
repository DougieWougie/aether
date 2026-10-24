package com.aether.nextcloud.auth

import com.aether.nextcloud.data.prefs.SettingsRepository
import com.aether.nextcloud.sync.TransferNotificationHelper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthIssuesTest {
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val notifications = mockk<TransferNotificationHelper>(relaxed = true)

    private fun TestScope.issues() = AuthIssues(settings, notifications, backgroundScope)

    @Test
    fun `the first report notifies the user`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { settings.setAuthIssue("alice@h", true) } returns true

        issues().report("alice@h")

        verify { notifications.notifySignInRequired("alice@h") }
    }

    @Test
    fun `repeat reports do not notify again`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { settings.setAuthIssue("alice@h", true) } returns false

        issues().report("alice@h")

        verify(exactly = 0) { notifications.notifySignInRequired(any()) }
    }

    @Test
    fun `resolving clears the flag and the notification`() = runTest(UnconfinedTestDispatcher()) {
        issues().resolve("alice@h")

        coVerify { settings.setAuthIssue("alice@h", false) }
        verify { notifications.cancelSignInRequired("alice@h") }
    }
}
