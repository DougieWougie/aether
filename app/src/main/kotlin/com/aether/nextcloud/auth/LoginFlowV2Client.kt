package com.aether.nextcloud.auth

import com.aether.nextcloud.core.AetherError
import com.aether.nextcloud.core.AetherResult
import com.aether.nextcloud.core.execute
import com.aether.nextcloud.core.toError
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What the caller needs to open the browser and later resume polling (survives process death via SavedStateHandle). */
data class LoginSession(val loginUrl: String, val pollEndpoint: String, val pollToken: String)

data class LoginCredentials(val server: String, val loginName: String, val appPassword: String)

sealed interface PollOutcome {
    data object Pending : PollOutcome
    data class Authenticated(val credentials: LoginCredentials) : PollOutcome
}

@Serializable
private data class LoginInitResponse(val poll: PollInfo, val login: String) {
    @Serializable
    data class PollInfo(val token: String, val endpoint: String)
}

@Serializable
private data class LoginPollResponse(val server: String, val loginName: String, val appPassword: String)

class LoginFlowV2Client(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val allowInsecure: Boolean = false,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Starts Login Flow v2: `POST <server>/index.php/login/v2`. */
    suspend fun start(serverInput: String): AetherResult<LoginSession> {
        val server = when (val normalized = normalizeServerUrl(serverInput, allowInsecure)) {
            is AetherResult.Success -> normalized.value
            is AetherResult.Failure -> return normalized
        }
        val request = Request.Builder()
            .url("$server/index.php/login/v2")
            .header("User-Agent", userAgent)
            .post(ByteArray(0).toRequestBody())
            .build()
        return client.execute(request) { response ->
            if (response.code != 200) return@execute AetherResult.Failure(response.toError())
            parse<LoginInitResponse, LoginSession>(response.body.string()) {
                LoginSession(loginUrl = it.login, pollEndpoint = it.poll.endpoint, pollToken = it.poll.token)
            }
        }
    }

    /** One poll: 404 means the user has not finished authenticating, 200 carries the app password. */
    suspend fun poll(pollEndpoint: String, pollToken: String): AetherResult<PollOutcome> {
        val request = Request.Builder()
            .url(pollEndpoint)
            .header("User-Agent", userAgent)
            .post(FormBody.Builder().add("token", pollToken).build())
            .build()
        return client.execute(request) { response ->
            when (response.code) {
                404 -> AetherResult.Success(PollOutcome.Pending)
                200 -> parse<LoginPollResponse, PollOutcome>(response.body.string()) {
                    PollOutcome.Authenticated(LoginCredentials(it.server, it.loginName, it.appPassword))
                }
                else -> AetherResult.Failure(response.toError())
            }
        }
    }

    /** Polls every [intervalMs] until the user finishes, an error occurs, or [timeoutMs] elapses. */
    suspend fun awaitCredentials(
        pollEndpoint: String,
        pollToken: String,
        intervalMs: Long = POLL_INTERVAL_MS,
        timeoutMs: Long = FLOW_TIMEOUT_MS,
    ): AetherResult<LoginCredentials> =
        withTimeoutOrNull(timeoutMs) { pollUntilDone(pollEndpoint, pollToken, intervalMs) }
            ?: AetherResult.Failure(AetherError.Timeout)

    /**
     * Network errors are expected while the app sits behind the browser (Android may suspend its connectivity),
     * so they are retried; only [MAX_CONSECUTIVE_NETWORK_ERRORS] in a row, or a non-network error, end the flow.
     */
    private suspend fun pollUntilDone(
        pollEndpoint: String,
        pollToken: String,
        intervalMs: Long,
    ): AetherResult<LoginCredentials> {
        var networkErrors = 0
        while (true) {
            when (val outcome = poll(pollEndpoint, pollToken)) {
                is AetherResult.Failure -> {
                    if (outcome.error !is AetherError.Network || ++networkErrors >= MAX_CONSECUTIVE_NETWORK_ERRORS) return outcome
                    delay(intervalMs)
                }
                is AetherResult.Success -> {
                    networkErrors = 0
                    when (val value = outcome.value) {
                        PollOutcome.Pending -> delay(intervalMs)
                        is PollOutcome.Authenticated -> return AetherResult.Success(value.credentials)
                    }
                }
            }
        }
    }

    private inline fun <reified T, R> parse(body: String, transform: (T) -> R): AetherResult<R> = try {
        AetherResult.Success(transform(json.decodeFromString<T>(body)))
    } catch (e: SerializationException) {
        AetherResult.Failure(AetherError.Malformed(e.message ?: "invalid JSON"))
    }

    companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val MAX_CONSECUTIVE_NETWORK_ERRORS = 30
        /** Nextcloud invalidates a login flow token after 20 minutes. */
        const val FLOW_TIMEOUT_MS = 20 * 60 * 1_000L

        /**
         * Trims input, defaults to https, upgrades plain http unless [allowInsecure], and strips
         * trailing slashes and `/index.php` so callers can append paths.
         */
        fun normalizeServerUrl(input: String, allowInsecure: Boolean = false): AetherResult<String> {
            var value = input.trim()
            if (value.isEmpty()) return AetherResult.Failure(AetherError.InvalidServer("Enter your Nextcloud server address"))
            value = when {
                value.startsWith("https://", ignoreCase = true) -> value
                value.startsWith("http://", ignoreCase = true) ->
                    if (allowInsecure) value else "https://" + value.substring("http://".length)
                else -> "https://$value"
            }
            value = value.trimEnd('/').removeSuffix("/index.php").trimEnd('/')
            val url = value.toHttpUrlOrNull()
                ?: return AetherResult.Failure(AetherError.InvalidServer("That doesn't look like a valid server address"))
            if (url.query != null || url.fragment != null) {
                return AetherResult.Failure(AetherError.InvalidServer("Remove query parameters from the server address"))
            }
            return AetherResult.Success(value)
        }
    }
}
