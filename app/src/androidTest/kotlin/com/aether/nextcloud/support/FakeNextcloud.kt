package com.aether.nextcloud.support

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

/**
 * An in-memory Nextcloud: just enough WebDAV (PROPFIND, GET, PUT with If-Match, MKCOL, MOVE, DELETE and the
 * chunked-upload v2 staging area) for the provider and workers to run against. It listens on loopback, which debug
 * builds allow in cleartext (`app/src/debug`).
 */
class FakeNextcloud(val user: String = "alice") : AutoCloseable {
    class Node(val dir: Boolean, var bytes: ByteArray = ByteArray(0), var fileId: Long, var etag: String, var mtime: Long, var mime: String = "application/octet-stream", var checksum: String? = null, var preview: Boolean = false, var readOnly: Boolean = false)

    private val server = MockWebServer()
    private val files = ConcurrentHashMap<String, Node>()
    private val chunks = ConcurrentHashMap<String, MutableMap<String, ByteArray>>()
    private var nextId = 100L
    private var etagCounter = 0

    /** Every request the server saw, oldest first, as `METHOD path`. */
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    /** Paths for which writes (PUT, DELETE, MOVE) answer 423. */
    val locked: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** When set, PUT answers 507. */
    @Volatile var quotaFull = false

    /** When set, every request answers this status (401 for a revoked app password, 503 for an outage). */
    @Volatile var forcedStatus: Int? = null

    /** When set, GET answers 500 (a download that starts and fails). */
    @Volatile var failDownloads = false

    /** Per HTTP method, a status to answer instead of handling the request (for example `PROPFIND` to 500). */
    val statusByMethod: MutableMap<String, Int> = ConcurrentHashMap()

    /** When set, folder listings (PROPFIND with Depth 1) answer 500 while single-document lookups still work. */
    @Volatile var failListings = false

    /** Statuses for the next PUTs, consumed in order (for example 412 then 507); an empty queue means normal handling. */
    val putScript: java.util.Queue<Int> = java.util.concurrent.ConcurrentLinkedQueue()

    /** What `/ocs/v2.php/cloud/user` reports; [quotaTotal] null means unlimited. */
    @Volatile var displayName = "Alice Example"
    @Volatile var quotaUsed = 25_000_000L
    @Volatile var quotaTotal: Long? = 100_000_000L

    /** Extra latency applied to every PUT, to make races observable. */
    @Volatile var putDelayMs = 0L

    val baseUrl: String get() = server.url("/").toString().removeSuffix("/")

    init {
        files["/"] = Node(dir = true, fileId = 1, etag = nextEtag(), mtime = NOW)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                forcedStatus?.let { return MockResponse().setResponseCode(it) }
                statusByMethod[request.method]?.let { return MockResponse().setResponseCode(it) }
                return runCatching { handle(request) }.getOrElse { MockResponse().setResponseCode(500).setBody(it.toString()) }
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    // region test-side access

    fun putFile(path: String, text: String, mime: String = "text/plain") = putFile(path, text.toByteArray(), mime)

    /** [checksum] is the `SHA1:<hex>` form Nextcloud reports in `oc:checksums`; null means the server has none. */
    fun putFile(path: String, bytes: ByteArray, mime: String = "application/octet-stream", checksum: String? = null) {
        mkdirs(path.substringBeforeLast('/', "/"))
        files[path] = Node(dir = false, bytes = bytes, fileId = nextId++, etag = nextEtag(), mtime = NOW, mime = mime, checksum = checksum)
    }

    /** Marks [path] as having a server-side preview (`nc:has-preview`), served at `/index.php/core/preview`. */
    fun withPreview(path: String) { files.getValue(path).preview = true }

    /** Takes the write permission away from [path], as a share with read-only access does. */
    fun makeReadOnly(path: String) { files.getValue(path).readOnly = true }

    fun mkdirs(path: String) {
        var current = ""
        path.split('/').filter { it.isNotEmpty() }.forEach {
            current += "/$it"
            files.getOrPut(current) { Node(dir = true, fileId = nextId++, etag = nextEtag(), mtime = NOW) }
        }
    }

    fun exists(path: String) = files.containsKey(path)
    fun text(path: String): String? = files[path]?.takeUnless { it.dir }?.bytes?.decodeToString()
    fun bytes(path: String): ByteArray? = files[path]?.takeUnless { it.dir }?.bytes
    fun etag(path: String): String? = files[path]?.etag
    fun paths(): Set<String> = files.keys.toSet() - "/"

    /** Simulates another client editing [path]: new bytes and a new ETag. */
    fun editRemotely(path: String, text: String) {
        val node = files.getValue(path)
        node.bytes = text.toByteArray()
        node.etag = nextEtag()
    }

    fun delete(path: String) {
        files.remove(path)
        files.keys.filter { it.startsWith("$path/") }.forEach(files::remove)
    }

    /** Changes [path]'s ETag the way a real server does for a folder when anything below it changes. */
    fun touchFolder(path: String) {
        files.getValue(path).etag = nextEtag()
    }

    fun requestsMatching(method: String, pathPrefix: String = "") =
        requests.filter { it.method == method && relative(it.path.orEmpty()).startsWith(pathPrefix) }

    // endregion

    private fun nextEtag() = "etag${etagCounter++}"

    private fun handle(request: RecordedRequest): MockResponse {
        val rawPath = request.path.orEmpty().substringBefore('?')
        val path = java.net.URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8")
        return when {
            path.startsWith("/remote.php/dav/uploads/$user/") -> uploads(request, path.removePrefix("/remote.php/dav/uploads/$user/"))
            path.startsWith("/remote.php/dav/files/$user") -> files(request, normalise(path.removePrefix("/remote.php/dav/files/$user")))
            path == "/index.php/core/preview" -> {
                val fileId = request.requestUrl?.queryParameter("fileId")
                if (files.values.any { it.fileId.toString() == fileId && it.preview }) MockResponse().setHeader("Content-Type", "image/png").setBody("PREVIEW-$fileId") else MockResponse().setResponseCode(404)
            }
            path == "/ocs/v2.php/cloud/user" -> MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"ocs":{"meta":{"status":"ok"},"data":{"id":"$user","display-name":"$displayName","quota":{"used":$quotaUsed,"free":${quotaTotal?.let { it - quotaUsed } ?: -3},"total":${quotaTotal ?: 0},"quota":${quotaTotal ?: "\"none\""}}}}}""",
            )
            path == "/ocs/v2.php/core/apppassword" && request.method == "DELETE" -> MockResponse().setResponseCode(200)
            path.startsWith("/ocs/") -> MockResponse().setResponseCode(404)
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun normalise(path: String) = ("/" + path.trim('/')).let { if (it == "/") it else it }

    private fun relative(rawPath: String): String {
        val decoded = java.net.URLDecoder.decode(rawPath.substringBefore('?').replace("+", "%2B"), "UTF-8")
        return normalise(decoded.removePrefix("/remote.php/dav/files/$user"))
    }

    private fun files(request: RecordedRequest, path: String): MockResponse = when (request.method) {
        "PROPFIND" -> if (failListings && request.getHeader("Depth") == "1") MockResponse().setResponseCode(500) else propfind(request, path)
        "GET" -> if (failDownloads) MockResponse().setResponseCode(500) else files[path]?.takeUnless { it.dir }?.let {
            MockResponse().setBody(Buffer().write(it.bytes)).setHeader("ETag", "\"${it.etag}\"").setHeader("Content-Type", it.mime)
        } ?: MockResponse().setResponseCode(404)
        "PUT" -> put(request, path)
        "MKCOL" -> when {
            files.containsKey(path) -> MockResponse().setResponseCode(405)
            parentMissing(path) -> MockResponse().setResponseCode(409)
            else -> { files[path] = Node(dir = true, fileId = nextId++, etag = nextEtag(), mtime = NOW); MockResponse().setResponseCode(201) }
        }
        "DELETE" -> when {
            path in locked -> MockResponse().setResponseCode(423)
            files.remove(path) == null -> MockResponse().setResponseCode(404)
            else -> { files.keys.filter { it.startsWith("$path/") }.forEach(files::remove); MockResponse().setResponseCode(204) }
        }
        "MOVE" -> move(request, path)
        else -> MockResponse().setResponseCode(405)
    }

    private fun parentMissing(path: String) = !files.containsKey(path.substringBeforeLast('/').ifEmpty { "/" })

    private fun put(request: RecordedRequest, path: String): MockResponse {
        if (putDelayMs > 0) Thread.sleep(putDelayMs)
        putScript.poll()?.let { return MockResponse().setResponseCode(it) }
        if (quotaFull) return MockResponse().setResponseCode(507)
        if (path in locked) return MockResponse().setResponseCode(423)
        if (parentMissing(path)) return MockResponse().setResponseCode(409)
        val existing = files[path]
        request.getHeader("If-Match")?.let { if (existing == null || "\"${existing.etag}\"" != it) return MockResponse().setResponseCode(412) }
        if (request.getHeader("If-None-Match") == "*" && existing != null) return MockResponse().setResponseCode(412)
        val node = Node(dir = false, bytes = request.body.readByteArray(), fileId = existing?.fileId ?: nextId++, etag = nextEtag(), mtime = NOW, mime = request.getHeader("Content-Type") ?: "application/octet-stream", checksum = request.getHeader("OC-Checksum"))
        files[path] = node
        return MockResponse().setResponseCode(if (existing == null) 201 else 204).setHeader("OC-ETag", "\"${node.etag}\"").setHeader("OC-FileId", node.fileId.toString())
    }

    private fun move(request: RecordedRequest, path: String): MockResponse {
        if (path in locked) return MockResponse().setResponseCode(423)
        val destination = destinationOf(request) ?: return MockResponse().setResponseCode(400)
        val overwrite = request.getHeader("Overwrite") != "F"
        if (!overwrite && files.containsKey(destination)) return MockResponse().setResponseCode(412)
        val node = files.remove(path) ?: return MockResponse().setResponseCode(404)
        val moved = files.keys.filter { it.startsWith("$path/") }.associateWith { files.remove(it)!! }
        files[destination] = node
        moved.forEach { (old, child) -> files[destination + old.removePrefix(path)] = child }
        return MockResponse().setResponseCode(201)
    }

    private fun destinationOf(request: RecordedRequest): String? {
        val destination = request.getHeader("Destination") ?: return null
        val path = java.net.URLDecoder.decode(java.net.URI(destination).rawPath.replace("+", "%2B"), "UTF-8")
        return normalise(path.removePrefix("/remote.php/dav/files/$user"))
    }

    private fun uploads(request: RecordedRequest, rest: String): MockResponse {
        val session = rest.substringBefore('/')
        val part = rest.substringAfter('/', "")
        return when (request.method) {
            "MKCOL" -> { chunks[session] = ConcurrentHashMap(); MockResponse().setResponseCode(201) }
            "PUT" -> {
                if (quotaFull) return MockResponse().setResponseCode(507)
                chunks[session]?.set(part, request.body.readByteArray()); MockResponse().setResponseCode(201)
            }
            "MOVE" -> {
                val destination = destinationOf(request) ?: return MockResponse().setResponseCode(400)
                if (destination in locked) return MockResponse().setResponseCode(423)
                val existing = files[destination]
                request.getHeader("If-Match")?.let { if (existing == null || "\"${existing.etag}\"" != it) return MockResponse().setResponseCode(412) }
                val assembled = chunks[session].orEmpty().toSortedMap().values.fold(ByteArray(0)) { acc, b -> acc + b }
                files[destination] = Node(dir = false, bytes = assembled, fileId = existing?.fileId ?: nextId++, etag = nextEtag(), mtime = NOW)
                chunks.remove(session)
                MockResponse().setResponseCode(if (existing == null) 201 else 204).setHeader("OC-ETag", "\"${files.getValue(destination).etag}\"")
            }
            "DELETE" -> { chunks.remove(session); MockResponse().setResponseCode(204) }
            else -> MockResponse().setResponseCode(405)
        }
    }

    private fun propfind(request: RecordedRequest, path: String): MockResponse {
        val self = files[path] ?: return MockResponse().setResponseCode(404)
        val depth = request.getHeader("Depth") ?: "1"
        val entries = buildList {
            add(path to self)
            if (depth != "0" && self.dir) {
                files.filterKeys { it != path && it.substringBeforeLast('/').ifEmpty { "/" } == path }.toSortedMap().forEach { (k, v) -> add(k to v) }
            }
        }
        val body = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">""")
            entries.forEach { (p, n) ->
                val href = "/remote.php/dav/files/$user" + p.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }.let { if (n.dir && !it.endsWith("/")) "$it/" else it }
                append("<d:response><d:href>$href</d:href><d:propstat><d:prop>")
                append(if (n.dir) "<d:resourcetype><d:collection/></d:resourcetype>" else "<d:resourcetype/>")
                if (!n.dir) append("<d:getcontentlength>${n.bytes.size}</d:getcontentlength><d:getcontenttype>${n.mime}</d:getcontenttype>")
                append("<d:getlastmodified>Mon, 01 Jan 2024 00:00:00 GMT</d:getlastmodified>")
                append("<d:getetag>&quot;${n.etag}&quot;</d:getetag>")
                append("<oc:fileid>${n.fileId}</oc:fileid><oc:permissions>${if (n.dir) "RGDNVCK" else if (n.readOnly) "RGDNV" else "RGDNVW"}</oc:permissions>")
                n.checksum?.let { append("<oc:checksums><oc:checksum>$it</oc:checksum></oc:checksums>") }
                append("<oc:size>${if (n.dir) 0 else n.bytes.size}</oc:size><nc:has-preview>${n.preview}</nc:has-preview><oc:favorite>0</oc:favorite>")
                append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
            }
            append("</d:multistatus>")
        }
        return MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml; charset=utf-8").setBody(body)
    }

    override fun close() = server.shutdown()

    private companion object {
        const val NOW = 1_704_067_200_000L
    }
}
