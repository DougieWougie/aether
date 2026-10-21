package com.aether.nextcloud.dav

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * Streams `[offset, offset + length)` of [source]. [onWritten] receives the bytes written so far *for this body*,
 * so an OkHttp retry that replays the body restarts the count instead of double-counting.
 */
internal class SourceSliceRequestBody(
    private val source: UploadSource,
    private val offset: Long,
    private val length: Long,
    private val contentType: MediaType?,
    private val onWritten: (Long) -> Unit = {},
) : RequestBody() {
    override fun contentType() = contentType

    override fun contentLength() = length

    override fun writeTo(sink: BufferedSink) {
        val buffer = ByteArray(BUFFER_SIZE)
        var written = 0L
        source.open(offset).use { input ->
            while (written < length) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), length - written).toInt())
                if (read < 0) throw java.io.EOFException("Source ended after $written of $length bytes")
                sink.write(buffer, 0, read)
                written += read
                onWritten(written)
            }
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
