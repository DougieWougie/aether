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
    fun applyBackup(settings: BackupSettings) {
        if (settings.enabled && settings.accountName != null) {
            CameraBackupWorker.scheduleTrigger(context, settings, ExistingWorkPolicy.REPLACE)
            CameraBackupWorker.schedulePeriodic(context, settings)
        } else {
            CameraBackupWorker.cancel(context)
        }
    }

    /** Emits true only while a camera backup run is executing, not merely while backup is enabled. */
    fun observeBackupRunning(): Flow<Boolean> = CameraBackupWorker.observeRunning(context)

    fun applyPinnedSync(settings: SyncSettings) = PinnedSyncWorker.schedulePeriodic(context, settings.pinnedSyncUnmeteredOnly)

    fun syncPinsNow() = PinnedSyncWorker.syncNow(context)
}
