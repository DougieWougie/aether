package com.aether.nextcloud.dav

import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.core.AetherError
import com.aether.nextcloud.core.AetherResult
import com.aether.nextcloud.core.execute
import com.aether.nextcloud.core.toError
import com.aether.nextcloud.dav.model.DavResource
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParserException

/** Result of a successful upload; [etag] is the new server ETag when the server reported one. */
data class UploadResult(val etag: String?, val fileId: String?)

/** Progress callback: bytes transferred so far and total bytes (-1 when unknown). */
typealias ProgressListener = (transferred: Long, total: Long) -> Unit

/** WebDAV operations for a single Nextcloud account, built on OkHttp (see ADR-0005). */
class WebDavClient(
    private val client: OkHttpClient,
    private val credentials: AetherCredentials,
    private val userAgent: String,
    private val parser: WebDavXmlParser = WebDavXmlParser(),
    private val chunkSize: Long = CHUNK_SIZE,
) {
    private fun request(url: String) = Request.Builder().url(url).authenticated(credentials, userAgent)

    suspend fun propfind(url: String, depth: Int): AetherResult<List<DavResource>> {
        val request = request(url)
            .header("Depth", depth.toString())
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML))
            .build()
        return client.execute(request) { response ->
            if (response.code != 207) return@execute AetherResult.Failure(response.toError())
            try {
                AetherResult.Success(parser.parse(response.body.byteStream()))
            } catch (e: XmlPullParserException) {
                AetherResult.Failure(AetherError.Malformed(e.message ?: "invalid XML"))
            }
        }
    }

    /** Downloads to a `.part` sibling and renames on success, so [targetFile] is never half-written. Returns the ETag. */
    suspend fun downloadFile(url: String, targetFile: File, onProgress: ProgressListener = { _, _ -> }): AetherResult<String?> {
        val partial = File(targetFile.parentFile, targetFile.name + ".part")
        return client.execute(request(url).get().build()) { response ->
            if (!response.isSuccessful) return@execute AetherResult.Failure(response.toError())
            val body = response.body
            val total = body.contentLength()
            targetFile.parentFile?.mkdirs()
            try {
                var transferred = 0L
                partial.outputStream().use { out ->
                    val source = body.source()
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        transferred += read
                        onProgress(transferred, total)
                    }
                }
                if (!partial.renameTo(targetFile)) throw IOException("Cannot move ${partial.name} into place")
                AetherResult.Success(response.header("OC-ETag")?.let(WebDavXmlParser::cleanEtag) ?: response.header("ETag")?.let(WebDavXmlParser::cleanEtag))
            } catch (e: Throwable) {
                partial.delete()
                throw e
            }
        }
    }

    /** PUT [sourceFile]; with [ifMatchEtag] the server answers 412 if the remote file changed meanwhile. */
    suspend fun uploadFile(
        url: String,
        sourceFile: File,
        mimeType: String?,
        ifMatchEtag: String? = null,
        onProgress: ProgressListener = { _, _ -> },
    ): AetherResult<UploadResult> = uploadFile(url, FileUploadSource(sourceFile), mimeType, ifMatchEtag, onProgress)

    /** With [createOnly] the server refuses (412) to replace an existing file (`If-None-Match: *`). */
    suspend fun uploadFile(
        url: String,
        source: UploadSource,
        mimeType: String?,
        ifMatchEtag: String? = null,
        onProgress: ProgressListener = { _, _ -> },
        createOnly: Boolean = false,
    ): AetherResult<UploadResult> {
        val length = source.length
        val body = SourceSliceRequestBody(source, 0, length, mimeType?.toMediaTypeOrNull()) { onProgress(it, length) }
        val request = request(url).put(body).apply {
            if (ifMatchEtag != null) header("If-Match", "\"$ifMatchEtag\"")
            else if (createOnly) header("If-None-Match", "*")
            source.lastModified?.let { header("X-OC-Mtime", (it / 1000).toString()) }
        }.build()
        return client.execute(request) { response -> uploadResult(response) }
    }

    /**
     * Chunked Upload v2: `MKCOL` an upload directory, `PUT` numbered chunks, then `MOVE .file` onto the target.
     * [targetRemotePath] is relative to the account's files root (e.g. `/Videos/clip.mp4`).
     */
    suspend fun uploadChunkedV2(
        sourceFile: File,
        targetRemotePath: String,
        ifMatchEtag: String? = null,
        onProgress: ProgressListener = { _, _ -> },
    ): AetherResult<UploadResult> = uploadChunkedV2(FileUploadSource(sourceFile), targetRemotePath, ifMatchEtag, onProgress)

    suspend fun uploadChunkedV2(
        source: UploadSource,
        targetRemotePath: String,
        ifMatchEtag: String? = null,
        onProgress: ProgressListener = { _, _ -> },
        createOnly: Boolean = false,
    ): AetherResult<UploadResult> {
        val total = source.length
        val uploadDir = "${credentials.uploadsBaseUrl}/${UUID.randomUUID()}"
        val destination = credentials.fileUrl(targetRemotePath)

        val created = send(
            request(uploadDir).method("MKCOL", null).header("Destination", destination).header("OC-Total-Length", total.toString()).build(),
        )
        if (created is AetherResult.Failure) return created

        val result = putChunks(uploadDir, source, total, onProgress).let { chunks ->
            if (chunks is AetherResult.Failure) chunks else assemble(uploadDir, destination, total, ifMatchEtag, source.lastModified, overwrite = !createOnly || ifMatchEtag != null)
        }
        if (result is AetherResult.Failure) {
            // Orphaned upload directories count against quota until the server's cleanup job removes them.
            runCatching { delete(uploadDir) }
        }
        return result
    }

    private suspend fun putChunks(uploadDir: String, source: UploadSource, total: Long, onProgress: ProgressListener): AetherResult<Unit> {
        var offset = 0L
        var index = 1
        while (offset < total) {
            val length = minOf(chunkSize, total - offset)
            val base = offset
            val body = SourceSliceRequestBody(source, offset, length, OCTET_STREAM) { onProgress(base + it, total) }
            val request = request("$uploadDir/${index.toString().padStart(5, '0')}")
                .put(body)
                .header("OC-Total-Length", total.toString())
                .build()
            val put = send(request)
            if (put is AetherResult.Failure) return put
            offset += length
            index++
        }
        return AetherResult.Success(Unit)
    }

    private suspend fun assemble(uploadDir: String, destination: String, total: Long, ifMatchEtag: String?, lastModified: Long?, overwrite: Boolean): AetherResult<UploadResult> {
        val request = request("$uploadDir/.file")
            .method("MOVE", null)
            .header("Destination", destination)
            .header("OC-Total-Length", total.toString())
            .header("Overwrite", if (overwrite) "T" else "F")
            .apply {
                if (ifMatchEtag != null) header("If-Match", "\"$ifMatchEtag\"")
                lastModified?.let { header("X-OC-Mtime", (it / 1000).toString()) }
            }
            .build()
        return client.execute(request) { response -> uploadResult(response) }
    }

    suspend fun mkcol(url: String): AetherResult<Unit> = send(request(url).method("MKCOL", null).build())

    suspend fun delete(url: String): AetherResult<Unit> = send(request(url).delete().build())

    suspend fun move(sourceUrl: String, destUrl: String, overwrite: Boolean = false): AetherResult<Unit> =
        send(request(sourceUrl).method("MOVE", null).header("Destination", destUrl).header("Overwrite", if (overwrite) "T" else "F").build())

    suspend fun copy(sourceUrl: String, destUrl: String, overwrite: Boolean = false): AetherResult<Unit> =
        send(request(sourceUrl).method("COPY", null).header("Destination", destUrl).header("Overwrite", if (overwrite) "T" else "F").build())

    private suspend fun send(request: Request): AetherResult<Unit> = client.execute(request) { response ->
        if (response.isSuccessful) AetherResult.Success(Unit) else AetherResult.Failure(response.toError())
    }

    private fun uploadResult(response: okhttp3.Response): AetherResult<UploadResult> =
        if (response.isSuccessful) {
            AetherResult.Success(
                UploadResult(
                    etag = (response.header("OC-ETag") ?: response.header("ETag"))?.let(WebDavXmlParser::cleanEtag),
                    fileId = response.header("OC-FileId"),
                ),
            )
        } else {
            AetherResult.Failure(response.toError())
        }

    companion object {
        /** Files above this size use chunked upload (CLAUDE.md: > 10 MB). */
        const val CHUNKED_THRESHOLD = 10L * 1024 * 1024
        const val CHUNK_SIZE = 10L * 1024 * 1024
        private const val BUFFER_SIZE = 64 * 1024
        private val XML = "application/xml; charset=utf-8".toMediaTypeOrNull()
        private val OCTET_STREAM = "application/octet-stream".toMediaTypeOrNull()

        private val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
              <d:prop>
                <d:resourcetype/>
                <d:getcontentlength/>
                <d:getcontenttype/>
                <d:getlastmodified/>
                <d:getetag/>
                <oc:fileid/>
                <oc:permissions/>
                <oc:size/>
                <nc:has-preview/>
                <oc:favorite/>
              </d:prop>
            </d:propfind>
        """.trimIndent()
    }
}

/** Converts a server `href` (percent-encoded path or full URL) into `/Documents/notes.md` relative to the files root. */
fun relativeRemotePath(href: String, loginName: String): String {
    val url = if (href.startsWith("http://") || href.startsWith("https://")) href else "http://placeholder$href"
    val segments = url.toHttpUrl().pathSegments
    val prefixEnd = segments.indices.firstOrNull {
        segments[it] == "dav" && segments.getOrNull(it + 1) == "files" && segments.getOrNull(it + 2) == loginName
    }?.let { it + 3 } ?: 0
    return "/" + segments.drop(prefixEnd).filter { it.isNotEmpty() }.joinToString("/")
}
