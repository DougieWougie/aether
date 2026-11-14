package com.aether.nextcloud.core

import android.content.Context
import com.aether.nextcloud.R

/**
 * What is stored for a failed transfer: a short code, never English prose, so the text is chosen (and translated) when it
 * is shown. `http:<status>` carries the status; every other error is one fixed word. See [errorCodeText].
 */
fun AetherError.code(): String = when (this) {
    is AetherError.Network -> ErrorCodes.NETWORK
    is AetherError.Http -> "${ErrorCodes.HTTP}:$code"
    AetherError.Unauthorized -> ErrorCodes.UNAUTHORIZED
    AetherError.NotFound -> ErrorCodes.NOT_FOUND
    AetherError.PreconditionFailed -> ErrorCodes.PRECONDITION_FAILED
    AetherError.Locked -> ErrorCodes.LOCKED
    AetherError.InsufficientStorage -> ErrorCodes.INSUFFICIENT_STORAGE
    AetherError.Timeout -> ErrorCodes.TIMEOUT
    is AetherError.Malformed -> ErrorCodes.MALFORMED
    is AetherError.InvalidServer -> ErrorCodes.INVALID_SERVER
}

object ErrorCodes {
    const val NETWORK = "network"
    const val HTTP = "http"
    const val UNAUTHORIZED = "unauthorized"
    const val NOT_FOUND = "not_found"
    const val PRECONDITION_FAILED = "precondition_failed"
    const val LOCKED = "locked"
    const val INSUFFICIENT_STORAGE = "insufficient_storage"
    const val TIMEOUT = "timeout"
    const val MALFORMED = "malformed"
    const val INVALID_SERVER = "invalid_server"
    /** The user (or sign-out) stopped the transfer. */
    const val CANCELLED = "cancelled"
    /** A row from before codes were stored, whose English text could not be mapped. */
    const val UNKNOWN = "unknown"
}

/** The message for an error that has just happened, with the details (server message, cause) that a stored code leaves out. */
fun AetherError.describe(context: Context): String = when (this) {
    is AetherError.Network -> cause.message ?: context.getString(R.string.error_network)
    is AetherError.Http -> message?.let { context.getString(R.string.error_http_detail, code, it) } ?: context.getString(R.string.error_http, code)
    is AetherError.Malformed -> context.getString(R.string.error_malformed_detail, detail)
    is AetherError.InvalidServer -> detail
    else -> errorCodeText(context, code())!!
}

/** The message for a stored [code] in the current language, or null when there is none. Unknown codes read as a generic failure. */
fun errorCodeText(context: Context, code: String?): String? {
    if (code == null) return null
    if (code.startsWith("${ErrorCodes.HTTP}:")) {
        val status = code.substringAfter(':').toIntOrNull()
        if (status != null) return context.getString(R.string.error_http, status)
    }
    val res = when (code) {
        ErrorCodes.NETWORK -> R.string.error_network
        ErrorCodes.UNAUTHORIZED -> R.string.error_unauthorized
        ErrorCodes.NOT_FOUND -> R.string.error_not_found
        ErrorCodes.PRECONDITION_FAILED -> R.string.error_precondition_failed
        ErrorCodes.LOCKED -> R.string.error_locked
        ErrorCodes.INSUFFICIENT_STORAGE -> R.string.error_insufficient_storage
        ErrorCodes.TIMEOUT -> R.string.error_timeout
        ErrorCodes.MALFORMED -> R.string.error_malformed
        ErrorCodes.INVALID_SERVER -> R.string.error_invalid_server
        ErrorCodes.CANCELLED -> R.string.error_cancelled
        else -> R.string.error_unknown
    }
    return context.getString(res)
}
