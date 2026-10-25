package com.aether.nextcloud.sync

import android.content.Context
import androidx.work.ExistingWorkPolicy
import com.aether.nextcloud.data.prefs.BackupSettings
import com.aether.nextcloud.data.prefs.SyncSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

/** Translates user settings into WorkManager schedules. */
@Singleton
class SyncScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    /** Schedules camera backup, or cancels it (including a run in progress) when it is off or paused. */
    fun applyBackup(settings: BackupSettings) {
        if (settings.enabled && !settings.paused && settings.accountName != null) {
            CameraBackupWorker.scheduleTrigger(context, settings, ExistingWorkPolicy.REPLACE)
            CameraBackupWorker.schedulePeriodic(context, settings)
        } else {
            CameraBackupWorker.cancel(context)
        }
    }

    /** Emits true only while a camera backup run is executing, not merely while backup is enabled. */
    fun observeBackupRunning(): Flow<Boolean> = CameraBackupWorker.observeRunning(context)

    fun applyPinnedSync(settings: SyncSettings) = PinnedSyncWorker.schedulePeriodic(context, settings.pinnedSyncUnmeteredOnly)

    fun backUpNow() {
        CameraBackupWorker.runNow(context)
    }

    /** Runs a backup scan now and waits for it: how many items it tried to upload, or null if the run did not succeed. */
    suspend fun backUpNowAndCount(): Int? = CameraBackupWorker.runNowAndCount(context)

    fun canReadMedia(): Boolean = CameraBackupWorker.hasMediaPermission(context)

    fun syncPinsNow() = PinnedSyncWorker.syncNow(context)
}
