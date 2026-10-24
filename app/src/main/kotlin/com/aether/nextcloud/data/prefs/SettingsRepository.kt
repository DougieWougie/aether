package com.aether.nextcloud.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aether.nextcloud.provider.DocumentId
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** A MediaStore album (bucket) chosen for camera backup; the name is kept so it can be shown without reading MediaStore. */
@Serializable
data class BackupAlbum(val id: Long, val name: String)

data class BackupSettings(
    val enabled: Boolean = false,
    val accountName: String? = null,
    val remoteFolder: String = DEFAULT_FOLDER,
    val unmeteredOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val includeVideos: Boolean = true,
    /** Only media added after this moment (epoch millis) is uploaded, so enabling backup never floods the server with the whole library. */
    val backupSinceMillis: Long = 0,
    /** Albums to back up; null means every album, including ones created later. */
    val albums: List<BackupAlbum>? = null,
) {
    /** These settings after [accountName] signed out: backup that targeted it is switched off rather than left pointing nowhere. */
    fun withoutAccount(accountName: String): BackupSettings =
        if (this.accountName == accountName) copy(enabled = false, accountName = null) else this

    companion object {
        const val DEFAULT_FOLDER = "/Photos/Camera Uploads"
    }
}

data class SyncSettings(
    /** Periodic offline-pin sync only runs on unmetered networks. */
    val pinnedSyncUnmeteredOnly: Boolean = true,
)

/** User preferences and sync constraints (DataStore). */
@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val store get() = context.settingsStore

    val backup: Flow<BackupSettings> = store.data.map { p ->
        BackupSettings(
            enabled = p[BACKUP_ENABLED] ?: false,
            accountName = p[BACKUP_ACCOUNT],
            remoteFolder = p[BACKUP_FOLDER] ?: BackupSettings.DEFAULT_FOLDER,
            unmeteredOnly = p[BACKUP_UNMETERED] ?: true,
            chargingOnly = p[BACKUP_CHARGING] ?: false,
            includeVideos = p[BACKUP_VIDEOS] ?: true,
            backupSinceMillis = p[BACKUP_SINCE] ?: 0,
            albums = p[BACKUP_ALBUMS]?.let { runCatching { Json.decodeFromString<List<BackupAlbum>>(it) }.getOrNull() },
        )
    }

    val sync: Flow<SyncSettings> = store.data.map { p ->
        SyncSettings(pinnedSyncUnmeteredOnly = p[PINNED_UNMETERED] ?: true)
    }

    /** Applies [transform] and returns the stored result. Turning backup on stamps [BackupSettings.backupSinceMillis]. */
    suspend fun updateBackup(transform: (BackupSettings) -> BackupSettings): BackupSettings {
        val before = backup.first()
        var after = transform(before)
        if (after.enabled && !before.enabled) after = after.copy(backupSinceMillis = System.currentTimeMillis())
        store.edit { p ->
            p[BACKUP_ENABLED] = after.enabled
            after.accountName?.let { p[BACKUP_ACCOUNT] = it } ?: p.remove(BACKUP_ACCOUNT)
            p[BACKUP_FOLDER] = after.remoteFolder
            p[BACKUP_UNMETERED] = after.unmeteredOnly
            p[BACKUP_CHARGING] = after.chargingOnly
            p[BACKUP_VIDEOS] = after.includeVideos
            p[BACKUP_SINCE] = after.backupSinceMillis
            after.albums?.let { p[BACKUP_ALBUMS] = Json.encodeToString(it) } ?: p.remove(BACKUP_ALBUMS)
        }
        return after
    }

    /** Whether recent upload folders are offered as Direct Share targets. On by default; off removes them from the share sheet. */
    val directShareEnabled: Flow<Boolean> = store.data.map { p -> p[DIRECT_SHARE] ?: true }

    suspend fun setDirectShareEnabled(enabled: Boolean) {
        store.edit { it[DIRECT_SHARE] = enabled }
    }

    /** Document ids of the folders most recently used as upload destinations, newest first. */
    val recentFolders: Flow<List<String>> = store.data.map { p ->
        p[RECENT_FOLDERS]?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull() } ?: emptyList()
    }

    suspend fun rememberFolder(documentId: String) {
        store.edit { p ->
            val current = p[RECENT_FOLDERS]?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull() } ?: emptyList()
            p[RECENT_FOLDERS] = Json.encodeToString((listOf(documentId) + current.filter { it != documentId }).take(MAX_RECENT_FOLDERS))
        }
    }

    /** Accounts whose credentials the server rejected; cleared once the user signs in again (ADR-0003). */
    val authIssues: Flow<Set<String>> = store.data.map { p -> p[AUTH_ISSUES] ?: emptySet() }

    /** Records whether [accountName] needs to sign in again; true only if that changed the stored state. */
    suspend fun setAuthIssue(accountName: String, needsSignIn: Boolean): Boolean {
        var changed = false
        store.edit { p ->
            val current = p[AUTH_ISSUES] ?: emptySet()
            val updated = withAuthIssue(current, accountName, needsSignIn)
            changed = updated != current
            if (changed) p[AUTH_ISSUES] = updated
        }
        return changed
    }

    /** Forgets everything remembered for [accountName]: backup that targeted it, and recent upload folders inside it. */
    suspend fun forgetAccount(accountName: String): ForgottenAccount {
        val backup = updateBackup { it.withoutAccount(accountName) }
        var folders = emptyList<String>()
        store.edit { p ->
            p[AUTH_ISSUES]?.let { p[AUTH_ISSUES] = withAuthIssue(it, accountName, false) }
            val current = p[RECENT_FOLDERS]?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull() } ?: emptyList()
            folders = foldersWithout(current, accountName)
            p[RECENT_FOLDERS] = Json.encodeToString(folders)
        }
        return ForgottenAccount(backup, folders)
    }

    /** What is left after [forgetAccount]: the backup settings to re-apply and the recent folders to republish. */
    data class ForgottenAccount(val backup: BackupSettings, val recentFolders: List<String>)

    suspend fun updateSync(transform: (SyncSettings) -> SyncSettings): SyncSettings {
        val after = transform(sync.first())
        store.edit { it[PINNED_UNMETERED] = after.pinnedSyncUnmeteredOnly }
        return after
    }

    companion object {
        const val MAX_RECENT_FOLDERS = 4

        fun withAuthIssue(issues: Set<String>, accountName: String, needsSignIn: Boolean): Set<String> =
            if (needsSignIn) issues + accountName else issues - accountName

        /** [folders] (document ids) minus those inside [accountName]. */
        fun foldersWithout(folders: List<String>, accountName: String): List<String> =
            folders.filterNot { DocumentId.parseOrNull(it)?.accountName == accountName }

        private val AUTH_ISSUES = stringSetPreferencesKey("auth_issues")
        private val DIRECT_SHARE = booleanPreferencesKey("direct_share_enabled")
        private val RECENT_FOLDERS = stringPreferencesKey("recent_folders")
        private val BACKUP_ENABLED = booleanPreferencesKey("backup_enabled")
        private val BACKUP_ACCOUNT = stringPreferencesKey("backup_account")
        private val BACKUP_FOLDER = stringPreferencesKey("backup_folder")
        private val BACKUP_UNMETERED = booleanPreferencesKey("backup_unmetered_only")
        private val BACKUP_CHARGING = booleanPreferencesKey("backup_charging_only")
        private val BACKUP_VIDEOS = booleanPreferencesKey("backup_include_videos")
        private val BACKUP_SINCE = longPreferencesKey("backup_since")
        private val BACKUP_ALBUMS = stringPreferencesKey("backup_albums")
        private val PINNED_UNMETERED = booleanPreferencesKey("pinned_unmetered_only")
    }
}
