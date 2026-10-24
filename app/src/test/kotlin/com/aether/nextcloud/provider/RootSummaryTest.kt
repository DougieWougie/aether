package com.aether.nextcloud.provider

import org.junit.Test
import org.junit.Assert.assertEquals

class RootSummaryTest {
    @Test
    fun `a single account shows only the host`() {
        assertEquals("cloud.example.com", rootSummary("alice@cloud.example.com", 1))
    }

    @Test
    fun `several accounts show the full account name`() {
        assertEquals("alice@cloud.example.com", rootSummary("alice@cloud.example.com", 2))
    }
}
