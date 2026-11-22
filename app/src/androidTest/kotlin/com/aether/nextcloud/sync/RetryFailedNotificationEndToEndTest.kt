package com.aether.nextcloud.sync

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aether.nextcloud.core.AetherError
import com.aether.nextcloud.data.local.entity.TransferDirection
import com.aether.nextcloud.data.local.entity.TransferEntity
import com.aether.nextcloud.data.local.entity.TransferState
import com.aether.nextcloud.support.AetherTestRule
import com.aether.nextcloud.support.awaitTrue
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end check that tapping the notification's Retry action invokes RetryFailedReceiver -> WorkManager. */
@RunWith(AndroidJUnit4::class)
class RetryFailedNotificationEndToEndTest {
    @get:Rule val env = AetherTestRule()

    private val helper get() = env.graph.notifications()
    private val manager get() = env.context.getSystemService(NotificationManager::class.java)
    private fun active(tag: String) = manager.activeNotifications.filter { it.tag == tag }

    @Before
    fun allowNotifications() {
        runCatching { InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(env.context.packageName, "android.permission.POST_NOTIFICATIONS") }
        manager.cancelAll()
    }

    @Test
    fun theRetryActionSendsTheFailedUploadAgainAndClearsTheAlert() {
        val staged = File.createTempFile("failed", ".tmp", env.context.cacheDir).apply { writeText("kept bytes") }
        val transfers = env.graph.database().transferDao()
        transfers.insert(
            TransferEntity(
                accountName = env.accountName, remotePath = "/retry.txt", direction = TransferDirection.UPLOAD,
                state = TransferState.FAILED, createdAt = 1, sourcePath = staged.absolutePath, mimeType = "text/plain",
            ),
        )
        helper.notifyUploadFailed(env.accountName, "/retry.txt", AetherError.InsufficientStorage)
        awaitTrue("the failure notification") { active(env.accountName).isNotEmpty() }

        active(env.accountName).single().notification.actions.single().actionIntent.send()

        awaitTrue("the kept bytes to reach the server", timeoutMs = 30_000) { env.nextcloud.text("/retry.txt") == "kept bytes" }
        awaitTrue("the alert to go") { active(env.accountName).isEmpty() }
    }
}
