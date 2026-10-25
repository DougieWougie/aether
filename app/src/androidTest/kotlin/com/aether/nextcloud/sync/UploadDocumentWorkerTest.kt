package com.aether.nextcloud.sync

import android.provider.DocumentsContract.Document
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.support.AetherTestRule
import com.aether.nextcloud.support.Smoke
import com.aether.nextcloud.support.awaitTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Write-backs go through the provider, which stages the bytes and hands them to the real [UploadDocumentWorker]. */
@Smoke
@RunWith(AndroidJUnit4::class)
class UploadDocumentWorkerTest {
    @get:Rule val env = AetherTestRule()

    private fun listed(path: String, name: String) {
        awaitTrue("$name to be listed") {
            env.resolver.query(env.childrenUri(path), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)!!.use { c ->
                generateSequence { if (c.moveToNext()) c.getString(0) else null }.any { it == name }
            }
        }
    }

    @Test
    fun anEditBasedOnAStaleVersionIsSavedAsAConflictCopy() {
        env.nextcloud.putFile("/report.txt", "v1")
        listed("/", "report.txt")

        // The descriptor captures v1's ETag when it opens; another client then changes the file before this one closes it.
        env.resolver.openOutputStream(env.documentUri("/report.txt"), "wt")!!.use { out ->
            env.nextcloud.editRemotely("/report.txt", "edited elsewhere")
            out.write("my local edit".toByteArray())
        }

        awaitTrue("a conflict copy to appear") { env.nextcloud.paths().any { it.contains("(Conflict ") } }
        val copy = env.nextcloud.paths().single { it.contains("(Conflict ") }
        assertTrue("copy should keep the extension: $copy", copy.startsWith("/report (Conflict ") && copy.endsWith(").txt"))
        assertEquals("my local edit", env.nextcloud.text(copy))
        assertEquals("the other client's edit must survive", "edited elsewhere", env.nextcloud.text("/report.txt"))
    }

    @Test
    fun filesOverTenMegabytesGoThroughChunkedUploadV2() {
        env.nextcloud.putFile("/video.bin", "x")
        listed("/", "video.bin")
        val big = ByteArray(11 * 1024 * 1024) { (it % 251).toByte() }

        env.resolver.openOutputStream(env.documentUri("/video.bin"), "wt")!!.use { it.write(big) }

        awaitTrue("the assembled file", timeoutMs = 60_000) { env.nextcloud.bytes("/video.bin")?.size == big.size }
        assertArrayEquals(big, env.nextcloud.bytes("/video.bin"))
        val uploads = env.nextcloud.requests.filter { it.path.orEmpty().contains("/remote.php/dav/uploads/") }
        assertEquals("MKCOL", uploads.first().method)
        assertEquals("two chunks for 11 MB", 2, uploads.count { it.method == "PUT" })
        assertEquals("MOVE", uploads.last().method)
        assertTrue("no whole-file PUT", env.nextcloud.requestsMatching("PUT", "/video.bin").isEmpty())
    }

    @Test
    fun aFullQuotaFailsTheUploadWithoutRetrying() {
        env.nextcloud.putFile("/doc.txt", "small")
        listed("/", "doc.txt")
        env.nextcloud.quotaFull = true

        env.resolver.openOutputStream(env.documentUri("/doc.txt"), "wt")!!.use { it.write("bigger".toByteArray()) }

        awaitTrue("the upload attempt") { env.nextcloud.requestsMatching("PUT", "/doc.txt").isNotEmpty() }
        Thread.sleep(3_000)
        assertEquals("507 is not retried", 1, env.nextcloud.requestsMatching("PUT", "/doc.txt").size)
        assertEquals("small", env.nextcloud.text("/doc.txt"))
    }
}
