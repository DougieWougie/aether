package com.aether.nextcloud.core

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** The one OkHttp client every Nextcloud request goes through. */
fun newAetherHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    // Requests carry the account's app password. A server must not be able to move one between http and https with
    // a redirect (OkHttp would otherwise follow it): a 3xx that changes scheme is returned as-is and fails the request.
    .followSslRedirects(false)
    // Redirects within the same scheme stay on: OkHttp drops the Authorization header when the target is another origin.
    .followRedirects(true)
    .build()
