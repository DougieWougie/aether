package com.aether.nextcloud.dav

import com.aether.nextcloud.auth.AetherCredentials
import okhttp3.Credentials
import okhttp3.Request

/** Adds `Authorization: Basic base64(loginName:appPassword)` and the Aether `User-Agent`. */
internal fun Request.Builder.authenticated(credentials: AetherCredentials, userAgent: String): Request.Builder =
    header("Authorization", Credentials.basic(credentials.loginName, credentials.appPassword, Charsets.UTF_8))
        .header("User-Agent", userAgent)
