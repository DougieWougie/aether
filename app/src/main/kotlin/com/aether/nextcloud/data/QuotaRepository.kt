package com.aether.nextcloud.data

import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.core.AetherResult
import com.aether.nextcloud.dav.NextcloudOcsClient
import com.aether.nextcloud.dav.UserInfo
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory quota snapshots so Binder-thread callers (`queryRoots`) never block on the network. */
@Singleton
class QuotaRepository @Inject constructor(private val ocs: NextcloudOcsClient) {
    data class Snapshot(val info: UserInfo, val fetchedAt: Long)

    private val state = MutableStateFlow<Map<String, Snapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, Snapshot>> = state

    fun cached(accountName: String): UserInfo? = state.value[accountName]?.info

    fun isStale(accountName: String, now: Long = System.currentTimeMillis()): Boolean {
        val fetchedAt = state.value[accountName]?.fetchedAt ?: return true
        return now - fetchedAt > TTL_MS
    }

    suspend fun refresh(credentials: AetherCredentials): AetherResult<UserInfo> {
        val result = ocs.userInfo(credentials)
        if (result is AetherResult.Success) {
            val snapshot = Snapshot(result.value, System.currentTimeMillis())
            state.value = state.value + (credentials.accountName to snapshot)
        }
        return result
    }

    fun forget(accountName: String) {
        state.value = state.value - accountName
    }

    private companion object {
        const val TTL_MS = 5 * 60_000L
    }
}
