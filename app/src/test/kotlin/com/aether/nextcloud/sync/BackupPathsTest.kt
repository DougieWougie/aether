package com.aether.nextcloud.sync

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupPathsTest {
    private val utc = ZoneId.of("UTC")
    private val oct2026 = 1_792_484_100_000L // 2026-10-20T08:15:00Z

    @Test
    fun `files are filed by year and month`() {
        assertEquals("/Photos/Camera Uploads/2026/10/IMG_1.jpg", BackupPaths.remotePath("/Photos/Camera Uploads", oct2026, "IMG_1.jpg", utc))
    }

    @Test
    fun `folder input is normalised`() {
        assertEquals("/Photos/2026/10/a.jpg", BackupPaths.remotePath("Photos/", oct2026, "a.jpg", utc))
        assertEquals("/2026/10/a.jpg", BackupPaths.remotePath("/", oct2026, "a.jpg", utc))
    }

    @Test
    fun `month follows the zone`() {
        val lateNight = 1_793_491_200_000L - 1 // 2026-10-31T23:59:59.999Z
        assertEquals("/B/2026/10/a.jpg", BackupPaths.remotePath("/B", lateNight, "a.jpg", utc))
        assertEquals("/B/2026/11/a.jpg", BackupPaths.remotePath("/B", lateNight, "a.jpg", ZoneId.of("Asia/Tokyo")))
    }

    @Test
    fun `directories to create are listed shallowest first`() {
        assertEquals(
            listOf("/Photos", "/Photos/Camera Uploads", "/Photos/Camera Uploads/2026", "/Photos/Camera Uploads/2026/10"),
            BackupPaths.directoriesFor("/Photos/Camera Uploads/2026/10/IMG_1.jpg"),
        )
    }

    @Test
    fun `media id disambiguates names`() {
        assertEquals("IMG_1_42.jpg", BackupPaths.withMediaId("IMG_1.jpg", 42))
        assertEquals("noext_7", BackupPaths.withMediaId("noext", 7))
        assertEquals("archive.tar_7.gz", BackupPaths.withMediaId("archive.tar.gz", 7))
    }
}
