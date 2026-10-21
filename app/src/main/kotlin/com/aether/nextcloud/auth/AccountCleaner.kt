package com.aether.nextcloud.auth

import com.aether.nextcloud.data.DocumentRepository
import com.aether.nextcloud.data.QuotaRepository
import com.aether.nextcloud.data.local.AetherDatabase
import com.aether.nextcloud.dav.NextcloudOcsClient
import com.aether.nextcloud.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tears down everything tied to an account (ADR-0003): best-effort app-password revocation, Room rows, cached and
 * pinned bytes. Credentials are captured synchronously because the account may be gone by the time the work runs.
 */
@Singleton
class AccountCleaner @Inject constructor(
    private val accounts: AccountRepository,
    private val documents: DocumentRepository,
    private val database: AetherDatabase,
    private val quotas: QuotaRepository,
    private val ocs: NextcloudOcsClient,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** Signs the account out of Android and cleans up. Safe to call for the system "Remove account" path too. */
    fun remove(accountName: String, removeFromAccountManager: Boolean = true) {
        val credentials = accounts.credentials(accountName)
        if (removeFromAccountManager) accounts.removeAccount(accountName)
        scope.launch {
            if (credentials != null) withTimeoutOrNull(REVOKE_TIMEOUT_MS) { ocs.revokeAppPassword(credentials) }
            documents.forgetAccount(accountName)
            database.transferDao().deleteAccount(accountName)
            database.backedUpMediaDao().deleteAccount(accountName)
            quotas.forget(accountName)
            accounts.notifyRootsChanged()
        }
    }

    private companion object {
        const val REVOKE_TIMEOUT_MS = 10_000L
    }
}
