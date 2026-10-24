package com.aether.nextcloud.auth

import android.content.Context
import com.aether.nextcloud.data.AvatarRepository
import com.aether.nextcloud.data.DocumentRepository
import com.aether.nextcloud.data.QuotaRepository
import com.aether.nextcloud.data.cache.DocumentDiskCache
import com.aether.nextcloud.data.local.AetherDatabase
import com.aether.nextcloud.data.prefs.SettingsRepository
import com.aether.nextcloud.dav.NextcloudOcsClient
import com.aether.nextcloud.di.ApplicationScope
import com.aether.nextcloud.provider.DocumentId
import com.aether.nextcloud.sync.SyncScheduler
import com.aether.nextcloud.sync.TransferNotificationHelper
import com.aether.nextcloud.sync.UploadDocumentWorker
import com.aether.nextcloud.ui.share.DirectShareShortcuts
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tears down everything tied to an account (ADR-0003): best-effort app-password revocation, queued uploads, Room rows,
 * every file on disk (cached, pinned, staged, thumbnails), remembered settings and notifications that name its files.
 * Credentials are captured synchronously because the account may be gone by the time the work runs.
 */
@Singleton
class AccountCleaner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accounts: AccountRepository,
    private val documents: DocumentRepository,
    private val database: AetherDatabase,
    private val quotas: QuotaRepository,
    private val avatars: AvatarRepository,
    private val diskCache: DocumentDiskCache,
    private val settings: SettingsRepository,
    private val scheduler: SyncScheduler,
    private val notifications: TransferNotificationHelper,
    private val ocs: NextcloudOcsClient,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** Signs the account out of Android and cleans up. Safe to call for the system "Remove account" path too. */
    fun remove(accountName: String, removeFromAccountManager: Boolean = true) {
        val credentials = accounts.credentials(accountName)
        if (removeFromAccountManager) accounts.removeAccount(accountName)
        // Stop uploads first so nothing is writing into the files that are about to be deleted.
        UploadDocumentWorker.cancelAll(context, accountName)
        scope.launch {
            if (credentials != null) withTimeoutOrNull(REVOKE_TIMEOUT_MS) { ocs.revokeAppPassword(credentials) }
            documents.forgetAccount(accountName)
            withContext(Dispatchers.IO) { diskCache.forgetAccount(accountName) }
            val remembered = settings.forgetAccount(accountName)
            scheduler.applyBackup(remembered.backup)
            DirectShareShortcuts.publish(context, remembered.recentFolders.mapNotNull(DocumentId::parseOrNull))
            notifications.cancelConflicts(accountName)
            notifications.cancelSignInRequired(accountName)
            database.transferDao().deleteAccount(accountName)
            database.backedUpMediaDao().deleteAccount(accountName)
            quotas.forget(accountName)
            avatars.forget(accountName)
            accounts.notifyRootsChanged()
        }
    }

    private companion object {
        const val REVOKE_TIMEOUT_MS = 10_000L
    }
}
