package com.aether.nextcloud.dav

import java.security.MessageDigest
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * Streams `[offset, offset + length)` of [source]. [onWritten] receives the bytes written so far *for this body*,
 * so an OkHttp retry that replays the body restarts the count instead of double-counting. When [baseDigest] is set, it is
 * cloned at the start of each [writeTo] attempt and handed to [onCompleteDigest] once all [length] bytes are written, so
 * connection replays never feed a chunk into the running hash twice.
 */
internal class SourceSliceRequestBody(
    private val source: UploadSource,
    private val offset: Long,
    private val length: Long,
    private val contentType: MediaType?,
    private val baseDigest: MessageDigest? = null,
    private val onCompleteDigest: (MessageDigest) -> Unit = {},
    private val onWritten: (Long) -> Unit = {},
) : RequestBody() {
    override fun contentType() = contentType

    override fun contentLength() = length

    override fun writeTo(sink: BufferedSink) {
        val attemptDigest = baseDigest?.let { it.clone() as MessageDigest }
        val buffer = ByteArray(BUFFER_SIZE)
        var written = 0L
        source.open(offset).use { input ->
            while (written < length) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), length - written).toInt())
                if (read < 0) throw java.io.EOFException("Source ended after $written of $length bytes")
                sink.write(buffer, 0, read)
                attemptDigest?.update(buffer, 0, read)
                written += read
                onWritten(written)
            }
        }
        if (attemptDigest != null) onCompleteDigest(attemptDigest)
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
