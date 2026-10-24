package com.aether.nextcloud.core

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }
    })
}

/** Maps non-success status codes shared by every Nextcloud endpoint to a domain error. */
fun Response.toError(): AetherError = when (code) {
    // Only 401 means the credentials are bad; 403 is a permission on one resource and must not look like a sign-out.
    401 -> AetherError.Unauthorized
    404 -> AetherError.NotFound
    412 -> AetherError.PreconditionFailed
    423 -> AetherError.Locked
    507 -> AetherError.InsufficientStorage
    else -> AetherError.Http(code, message.ifBlank { null })
}

/**
 * Runs [request] and hands the response to [block] on [Dispatchers.IO]; the response is always closed.
 * I/O failures surface as [AetherError.Network]; cancellation still propagates as an exception.
 */
suspend fun <T> OkHttpClient.execute(
    request: Request,
    block: suspend (Response) -> AetherResult<T>,
): AetherResult<T> = withContext(Dispatchers.IO) {
    try {
        newCall(request).await().use { block(it) }
    } catch (e: IOException) {
        AetherResult.Failure(AetherError.Network(e))
    }
}
