package com.aether.nextcloud.dav

import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.core.AetherError
import com.aether.nextcloud.core.AetherResult
import com.aether.nextcloud.core.execute
import com.aether.nextcloud.core.toError
import java.io.File
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class UserInfo(
    val displayName: String,
    val usedBytes: Long,
    /** Bytes still available to the user; null when the server did not report it. */
    val freeBytes: Long?,
    /** Quota ceiling in bytes; null when the user has unlimited quota. */
    val totalBytes: Long?,
) {
    val isUnlimited: Boolean get() = totalBytes == null
}

@Serializable
private data class OcsEnvelope(val ocs: Ocs) {
    @Serializable
    data class Ocs(val data: Data) {
        @Serializable
        data class Data(
            val id: String = "",
            @kotlinx.serialization.SerialName("display-name") val displayName: String? = null,
            val quota: Quota = Quota(),
        )

        @Serializable
        data class Quota(
            val free: Long? = null,
            val used: Long = 0,
            val total: Long? = null,
            /** A byte count, or -3 / "none" when unlimited depending on server version. */
            val quota: JsonElement? = null,
        )
    }
}

/** OCS (JSON) endpoints and thumbnail previews. */
class NextcloudOcsClient(
    private val client: OkHttpClient,
    private val userAgent: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun request(credentials: AetherCredentials, url: String) =
        Request.Builder().url(url).authenticated(credentials, userAgent).header("OCS-APIRequest", "true")

    /** `GET /ocs/v2.php/cloud/user?format=json` */
    suspend fun userInfo(credentials: AetherCredentials): AetherResult<UserInfo> {
        val request = request(credentials, "${credentials.serverUrl}/ocs/v2.php/cloud/user?format=json")
            .header("Accept", "application/json")
            .get()
            .build()
        return client.execute(request) { response ->
            if (!response.isSuccessful) return@execute AetherResult.Failure(response.toError())
            try {
                val data = json.decodeFromString<OcsEnvelope>(response.body.string()).ocs.data
                val quota = data.quota
                val unlimited = quota.quota.isUnlimited() || quota.total == null || quota.total <= 0
                AetherResult.Success(
                    UserInfo(
                        displayName = data.displayName ?: data.id,
                        usedBytes = quota.used,
                        freeBytes = quota.free?.takeIf { it >= 0 },
                        totalBytes = if (unlimited) null else quota.total,
                    ),
                )
            } catch (e: SerializationException) {
                AetherResult.Failure(AetherError.Malformed(e.message ?: "invalid JSON"))
            }
        }
    }

    /** Best-effort revocation of this device's app password (`DELETE /ocs/v2.php/core/apppassword`). */
    suspend fun revokeAppPassword(credentials: AetherCredentials): AetherResult<Unit> {
        val request = request(credentials, "${credentials.serverUrl}/ocs/v2.php/core/apppassword").delete().build()
        return client.execute(request) { response ->
            if (response.isSuccessful) AetherResult.Success(Unit) else AetherResult.Failure(response.toError())
        }
    }

    /** `<server>/index.php/core/preview?fileId=<id>&x=256&y=256&a=1` */
    fun previewUrl(credentials: AetherCredentials, fileId: String, size: Int = PREVIEW_SIZE): String =
        "${credentials.serverUrl}/index.php/core/preview".toHttpUrl().newBuilder()
            .addQueryParameter("fileId", fileId)
            .addQueryParameter("x", size.toString())
            .addQueryParameter("y", size.toString())
            .addQueryParameter("a", "1")
            .build()
            .toString()

    suspend fun downloadPreview(credentials: AetherCredentials, fileId: String, target: File, size: Int = PREVIEW_SIZE): AetherResult<Unit> {
        val request = request(credentials, previewUrl(credentials, fileId, size)).get().build()
        return client.execute(request) { response ->
            if (!response.isSuccessful) return@execute AetherResult.Failure(response.toError())
            try {
                target.parentFile?.mkdirs()
                target.outputStream().use { out -> response.body.byteStream().copyTo(out) }
                AetherResult.Success(Unit)
            } catch (e: IOException) {
                target.delete()
                AetherResult.Failure(AetherError.Network(e))
            }
        }
    }

    /**
     * `GET <server>/index.php/avatar/<user>/<size>`. Nextcloud answers with a generated letter avatar when the user
     * has not set a picture, so only a custom one is saved: the result is `true` if [target] now holds it, `false` if
     * the user has none (any stale [target] is removed).
     */
    suspend fun downloadAvatar(credentials: AetherCredentials, target: File, size: Int = AVATAR_SIZE): AetherResult<Boolean> {
        val url = "${credentials.serverUrl}/index.php/avatar".toHttpUrl().newBuilder()
            .addPathSegment(credentials.loginName)
            .addPathSegment(size.toString())
            .build()
        val request = request(credentials, url.toString()).get().build()
        return client.execute(request) { response ->
            if (response.code == 404) {
                target.delete()
                return@execute AetherResult.Success(false)
            }
            if (!response.isSuccessful) return@execute AetherResult.Failure(response.toError())
            // Newer servers flag custom avatars; older ones answer 201 for generated ones and 200 for uploaded ones.
            val custom = response.header("X-NC-IsCustomAvatar")?.let { it == "1" } ?: (response.code == 200)
            if (!custom) {
                target.delete()
                return@execute AetherResult.Success(false)
            }
            try {
                target.parentFile?.mkdirs()
                target.outputStream().use { out -> response.body.byteStream().copyTo(out) }
                AetherResult.Success(true)
            } catch (e: IOException) {
                target.delete()
                AetherResult.Failure(AetherError.Network(e))
            }
        }
    }

    private fun JsonElement?.isUnlimited(): Boolean {
        val primitive = this as? JsonPrimitive ?: return false
        return primitive.content == "none" || primitive.content == "-3"
    }

    companion object {
        const val PREVIEW_SIZE = 256
        const val AVATAR_SIZE = 192
    }
}
