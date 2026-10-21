package com.aether.nextcloud.sync

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** ADR-0004: `<filename> (Conflict <timestamp>).<ext>`. */
object ConflictNaming {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss").withZone(ZoneId.systemDefault())

    fun conflictName(fileName: String, nowMillis: Long): String {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val extension = if (dot > 0) fileName.substring(dot) else ""
        return "$base (Conflict ${formatter.format(Instant.ofEpochMilli(nowMillis))})$extension"
    }
}
