package com.aether.nextcloud.sync

import android.content.ContentResolver
import android.net.Uri
import com.aether.nextcloud.dav.UploadSource
import com.aether.nextcloud.dav.skipFully
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** Streams a `content://` item (MediaStore, shared files) straight to the server without a staging copy. */
class ContentUriUploadSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val length: Long,
    override val lastModified: Long?,
) : UploadSource {
    override fun open(offset: Long): InputStream {
        val descriptor = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException("Cannot open $uri")
        try {
            val input = FileInputStream(descriptor.fileDescriptor)
            if (offset > 0) {
                try {
                    input.channel.position(offset)
                } catch (_: IOException) {
                    input.skipFully(offset, uri.toString())
                }
            }
            return object : FilterInputStream(input) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        descriptor.close()
                    }
                }
            }
        } catch (e: Throwable) {
            descriptor.close()
            throw e
        }
    }
}
