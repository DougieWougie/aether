package com.aether.nextcloud.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.data.local.entity.BackedUpMediaEntity
import com.aether.nextcloud.data.local.entity.TransferDirection
import com.aether.nextcloud.data.local.entity.TransferEntity
import com.aether.nextcloud.provider.DocumentId
import com.aether.nextcloud.support.AetherTestRule
import com.aether.nextcloud.support.awaitTrue
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Signing out must leave nothing of the account behind (PLAN.md 1.3). */
@RunWith(AndroidJUnit4::class)
class AccountCleanerTest {
    @get:Rule val env = AetherTestRule()

    private val credentials get() = env.graph.accounts().credentials(env.accountName)!!

    @Test
    fun removingAnAccountDeletesItsRowsFilesAndAppPassword() {
        env.nextcloud.putFile("/a.txt", "a")
        val root = DocumentId.root(env.accountName)
        val cache = env.graph.diskCache()
        val creds = credentials
        runBlocking {
            env.graph.documents().refreshChildren(creds, root)
            env.graph.documents().ensureLocalFile(creds, DocumentId(env.accountName, "/a.txt"))
        }
        val cached = File(env.graph.documents().cached(DocumentId(env.accountName, "/a.txt"))!!.localCachePath!!)
        val staged = cache.newStagingFile(DocumentId(env.accountName, "/a.txt").toString()).apply { writeText("unsent") }
        val thumbnail = cache.thumbnailFor(env.accountName, "100", 256, "etag").apply { parentFile!!.mkdirs(); writeText("png") }
        val transfers = env.graph.database().transferDao()
        transfers.insert(TransferEntity(accountName = env.accountName, remotePath = "/a.txt", direction = TransferDirection.UPLOAD, createdAt = 1))
        val backedUp = env.graph.database().backedUpMediaDao()
        backedUp.upsert(BackedUpMediaEntity(env.accountName, mediaId = 1, isVideo = false, dateModified = 1, size = 1, remotePath = "/Camera/1.jpg", backedUpAt = 1))
        assertTrue(cached.isFile && staged.isFile && thumbnail.isFile)

        env.graph.accountCleaner().remove(env.accountName)

        awaitTrue("the app password to be revoked on the server") { env.nextcloud.requests.any { it.method == "DELETE" && it.path.orEmpty().contains("/ocs/v2.php/core/apppassword") } }
        awaitTrue("the cached document rows to go") { env.graph.documents().cached(DocumentId(env.accountName, "/a.txt")) == null }
        awaitTrue("the cached file to go") { !cached.exists() }
        assertFalse("staged uploads are deleted", staged.exists())
        assertFalse("thumbnails are deleted", thumbnail.exists())
        assertNull(env.graph.accounts().credentials(env.accountName))
        awaitTrue("the transfer rows to go") { runBlocking { transfers.observeRecent(10).first() }.none { it.accountName == env.accountName } }
        awaitTrue("the backup log rows to go") { backedUp.find(env.accountName, 1, false) == null }
    }

    @Test
    fun removingAnAccountAlreadyGoneFromAccountManagerStillCleansUp() {
        env.nextcloud.putFile("/a.txt", "a")
        val creds = credentials
        runBlocking { env.graph.documents().refreshChildren(creds, DocumentId.root(env.accountName)) }

        env.graph.accounts().removeAccount(env.accountName)
        env.graph.accountCleaner().remove(env.accountName, removeFromAccountManager = false)

        awaitTrue("rows to be deleted") { env.graph.documents().cached(DocumentId(env.accountName, "/a.txt")) == null }
        assertTrue("without credentials nothing can be revoked", env.nextcloud.requests.none { it.path.orEmpty().contains("/ocs/") })
    }
}
