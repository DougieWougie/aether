package com.aether.nextcloud.sync

import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.aether.nextcloud.R
import com.aether.nextcloud.core.AetherError
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferNotificationHelperNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val helper = TransferNotificationHelper(context)
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val account = "alice@cloud.example.com"
    private fun active(tag: String) = manager.activeNotifications.filter { it.tag == tag }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        manager.cancelAll()
    }

    @Test
    fun aConflictAlertHidesTheFileNamesOnTheLockScreen() {
        helper.notifyConflict(account, "report.txt", "report (Conflict 1).txt")

        val notification = active(account).single().notification
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        val publicText = notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue("public text must not name the file: $publicText", "report" !in publicText)
        assertTrue("the full text does", "report.txt" in notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun anUploadFailureAlertHidesTheFileNameOnTheLockScreenAndOffersRetry() {
        helper.notifyUploadFailed(account, "/Documents/report.txt", AetherError.InsufficientStorage)

        val notification = active(account).single().notification
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue("it names the file and why: $text", "report.txt" in text && "storage is full" in text)
        assertTrue("the public version must not name the file", "report" !in notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(context.getString(R.string.notification_retry), notification.actions.single().title.toString())
    }

    @Test
    fun theSignInAlertIsOnePerAccountAndCanBeCancelled() {
        helper.notifySignInRequired(account)
        helper.notifySignInRequired(account)

        assertEquals("repeated failures must not stack", 1, active(account).size)
        assertEquals(Notification.VISIBILITY_PRIVATE, active(account).single().notification.visibility)

        helper.cancelSignInRequired(account)

        assertTrue(active(account).isEmpty())
    }

    @Test
    fun cancellingConflictsLeavesTheSignInAlertAndOtherAccountsAlone() {
        helper.notifyConflict(account, "a.txt", "a (Conflict 1).txt")
        helper.notifyConflict("someone@else", "b.txt", "b (Conflict 1).txt")
        helper.notifySignInRequired(account)
        assertEquals(3, manager.activeNotifications.count { it.tag == account || it.tag == "someone@else" })

        helper.cancelConflicts(account)

        assertEquals(1, active(account).size)
        assertEquals(1, active("someone@else").size)
        helper.cancelConflicts("someone@else")
    }

    @Test
    fun theForegroundNotificationShowsProgressAndACancelAction() {
        val info = helper.foregroundInfo(UUID.randomUUID(), "Uploading", transferred = 50, total = 100)

        val notification = info.notification
        assertEquals(100, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(50, notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(1, notification.actions.size)
        assertNotNull(notification.contentIntent)
    }

    @Test
    fun anUnknownSizeShowsAnIndeterminateBar() {
        val notification = helper.foregroundInfo(UUID.randomUUID(), "Syncing").notification

        assertEquals(true, notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }
}
