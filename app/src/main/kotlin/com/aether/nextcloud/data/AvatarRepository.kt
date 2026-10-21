package com.aether.nextcloud.data

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.core.AetherResult
import com.aether.nextcloud.dav.NextcloudOcsClient
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Profile pictures kept in `cacheDir/avatars`, one file per account, so the header can show them before the network answers. */
@Singleton
class AvatarRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ocs: NextcloudOcsClient,
) {
    private fun file(accountName: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(accountName.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(context.cacheDir, "avatars/$name")
    }

    /** The last picture fetched for this account, or null if it has none. */
    suspend fun cached(accountName: String): ImageBitmap? = decode(file(accountName))

    /** Fetches the picture again; null if the user has none or the request failed and nothing is cached. */
    suspend fun refresh(credentials: AetherCredentials): ImageBitmap? {
        val target = file(credentials.accountName)
        return when (val result = ocs.downloadAvatar(credentials, target)) {
            is AetherResult.Success -> if (result.value) decode(target) else null
            is AetherResult.Failure -> decode(target)
        }
    }

    suspend fun forget(accountName: String) {
        withContext(Dispatchers.IO) { file(accountName).delete() }
    }

    private suspend fun decode(file: File): ImageBitmap? = withContext(Dispatchers.IO) {
        if (file.isFile) BitmapFactory.decodeFile(file.path)?.asImageBitmap() else null
    }
}
