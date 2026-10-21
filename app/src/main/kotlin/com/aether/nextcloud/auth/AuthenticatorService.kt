package com.aether.nextcloud.auth

import android.app.Service
import android.content.Intent
import android.os.IBinder

class AuthenticatorService : Service() {
    private lateinit var authenticator: AetherAccountAuthenticator

    override fun onCreate() {
        authenticator = AetherAccountAuthenticator(this)
    }

    override fun onBind(intent: Intent): IBinder? = authenticator.iBinder
}
