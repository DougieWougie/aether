package com.aether.nextcloud.dav

import java.io.InputStream
import java.security.MessageDigest

/** SHA-1 checksums in the `SHA1:<hex>` form Nextcloud uses in `OC-Checksum` and `oc:checksums`. */
object Checksums {
    private const val SHA1 = "SHA1:"

    /** Reads [input] to the end (without closing it) and returns its `SHA1:<hex>`. */
    fun sha1(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return SHA1 + digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The SHA-1 among [checksums] as reported by the server, or null if it holds none (other algorithms are ignored). */
    fun sha1Of(checksums: List<String>): String? =
        checksums.firstOrNull { it.startsWith(SHA1, ignoreCase = true) }?.let { SHA1 + it.substring(SHA1.length).lowercase() }
}
