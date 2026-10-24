package com.aether.nextcloud.sync

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.aether.nextcloud.data.prefs.BackupSettings
import com.aether.nextcloud.data.prefs.SyncSettings
import com.aether.nextcloud.support.awaitTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val scheduler = SyncScheduler(context)
    private val work get() = WorkManager.getInstance(context)

    private fun infos(name: String) = work.getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }

    @After
    fun cancelEverything() {
        CameraBackupWorker.cancel(context)
        work.cancelUniqueWork("pinned-sync-periodic")
        work.cancelUniqueWork("pinned-sync-now")
    }

    @Test
    fun enabledBackupWithAnAccountSchedulesTheTriggerAndThePeriodicSweep() {
        scheduler.applyBackup(BackupSettings(enabled = true, accountName = "alice@h"))

        assertEquals(1, infos("camera-backup-trigger").size)
        assertEquals(1, infos("camera-backup-periodic").size)
    }

    @Test
    fun theUnmeteredSettingBecomesANetworkConstraint() {
        scheduler.applyBackup(BackupSettings(enabled = true, accountName = "alice@h", unmeteredOnly = true, chargingOnly = true))

        val constraints = infos("camera-backup-periodic").single().constraints
        assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresCharging())

        scheduler.applyBackup(BackupSettings(enabled = true, accountName = "alice@h", unmeteredOnly = false, chargingOnly = false))
        val relaxed = infos("camera-backup-periodic").single().constraints
        assertEquals(NetworkType.CONNECTED, relaxed.requiredNetworkType)
        assertFalse(relaxed.requiresCharging())
    }

    @Test
    fun disablingBackupOrLosingTheAccountCancelsTheWork() {
        scheduler.applyBackup(BackupSettings(enabled = true, accountName = "alice@h"))

        scheduler.applyBackup(BackupSettings(enabled = true, accountName = null))

        awaitTrue("backup work to be cancelled") { infos("camera-backup-trigger").isEmpty() && infos("camera-backup-periodic").isEmpty() }
    }

    @Test
    fun theBackupIsNotReportedAsRunningWhileItOnlyWaitsForATrigger() {
        scheduler.applyBackup(BackupSettings(enabled = true, accountName = "alice@h"))

        assertFalse(runBlocking { scheduler.observeBackupRunning().first() })
    }

    @Test
    fun pinnedSyncRunsPeriodicallyAndFollowsTheUnmeteredSetting() {
        scheduler.applyPinnedSync(SyncSettings(pinnedSyncUnmeteredOnly = true))
        assertEquals(NetworkType.UNMETERED, infos("pinned-sync-periodic").single().constraints.requiredNetworkType)

        scheduler.applyPinnedSync(SyncSettings(pinnedSyncUnmeteredOnly = false))

        val request = infos("pinned-sync-periodic").single()
        assertEquals("re-applying updates in place, not a second job", 1, infos("pinned-sync-periodic").size)
        assertEquals(NetworkType.CONNECTED, request.constraints.requiredNetworkType)
        assertTrue(request.constraints.requiresBatteryNotLow())
    }

    @Test
    fun syncNowQueuesOneExpeditedRunEvenWhenTappedTwice() {
        scheduler.syncPinsNow()
        scheduler.syncPinsNow()

        val queued = work.getWorkInfosForUniqueWork("pinned-sync-now").get()
        assertEquals(1, queued.size)
        assertTrue(queued.single().state == WorkInfo.State.ENQUEUED || queued.single().state == WorkInfo.State.RUNNING || queued.single().state.isFinished)
    }
}
