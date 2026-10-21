package com.aether.nextcloud.core

import java.io.IOException

sealed interface AetherResult<out T> {
    data class Success<T>(val value: T) : AetherResult<T>
    data class Failure(val error: AetherError) : AetherResult<Nothing>
}

sealed interface AetherError {
    data class Network(val cause: IOException) : AetherError
    data class Http(val code: Int, val message: String?) : AetherError
    data object Unauthorized : AetherError
    data object NotFound : AetherError
    data object PreconditionFailed : AetherError
    data object Timeout : AetherError
    data class Malformed(val detail: String) : AetherError
    data class InvalidServer(val detail: String) : AetherError
}

inline fun <T, R> AetherResult<T>.map(transform: (T) -> R): AetherResult<R> = when (this) {
    is AetherResult.Success -> AetherResult.Success(transform(value))
    is AetherResult.Failure -> this
}

inline fun <T, R> AetherResult<T>.flatMap(transform: (T) -> AetherResult<R>): AetherResult<R> = when (this) {
    is AetherResult.Success -> transform(value)
    is AetherResult.Failure -> this
}

fun <T> AetherResult<T>.getOrNull(): T? = (this as? AetherResult.Success)?.value

fun AetherError.describe(): String = when (this) {
    is AetherError.Network -> cause.message ?: "Network error"
    is AetherError.Http -> "Server returned HTTP $code" + (message?.let { ": $it" } ?: "")
    AetherError.Unauthorized -> "Authentication failed"
    AetherError.NotFound -> "Not found"
    AetherError.PreconditionFailed -> "The file changed on the server"
    AetherError.Timeout -> "Timed out"
    is AetherError.Malformed -> "Unexpected server response: $detail"
    is AetherError.InvalidServer -> detail
}
