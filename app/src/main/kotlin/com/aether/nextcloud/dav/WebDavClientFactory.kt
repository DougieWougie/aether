package com.aether.nextcloud.dav

import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.di.UserAgent
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient

/** [WebDavClient] is bound to one account's credentials; this builds them on demand over the shared OkHttp pool. */
@Singleton
class WebDavClientFactory @Inject constructor(
    private val client: OkHttpClient,
    @UserAgent private val userAgent: String,
) {
    fun forAccount(credentials: AetherCredentials) = WebDavClient(client, credentials, userAgent)
}
