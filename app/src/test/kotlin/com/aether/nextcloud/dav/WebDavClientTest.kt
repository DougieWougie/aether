package com.aether.nextcloud.dav

import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.core.AetherError
import com.aether.nextcloud.core.AetherResult
import java.io.File
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebDavClientTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var credentials: AetherCredentials
    private lateinit var dav: WebDavClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val base = server.url("/").toString().trimEnd('/')
        credentials = AetherCredentials("alice@localhost", base, "alice", "app-pw", "Alice")
        dav = WebDavClient(OkHttpClient(), credentials, "Aether (Test; Android 36)", chunkSize = 4)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun url(path: String) = credentials.fileUrl(path)

    private fun fixture(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.readBytes().decodeToString()

    @Test
    fun `propfind sends depth, auth and the property body and parses the multistatus`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(207).setBody(fixture("propfind_depth1.xml")))

        val result = dav.propfind(url("/Documents/"), depth = 1)

        assertEquals(3, (result as AetherResult.Success).value.size)
        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("/remote.php/dav/files/alice/Documents/", request.path)
        assertEquals("1", request.getHeader("Depth"))
        assertEquals("Basic " + Base64.getEncoder().encodeToString("alice:app-pw".toByteArray()), request.getHeader("Authorization"))
        assertEquals("Aether (Test; Android 36)", request.getHeader("User-Agent"))
        val body = request.body.readUtf8()
        listOf("d:resourcetype", "d:getetag", "oc:fileid", "oc:permissions", "oc:size", "nc:has-preview", "oc:favorite")
            .forEach { assertTrue("missing $it", body.contains("<$it/>")) }
    }

    @Test
    fun `a 403 is a permission problem, not a rejected login`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))

        val error = (dav.propfind(url("/"), 0) as AetherResult.Failure).error

        assertTrue("was $error", error is AetherError.Http && error.code == 403)
    }

    @Test
    fun `propfind maps errors`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(207).setBody("<not-xml"))

        assertEquals(AetherResult.Failure(AetherError.Unauthorized), dav.propfind(url("/"), 0))
        assertEquals(AetherResult.Failure(AetherError.NotFound), dav.propfind(url("/nope"), 0))
        assertTrue((dav.propfind(url("/"), 0) as AetherResult.Failure).error is AetherError.Malformed)
    }

    @Test
    fun `downloadFile writes the target atomically, reports progress and returns the etag`() = runBlocking {
        val content = "hello nextcloud".repeat(1000)
        server.enqueue(MockResponse().setBody(content).setHeader("OC-ETag", "\"etag-1\""))
        val target = File(tmp.root, "cache/notes.md")
        val progress = mutableListOf<Long>()

        val result = dav.downloadFile(url("/notes.md"), target) { done, total ->
            progress += done
            assertEquals(content.length.toLong(), total)
        }

        assertEquals("etag-1", (result as AetherResult.Success).value)
        assertEquals(content, target.readText())
        assertEquals(content.length.toLong(), progress.last())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun `downloadFile leaves nothing behind on http errors`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val target = File(tmp.root, "gone.md")

        val result = dav.downloadFile(url("/gone.md"), target)

        assertEquals(AetherResult.Failure(AetherError.NotFound), result)
        assertFalse(target.exists())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun `uploadFile puts with if-match and surfaces the new etag`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204).setHeader("OC-ETag", "\"new-etag\"").setHeader("OC-FileId", "00001234ocx"))
        val file = tmp.newFile("a.txt").apply { writeText("payload") }
        val progress = mutableListOf<Long>()

        val result = dav.uploadFile(url("/a.txt"), file, "text/plain", ifMatchEtag = "old-etag") { done, _ -> progress += done }

        assertEquals(UploadResult("new-etag", "00001234ocx"), (result as AetherResult.Success).value)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("\"old-etag\"", request.getHeader("If-Match"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("text/plain"))
        assertEquals("payload", request.body.readUtf8())
        assertEquals(7L, progress.last())
    }

    @Test
    fun `uploadFile omits if-match for new files and maps 412`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(412))
        val file = tmp.newFile("b.txt").apply { writeText("x") }

        val created = dav.uploadFile(url("/b.txt"), file, null)
        val conflict = dav.uploadFile(url("/b.txt"), file, null, ifMatchEtag = "stale")

        assertNull((created as AetherResult.Success).value.etag)
        assertNull(server.takeRequest().getHeader("If-Match"))
        assertEquals(AetherResult.Failure(AetherError.PreconditionFailed), conflict)
    }

    @Test
    fun `chunked upload v2 creates the dir, puts ordered chunks and assembles with move`() = runBlocking {
        val data = "0123456789".toByteArray() // 10 bytes, chunk size 4 -> 4 + 4 + 2
        val file = tmp.newFile("big.bin").apply { writeBytes(data) }
        server.enqueue(MockResponse().setResponseCode(201)) // MKCOL
        repeat(3) { server.enqueue(MockResponse().setResponseCode(201)) } // chunks
        server.enqueue(MockResponse().setResponseCode(201).setHeader("OC-ETag", "\"assembled\"")) // MOVE
        val progress = mutableListOf<Long>()

        val result = dav.uploadChunkedV2(file, "/Videos/big.bin") { done, total ->
            assertEquals(10L, total)
            progress += done
        }

        assertEquals("assembled", (result as AetherResult.Success).value.etag)
        val mkcol = server.takeRequest()
        assertEquals("MKCOL", mkcol.method)
        assertTrue(mkcol.path!!.startsWith("/remote.php/dav/uploads/alice/"))
        assertEquals("10", mkcol.getHeader("OC-Total-Length"))
        val uploadDir = mkcol.path!!

        val chunks = List(3) { server.takeRequest() }
        assertEquals(listOf("$uploadDir/00001", "$uploadDir/00002", "$uploadDir/00003"), chunks.map { it.path })
        assertTrue(chunks.all { it.method == "PUT" && it.getHeader("OC-Total-Length") == "10" })
        val reassembled = Buffer().also { out -> chunks.forEach { out.write(it.body, it.body.size) } }.readByteArray()
        assertArrayEquals(data, reassembled)

        val move = server.takeRequest()
        assertEquals("MOVE", move.method)
        assertEquals("$uploadDir/.file", move.path)
        assertEquals(credentials.fileUrl("/Videos/big.bin"), move.getHeader("Destination"))
        assertEquals("10", move.getHeader("OC-Total-Length"))
        assertEquals(10L, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun `chunked upload cleans up the upload dir when a chunk fails`() = runBlocking {
        val file = tmp.newFile("big2.bin").apply { writeBytes(ByteArray(10)) }
        server.enqueue(MockResponse().setResponseCode(201)) // MKCOL
        server.enqueue(MockResponse().setResponseCode(201)) // chunk 1
        server.enqueue(MockResponse().setResponseCode(507)) // chunk 2: insufficient storage
        server.enqueue(MockResponse().setResponseCode(204)) // DELETE

        val result = dav.uploadChunkedV2(file, "/big2.bin")

        assertEquals(507, ((result as AetherResult.Failure).error as AetherError.Http).code)
        repeat(3) { server.takeRequest() }
        val cleanup = server.takeRequest()
        assertEquals("DELETE", cleanup.method)
        assertTrue(cleanup.path!!.startsWith("/remote.php/dav/uploads/alice/"))
    }

    @Test
    fun `chunked upload surfaces a 412 from the final move`() = runBlocking {
        val file = tmp.newFile("big3.bin").apply { writeBytes(ByteArray(3)) }
        repeat(2) { server.enqueue(MockResponse().setResponseCode(201)) }
        server.enqueue(MockResponse().setResponseCode(412))
        server.enqueue(MockResponse().setResponseCode(204))

        val result = dav.uploadChunkedV2(file, "/big3.bin", ifMatchEtag = "old")

        assertEquals(AetherResult.Failure(AetherError.PreconditionFailed), result)
        repeat(2) { server.takeRequest() }
        assertEquals("\"old\"", server.takeRequest().getHeader("If-Match"))
    }

    @Test
    fun `mkcol delete move and copy use the right verbs and headers`() = runBlocking {
        repeat(5) { server.enqueue(MockResponse().setResponseCode(201)) }

        dav.mkcol(url("/New"))
        dav.delete(url("/Old"))
        dav.move(url("/a"), url("/b"), overwrite = false)
        dav.move(url("/a"), url("/b"), overwrite = true)
        dav.copy(url("/a"), url("/c"))

        assertEquals("MKCOL", server.takeRequest().method)
        assertEquals("DELETE", server.takeRequest().method)
        val move = server.takeRequest()
        assertEquals("MOVE", move.method)
        assertEquals(url("/b"), move.getHeader("Destination"))
        assertEquals("F", move.getHeader("Overwrite"))
        assertEquals("T", server.takeRequest().getHeader("Overwrite"))
        assertEquals("COPY", server.takeRequest().method)
    }

    @Test
    fun `move onto an existing file without overwrite reports precondition failed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(412))
        assertEquals(AetherResult.Failure(AetherError.PreconditionFailed), dav.move(url("/a"), url("/b")))
    }

    @Test
    fun `createOnly sends if-none-match and never overrides an explicit if-match`() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(201)) }
        val file = tmp.newFile("c.txt").apply { writeText("x") }

        dav.uploadFile(url("/c.txt"), FileUploadSource(file), null, createOnly = true)
        dav.uploadFile(url("/c.txt"), FileUploadSource(file), null, ifMatchEtag = "e", createOnly = true)

        val fresh = server.takeRequest()
        assertEquals("*", fresh.getHeader("If-None-Match"))
        assertNull(fresh.getHeader("If-Match"))
        val edit = server.takeRequest()
        assertEquals("\"e\"", edit.getHeader("If-Match"))
        assertNull(edit.getHeader("If-None-Match"))
    }

    @Test
    fun `chunked createOnly assembles without overwrite`() = runBlocking {
        val file = tmp.newFile("d.bin").apply { writeBytes(ByteArray(3)) }
        repeat(3) { server.enqueue(MockResponse().setResponseCode(201)) }

        dav.uploadChunkedV2(FileUploadSource(file), "/d.bin", createOnly = true)

        repeat(2) { server.takeRequest() }
        assertEquals("F", server.takeRequest().getHeader("Overwrite"))
    }

    @Test
    fun `a locked file reports Locked for delete and upload`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(423))
        server.enqueue(MockResponse().setResponseCode(423))
        val file = tmp.newFile("locked.txt").apply { writeText("x") }

        assertEquals(AetherResult.Failure(AetherError.Locked), dav.delete(url("/locked.txt")))
        assertEquals(AetherResult.Failure(AetherError.Locked), dav.uploadFile(url("/locked.txt"), file, null))
    }

    @Test
    fun `network failures become Network errors`() = runBlocking {
        server.shutdown()
        assertTrue((dav.delete(url("/x")) as AetherResult.Failure).error is AetherError.Network)
    }
}
