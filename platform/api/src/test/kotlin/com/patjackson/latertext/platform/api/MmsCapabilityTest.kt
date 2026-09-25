package com.patjackson.latertext.platform.api

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MmsCapabilityTest {
    private val capability = MmsCapability(
        supported = true,
        maxMessageBytes = 1_048_576,
        maxImageWidth = 2_592,
        maxImageHeight = 1_944,
    )

    @Test
    fun `accepts common MMS images within carrier bounds in either orientation`() {
        assertTrue(capability.accepts("image/jpeg", 100_000, 2_592, 1_944))
        assertTrue(capability.accepts("image/gif", 100_000, 1_944, 2_592))
    }

    @Test
    fun `rejects unsupported media dimensions and payloads without headroom`() {
        assertFalse(capability.accepts("image/webp", 100_000, 100, 100))
        assertFalse(capability.accepts("image/png", 100_000, 4_000, 3_000))
        assertFalse(capability.accepts("image/png", 1_040_000, 100, 100))
    }

    @Test
    fun `static images can be prepared while oversized GIFs cannot`() {
        assertTrue(capability.canPrepare("image/jpeg", 8_000_000, 4_000, 3_000))
        assertTrue(capability.canPrepare("image/png", 8_000_000, 4_000, 3_000))
        assertTrue(capability.canPrepare("image/webp", 8_000_000, 4_000, 3_000, isAnimated = false))
        assertTrue(capability.canPrepare("image/gif", 8_000_000, 4_000, 3_000, isAnimated = false))
        assertFalse(capability.canPrepare("image/webp", 100_000, 100, 100, isAnimated = true))
        assertFalse(capability.canPrepare("image/gif", 8_000_000, 4_000, 3_000))
    }
}
