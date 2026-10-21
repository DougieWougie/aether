package com.aether.nextcloud.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingPipeHelperTest {
    @Test
    fun `write modes`() {
        listOf("w", "wt", "wa", "rw", "rwt").forEach { assertTrue(it, StreamingPipeHelper.isWriteMode(it)) }
        assertFalse(StreamingPipeHelper.isWriteMode("r"))
    }

    @Test
    fun `only rw and wa need the current content`() {
        assertTrue(StreamingPipeHelper.needsExistingContent("rw"))
        assertTrue(StreamingPipeHelper.needsExistingContent("wa"))
        assertFalse(StreamingPipeHelper.needsExistingContent("w"))
        assertFalse(StreamingPipeHelper.needsExistingContent("wt"))
        assertFalse(StreamingPipeHelper.needsExistingContent("rwt"))
    }

    @Test
    fun `truncating modes`() {
        assertTrue(StreamingPipeHelper.truncates("w"))
        assertTrue(StreamingPipeHelper.truncates("wt"))
        assertTrue(StreamingPipeHelper.truncates("rwt"))
        assertFalse(StreamingPipeHelper.truncates("rw"))
        assertFalse(StreamingPipeHelper.truncates("wa"))
    }
}
