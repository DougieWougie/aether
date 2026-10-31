package com.aether.nextcloud.sync

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Where camera backup puts things: `<folder>/YYYY/MM/<name>` (ADR-0004). */
object BackupPaths {
    fun remotePath(folder: String, takenAtMillis: Long, fileName: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val date = Instant.ofEpochMilli(takenAtMillis).atZone(zone)
        val root = folder.trim().trimEnd('/').let { if (it.startsWith("/")) it else "/$it" }.let { if (it == "/") "" else it }
        return "$root/${YEAR.format(date)}/${MONTH.format(date)}/$fileName"
    }

    /** Folders to `MKCOL`, shallowest first, for [remotePath]'s parent. */
    fun directoriesFor(remotePath: String): List<String> {
        val segments = remotePath.split('/').filter { it.isNotEmpty() }.dropLast(1)
        return segments.indices.map { "/" + segments.subList(0, it + 1).joinToString("/") }
    }

    /** `IMG_001.jpg` → `IMG_001_42.jpg`; used when a different file already occupies the name. */
    fun withMediaId(fileName: String, mediaId: Long): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) + "_$mediaId" + fileName.substring(dot) else "${fileName}_$mediaId"
    }

    /** `AETHER_20261031_175742.jpg`: the name of a photo taken from the Camera tab. */
    fun captureName(takenAtMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "AETHER_${CAPTURE_STAMP.format(Instant.ofEpochMilli(takenAtMillis).atZone(zone))}.jpg"

    private val CAPTURE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    private val YEAR = DateTimeFormatter.ofPattern("yyyy")
    private val MONTH = DateTimeFormatter.ofPattern("MM")
}
