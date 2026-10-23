package com.aether.nextcloud.ui.dashboard

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aether.nextcloud.AetherConstants
import com.aether.nextcloud.auth.AccountCleaner
import com.aether.nextcloud.auth.AccountRepository
import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.core.AetherResult
import com.aether.nextcloud.core.describe
import com.aether.nextcloud.data.AvatarRepository
import com.aether.nextcloud.data.DocumentRepository
import com.aether.nextcloud.data.QuotaRepository
import com.aether.nextcloud.data.cache.DocumentDiskCache
import com.aether.nextcloud.data.local.AetherDatabase
import com.aether.nextcloud.data.local.entity.DocumentEntity
import com.aether.nextcloud.data.local.entity.TransferEntity
import com.aether.nextcloud.data.prefs.BackupSettings
import com.aether.nextcloud.data.prefs.SettingsRepository
import com.aether.nextcloud.dav.UserInfo
import com.aether.nextcloud.provider.DocumentId
import com.aether.nextcloud.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DashboardTab { Transfers, Pinned, Camera }

data class StorageUsage(val cacheBytes: Long = 0, val pinnedBytes: Long = 0)

data class DashboardUiState(
    val accounts: List<AetherCredentials> = emptyList(),
    val selected: AetherCredentials? = null,
    val quota: UserInfo? = null,
    val quotaError: String? = null,
    val tab: DashboardTab = DashboardTab.Transfers,
    val activeTransfers: List<TransferEntity> = emptyList(),
    val recentTransfers: List<TransferEntity> = emptyList(),
    val pinned: List<DocumentEntity> = emptyList(),
    val backup: BackupSettings = BackupSettings(),
    val backedUpCount: Int = 0,
    val lastBackupAt: Long? = null,
    val backupRunning: Boolean = false,
    val storage: StorageUsage = StorageUsage(),
    val avatar: ImageBitmap? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val accountRepository: AccountRepository,
    private val accountCleaner: AccountCleaner,
    private val quotaRepository: QuotaRepository,
    private val avatars: AvatarRepository,
    private val documents: DocumentRepository,
    private val diskCache: DocumentDiskCache,
    private val settings: SettingsRepository,
    private val scheduler: SyncScheduler,
    database: AetherDatabase,
) : ViewModel() {

    private val selectedName = MutableStateFlow(savedState.get<String>(KEY_ACCOUNT))
    private val tab = MutableStateFlow(savedState.get<String>(KEY_TAB)?.let { name -> DashboardTab.entries.firstOrNull { it.name == name } } ?: DashboardTab.Transfers)
    private val storage = MutableStateFlow(StorageUsage())

    private val selected = combine(accountRepository.observeAccounts(), selectedName) { accounts, name ->
        accounts to (accounts.firstOrNull { it.accountName == name } ?: accounts.firstOrNull())
    }

    private val transferData = combine(
        database.transferDao().observeActive(),
        database.transferDao().observeRecent(RECENT_TRANSFERS),
        database.documentDao().observePinned(),
    ) { active, recent, pinned -> Triple(active, recent, pinned) }

    private val backupData = selected.flatMapLatest { (_, account) ->
        if (account == null) {
            flowOf(Triple(0, null as Long?, BackupSettings()))
        } else {
            combine(
                database.backedUpMediaDao().observeCount(account.accountName),
                database.backedUpMediaDao().observeLastBackupAt(account.accountName),
                settings.backup,
            ) { count, last, backup -> Triple(count, last, backup) }
        }
    }

    private val message = MutableStateFlow<String?>(null)
    private val avatar = MutableStateFlow<Pair<String, ImageBitmap?>?>(null)

    val state: StateFlow<DashboardUiState> = combine(
        selected, tab, transferData, backupData,
        combine(quotaRepository.snapshots, storage, message, avatar, scheduler.observeBackupRunning(), ::Extras),
    ) { (accounts, account), currentTab, (active, recent, pinned), (count, last, backup), extras ->
        DashboardUiState(
            accounts = accounts,
            selected = account,
            quota = account?.let { extras.quotas[it.accountName]?.info },
            quotaError = extras.message,
            tab = currentTab,
            activeTransfers = active.filter { it.accountName == account?.accountName },
            recentTransfers = recent.filter { it.accountName == account?.accountName },
            pinned = pinned.filter { it.accountName == account?.accountName },
            backup = backup,
            backedUpCount = count,
            lastBackupAt = last,
            backupRunning = extras.backupRunning,
            storage = extras.usage,
            avatar = extras.avatar?.takeIf { it.first == account?.accountName }?.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    init {
        refresh()
    }

    fun selectAccount(accountName: String) {
        savedState[KEY_ACCOUNT] = accountName
        selectedName.value = accountName
        refresh()
    }

    fun selectTab(value: DashboardTab) {
        savedState[KEY_TAB] = value.name
        tab.value = value
    }

    /** Refreshes quota (for the ring) and on-disk usage. */
    fun refresh() {
        viewModelScope.launch {
            val account = selected.first().second ?: return@launch
            message.value = null
            if (avatar.value?.first != account.accountName) avatar.value = account.accountName to avatars.cached(account.accountName)
            avatar.value = account.accountName to avatars.refresh(account)
            when (val result = quotaRepository.refresh(account)) {
                is AetherResult.Failure -> message.value = result.error.describe()
                is AetherResult.Success -> accountRepository.setDisplayName(account.accountName, result.value.displayName)
            }
            refreshStorage()
        }
    }

    fun unpin(document: DocumentEntity) {
        viewModelScope.launch {
            documents.setPinned(DocumentId.parse(document.documentId), false)
            refreshStorage()
        }
    }

    fun syncPinsNow() = scheduler.syncPinsNow()

    /**
     * Pins whatever the user picked in the system document picker (tree URIs pin a whole folder). URIs from other
     * providers are ignored. Pinned items are downloaded by an immediate sync.
     */
    fun pin(uris: List<Uri>) {
        viewModelScope.launch {
            var pinnedAny = false
            for (uri in uris) {
                if (uri.authority != AetherConstants.AUTHORITY) continue
                val rawId = if (DocumentsContract.isTreeUri(uri) && uri.pathSegments.size <= 2) DocumentsContract.getTreeDocumentId(uri)
                else DocumentsContract.getDocumentId(uri)
                val id = DocumentId.parseOrNull(rawId) ?: continue
                val credentials = accountRepository.credentials(id.accountName) ?: continue
                if (documents.refreshDocument(credentials, id) is AetherResult.Success) {
                    documents.setPinned(id, true)
                    pinnedAny = true
                }
            }
            if (pinnedAny) scheduler.syncPinsNow()
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { diskCache.clearCache() }
            refreshStorage()
        }
    }

    fun signOut(accountName: String) = accountCleaner.remove(accountName)

    private suspend fun refreshStorage() {
        storage.update { withContext(Dispatchers.IO) { StorageUsage(diskCache.cacheSizeBytes(), diskCache.pinnedSizeBytes()) } }
    }

    private class Extras(
        val quotas: Map<String, QuotaRepository.Snapshot>,
        val usage: StorageUsage,
        val message: String?,
        val avatar: Pair<String, ImageBitmap?>?,
        val backupRunning: Boolean,
    )

    private companion object {
        const val KEY_ACCOUNT = "account"
        const val KEY_TAB = "tab"
        const val RECENT_TRANSFERS = 10
    }
}
