package com.aether.nextcloud.sync

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aether.nextcloud.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The Retry action of a failed-upload notification: sends the account's failed transfers again (see [TransferRetrier]). */
@AndroidEntryPoint
class RetryFailedReceiver : BroadcastReceiver() {
    @Inject lateinit var retrier: TransferRetrier

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val account = intent.getStringExtra(EXTRA_ACCOUNT) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val pending = goAsync()
        scope.launch {
            try {
                context.getSystemService(NotificationManager::class.java).cancel(account, notificationId)
                retrier.retryFailed(account)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ACCOUNT = "account"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
