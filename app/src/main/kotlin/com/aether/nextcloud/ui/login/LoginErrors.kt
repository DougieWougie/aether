package com.aether.nextcloud.ui.login

import androidx.annotation.StringRes
import com.aether.nextcloud.R
import com.aether.nextcloud.core.AetherError

/** A plain-language message for failures the user can act on while signing in; null falls back to [com.aether.nextcloud.core.describe]. */
@StringRes
internal fun AetherError.loginMessageRes(): Int? = when (this) {
    AetherError.Timeout -> R.string.login_timed_out
    is AetherError.Network -> R.string.login_unreachable
    AetherError.NotFound, is AetherError.Malformed -> R.string.login_not_nextcloud
    is AetherError.Http -> if (code in 400..499) R.string.login_not_nextcloud else null
    else -> null
}
