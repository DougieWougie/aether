package com.aether.nextcloud.sync

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class TransferNotificationHelperTest {
    @Test
    fun `percent is whole numbers of the total`() {
        assertEquals(0, TransferNotificationHelper.percent(0, 100))
        assertEquals(33, TransferNotificationHelper.percent(1, 3))
        assertEquals(100, TransferNotificationHelper.percent(100, 100))
    }

    @Test
    fun `an unknown or zero total reads as zero rather than dividing by it`() {
        assertEquals(0, TransferNotificationHelper.percent(50, -1))
        assertEquals(0, TransferNotificationHelper.percent(50, 0))
    }

    @Test
    fun `more bytes than expected never exceeds a hundred and large sizes do not overflow`() {
        assertEquals(100, TransferNotificationHelper.percent(150, 100))
        assertEquals(50, TransferNotificationHelper.percent(Long.MAX_VALUE / 200, Long.MAX_VALUE / 100))
    }

    @Test
    fun `the same work always gets the same notification id`() {
        val id = UUID.randomUUID()

        assertEquals(TransferNotificationHelper.notificationId(id), TransferNotificationHelper.notificationId(UUID.fromString(id.toString())))
    }
}
