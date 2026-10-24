package com.aether.nextcloud.sync

import android.app.Notification
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aether.nextcloud.support.AetherTestRule
import com.aether.nextcloud.support.awaitTrue
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferNotificationHelperTest {
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
    fun aConflictAlertHidesTheFileNamesOnTheLockScreen() {
        helper.notifyConflict(env.accountName, "report.txt", "report (Conflict 1).txt")

        awaitTrue("the conflict notification") { active(env.accountName).isNotEmpty() }
        val notification = active(env.accountName).single().notification
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        val publicText = notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue("public text must not name the file: $publicText", "report" !in publicText)
        assertTrue("the full text does", "report.txt" in notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun theSignInAlertIsOnePerAccountAndCanBeCancelled() {
        helper.notifySignInRequired(env.accountName)
        helper.notifySignInRequired(env.accountName)

        awaitTrue("the sign-in notification") { active(env.accountName).isNotEmpty() }
        assertEquals("repeated failures must not stack", 1, active(env.accountName).size)
        assertEquals(Notification.VISIBILITY_PRIVATE, active(env.accountName).single().notification.visibility)

        helper.cancelSignInRequired(env.accountName)

        awaitTrue("the notification to go") { active(env.accountName).isEmpty() }
    }

    @Test
    fun cancellingConflictsLeavesTheSignInAlertAndOtherAccountsAlone() {
        helper.notifyConflict(env.accountName, "a.txt", "a (Conflict 1).txt")
        helper.notifyConflict("someone@else", "b.txt", "b (Conflict 1).txt")
        helper.notifySignInRequired(env.accountName)
        awaitTrue("all three notifications") { manager.activeNotifications.count { it.tag == env.accountName || it.tag == "someone@else" } == 3 }

        helper.cancelConflicts(env.accountName)

        awaitTrue("the account's conflict alert to go") { active(env.accountName).size == 1 }
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
        assertNull(manager.activeNotifications.firstOrNull { it.tag == "nope" })
    }
}
