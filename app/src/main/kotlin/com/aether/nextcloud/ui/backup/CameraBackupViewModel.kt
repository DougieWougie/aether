package com.aether.nextcloud.ui.backup

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aether.nextcloud.auth.AccountRepository
import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.data.BackupStats
import com.aether.nextcloud.data.CameraBackupStatsRepository
import com.aether.nextcloud.data.local.AetherDatabase
import com.aether.nextcloud.data.local.entity.BackedUpMediaEntity
import com.aether.nextcloud.data.prefs.BackupSettings
import com.aether.nextcloud.data.prefs.SettingsRepository
import com.aether.nextcloud.provider.DocumentId
import com.aether.nextcloud.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CameraBackupUiState(
    val settings: BackupSettings = BackupSettings(),
    val accounts: List<AetherCredentials> = emptyList(),
    val backedUpCount: Int = 0,
    val running: Boolean = false,
    val stats: BackupStats = BackupStats(),
)

@HiltViewModel
class CameraBackupViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val scheduler: SyncScheduler,
    private val cameraStats: CameraBackupStatsRepository,
    private val accountRepository: AccountRepository,
    database: AetherDatabase,
) : ViewModel() {

    private val backedUp = database.backedUpMediaDao()

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<CameraBackupUiState> = combine(settings.backup, accountRepository.observeAccounts()) { backup, accounts ->
        backup to accounts
    }.flatMapLatest { (backup, accounts) ->
        val account = backup.accountName ?: accounts.firstOrNull()?.accountName
        val count = if (account == null) flowOf(0) else backedUp.observeCount(account)
        val stats = if (account == null) flowOf(BackupStats()) else cameraStats.observe(account, RECENT_UPLOADS)
        combine(count, scheduler.observeBackupRunning(), stats) { n, running, s -> CameraBackupUiState(backup, accounts, n, running, s) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CameraBackupUiState())

    init {
        refreshServerSize()
    }

    /** Re-reads the backup folder's size on the server; the stats show this phone's total until it arrives. */
    fun refreshServerSize() {
        viewModelScope.launch {
            val backup = settings.backup.first()
            val account = backup.accountName ?: accountRepository.observeAccounts().first().firstOrNull()?.accountName ?: return@launch
            val credentials = accountRepository.credentials(account) ?: return@launch
            cameraStats.refreshServerSize(credentials, backup.remoteFolder)
        }
    }

    suspend fun thumbnail(item: BackedUpMediaEntity): ImageBitmap? {
        val credentials = accountRepository.credentials(item.accountName) ?: return null
        return cameraStats.thumbnail(credentials, item)
    }

    /** Call only once media permission is granted. Defaults the destination account to the first signed-in one. */
    fun setEnabled(enabled: Boolean) = update {
        val account = it.accountName ?: state.value.accounts.firstOrNull()?.accountName
        it.copy(enabled = enabled && account != null, accountName = account)
    }

    fun setAccount(accountName: String) = update { it.copy(accountName = accountName) }

    fun setFolder(folder: String) = update { it.copy(remoteFolder = DocumentId.normalizePath(folder.ifBlank { BackupSettings.DEFAULT_FOLDER })) }

    fun setUnmeteredOnly(value: Boolean) = update { it.copy(unmeteredOnly = value) }

    fun setChargingOnly(value: Boolean) = update { it.copy(chargingOnly = value) }

    fun setIncludeVideos(value: Boolean) = update { it.copy(includeVideos = value) }

    private companion object {
        const val RECENT_UPLOADS = 12
    }

    private fun update(transform: (BackupSettings) -> BackupSettings) {
        viewModelScope.launch { scheduler.applyBackup(settings.updateBackup(transform)) }
    }
}
