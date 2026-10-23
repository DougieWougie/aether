package com.aether.nextcloud.data

import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.core.AetherResult
import com.aether.nextcloud.data.local.AetherDatabase
import com.aether.nextcloud.dav.WebDavClientFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

/** What camera backup has uploaded: sizes by kind and, once fetched, the destination folder's size on the server. */
data class BackupStats(
    val photoCount: Int = 0,
    val photoBytes: Long = 0,
    val videoCount: Int = 0,
    val videoBytes: Long = 0,
    /** Size of the whole backup folder on the server, including anything other devices put there; null until fetched. */
    val serverBytes: Long? = null,
) {
    val totalCount: Int get() = photoCount + videoCount
    /** Bytes uploaded by this phone, from the backup log. */
    val localBytes: Long get() = photoBytes + videoBytes
}

/** Stats for the camera backup UI. */
@Singleton
class CameraBackupStatsRepository @Inject constructor(
    database: AetherDatabase,
    private val davFactory: WebDavClientFactory,
) {
    private val dao = database.backedUpMediaDao()
    private val serverBytes = MutableStateFlow<Map<String, Long>>(emptyMap())

    fun observe(accountName: String): Flow<BackupStats> =
        combine(dao.observeTotals(accountName), serverBytes) { totals, server ->
            val photos = totals.firstOrNull { !it.isVideo }
            val videos = totals.firstOrNull { it.isVideo }
            BackupStats(
                photoCount = photos?.count ?: 0,
                photoBytes = photos?.bytes ?: 0,
                videoCount = videos?.count ?: 0,
                videoBytes = videos?.bytes ?: 0,
                serverBytes = server[accountName],
            )
        }

    /** Asks the server how big [folder] is; keeps the last answer when the folder does not exist yet or the request fails. */
    suspend fun refreshServerSize(credentials: AetherCredentials, folder: String) {
        val result = davFactory.forAccount(credentials).propfind(credentials.fileUrl(folder.trimEnd('/') + "/"), depth = 0)
        val size = (result as? AetherResult.Success)?.value?.firstOrNull()?.size ?: return
        serverBytes.update { it + (credentials.accountName to size) }
    }
}
