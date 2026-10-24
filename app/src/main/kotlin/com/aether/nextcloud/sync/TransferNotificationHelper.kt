package com.aether.nextcloud.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import com.aether.nextcloud.MainActivity
import com.aether.nextcloud.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Builds the `FOREGROUND_SERVICE_DATA_SYNC` progress notifications and conflict alerts (ADR-0007). */
@Singleton
class TransferNotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    private fun ensureChannels() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TRANSFERS, context.getString(R.string.notification_channel_transfers), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CONFLICTS, context.getString(R.string.notification_channel_conflicts), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** [total] ≤ 0 renders an indeterminate bar. */
    fun foregroundInfo(workId: UUID, title: String, transferred: Long = 0, total: Long = -1): ForegroundInfo {
        ensureChannels()
        val cancel = WorkManager.getInstance(context).createCancelPendingIntent(workId)
        val notification = NotificationCompat.Builder(context, CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_stat_transfer)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .setProgress(if (total > 0) 100 else 0, if (total > 0) percent(transferred, total) else 0, total <= 0)
            .addAction(0, context.getString(R.string.notification_cancel), cancel)
            .build()
        return ForegroundInfo(notificationId(workId), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** Posted under the account name as its tag, so [cancelConflicts] can remove it when the account signs out. */
    fun notifyConflict(accountName: String, originalName: String, conflictName: String) {
        ensureChannels()
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_CONFLICTS)
            .setSmallIcon(R.drawable.ic_stat_transfer)
            .setContentTitle(context.getString(R.string.notification_conflict_title))
            .setContentText(context.getString(R.string.notification_conflict_text, originalName, conflictName))
            .setStyle(NotificationCompat.BigTextStyle())
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        manager.notify(accountName, ("conflict:$conflictName").hashCode(), notification)
    }

    /** Removes the conflict alerts of [accountName]; they name its files. */
    fun cancelConflicts(accountName: String) {
        manager.activeNotifications.filter { it.tag == accountName }.forEach { manager.cancel(it.tag, it.id) }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_TRANSFERS = "transfers"
        const val CHANNEL_CONFLICTS = "conflicts"

        fun percent(transferred: Long, total: Long): Int =
            if (total <= 0) 0 else (transferred * 100 / total).toInt().coerceIn(0, 100)

        fun notificationId(workId: UUID): Int = workId.hashCode()
    }
}
