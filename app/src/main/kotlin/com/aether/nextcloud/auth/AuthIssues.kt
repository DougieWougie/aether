package com.aether.nextcloud.auth

import com.aether.nextcloud.data.prefs.SettingsRepository
import com.aether.nextcloud.di.ApplicationScope
import com.aether.nextcloud.sync.TransferNotificationHelper
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Remembers which accounts the server no longer accepts (a revoked or expired app password). Background work and the
 * provider [report] it; the dashboard shows [accounts] and a notification asks the user to sign in again. The state is
 * cleared by [resolve] when they do, or when the account is removed.
 */
@Singleton
class AuthIssues @Inject constructor(
    private val settings: SettingsRepository,
    private val notifications: TransferNotificationHelper,
    @ApplicationScope private val scope: CoroutineScope,
) {
    val accounts: Flow<Set<String>> = settings.authIssues

    /** Fire and forget, so non-suspending callers (the provider) can use it. Notifies only the first time. */
    fun report(accountName: String) {
        scope.launch {
            if (settings.setAuthIssue(accountName, true)) notifications.notifySignInRequired(accountName)
        }
    }

    suspend fun resolve(accountName: String) {
        settings.setAuthIssue(accountName, false)
        notifications.cancelSignInRequired(accountName)
    }
}
