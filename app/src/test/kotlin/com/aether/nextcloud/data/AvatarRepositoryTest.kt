package com.aether.nextcloud.data

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.core.AetherError
import com.aether.nextcloud.core.AetherResult
import com.aether.nextcloud.dav.NextcloudOcsClient
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AvatarRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val creds = AetherCredentials("alice@cloud.example.com", "https://cloud.example.com", "alice", "pw", "Alice")

    private fun writePng(target: File) {
        target.parentFile?.mkdirs()
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        target.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    @Test
    fun refreshCachesAvatarFallsBackOnFailureAndForgetsOnSignOut() = runBlocking {
        val ocs = mockk<NextcloudOcsClient>()
        val repo = AvatarRepository(context, ocs)
        repo.forget(creds.accountName)
        assertNull(repo.cached(creds.accountName))

        coEvery { ocs.downloadAvatar(creds, any(), any()) } answers {
            writePng(secondArg())
            AetherResult.Success(true)
        }
        assertNotNull(repo.refresh(creds))
        assertNotNull(repo.cached(creds.accountName))

        // Failure falls back to the cached bitmap
        coEvery { ocs.downloadAvatar(creds, any(), any()) } returns AetherResult.Failure(AetherError.Network(IOException("offline")))
        assertNotNull(repo.refresh(creds))

        // Success(false) means no avatar exists on server -> returns null
        coEvery { ocs.downloadAvatar(creds, any(), any()) } returns AetherResult.Success(false)
        assertNull(repo.refresh(creds))

        // Forget deletes the cached file
        repo.forget(creds.accountName)
        assertNull(repo.cached(creds.accountName))
    }
}
