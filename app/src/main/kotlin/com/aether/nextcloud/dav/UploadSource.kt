package com.aether.nextcloud.dav

import java.io.File
import java.io.InputStream

/** Bytes to upload: a known [length] and the ability to start reading at any offset (for chunks and retries). */
interface UploadSource {
    val length: Long

    /** Last-modified time in epoch millis, forwarded as `X-OC-Mtime` so the server keeps the original timestamp. */
    val lastModified: Long?

    /** `SHA1:<hex>` of the content, sent as `OC-Checksum` so the server verifies the upload and keeps the hash for later comparisons. */
    val checksum: String? get() = null

    /** Opens a fresh stream positioned at [offset]; the caller closes it. */
    fun open(offset: Long): InputStream
}

class FileUploadSource(private val file: File) : UploadSource {
    override val length: Long get() = file.length()
    override val lastModified: Long? get() = file.lastModified().takeIf { it > 0 }

    override fun open(offset: Long): InputStream = file.inputStream().also { it.skipFully(offset, file.name) }
}

internal fun InputStream.skipFully(count: Long, name: String) {
    var remaining = count
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped <= 0) throw java.io.EOFException("Cannot seek to $count in $name")
        remaining -= skipped
    }
}
