package com.aether.nextcloud.ui.screens

import android.content.Context
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.data.local.entity.DocumentEntity
import com.aether.nextcloud.data.local.entity.TransferDirection
import com.aether.nextcloud.data.local.entity.TransferEntity
import com.aether.nextcloud.data.local.entity.TransferState
import com.aether.nextcloud.ui.theme.AetherTheme

val context: Context get() = ApplicationProvider.getApplicationContext()

fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

fun ComposeContentTestRule.show(content: @androidx.compose.runtime.Composable () -> Unit) = setContent { AetherTheme(dynamicColor = false) { content() } }

val alice = AetherCredentials("alice@cloud.example.com", "https://cloud.example.com", "alice", "pw", "Alice")
val bob = AetherCredentials("bob@cloud.example.com", "https://cloud.example.com", "bob", "pw", "Bob")

fun file(
    path: String,
    etag: String? = "e1",
    cachedEtag: String? = null,
    localPath: String? = null,
    dir: Boolean = false,
    size: Long = 2048,
) = DocumentEntity(
    documentId = "alice@cloud.example.com:$path", accountName = "alice@cloud.example.com", path = path,
    parentId = "alice@cloud.example.com:/", name = path.substringAfterLast('/'), isDirectory = dir, mimeType = "text/plain",
    size = size, lastModified = 1, etag = etag, fileId = "1", permissions = "RGDNVW", hasPreview = false,
    isPinned = true, localCachePath = localPath, cachedEtag = cachedEtag,
)

fun transfer(
    path: String,
    state: TransferState,
    direction: TransferDirection = TransferDirection.UPLOAD,
    done: Long = 0,
    total: Long = -1,
    error: String? = null,
) = TransferEntity(
    accountName = "alice@cloud.example.com", remotePath = path, direction = direction, state = state,
    bytesTransferred = done, totalBytes = total, error = error, createdAt = System.currentTimeMillis(),
)

/**
 * Waits until [text] is on screen. Before an activity has set its content there is no Compose hierarchy at all and
 * querying throws, which on a slow emulator happens at the first wait; that just means "not yet".
 */
fun androidx.compose.ui.test.junit4.ComposeTestRule.waitForText(text: String, timeoutMs: Long = 10_000) = waitUntil(timeoutMs) {
    try {
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    } catch (_: IllegalStateException) {
        false
    } catch (_: androidx.compose.ui.test.junit4.android.ComposeNotIdleException) {
        false
    }
}

/** Waits until [text] is gone from the screen. */
fun androidx.compose.ui.test.junit4.ComposeTestRule.waitForTextGone(text: String, timeoutMs: Long = 10_000) = waitUntil(timeoutMs) {
    try {
        onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
    } catch (_: IllegalStateException) {
        false
    } catch (_: androidx.compose.ui.test.junit4.android.ComposeNotIdleException) {
        false
    }
}
