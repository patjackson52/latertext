package com.patjackson.latertext.feature.composer

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComposerUiStateTest {
    @Test
    fun `copy recomputes submit readiness from current fields`() {
        val initial = ComposerUiState()

        val ready = initial.copy(recipient = "+15551234567", message = "Hello")

        assertFalse(initial.canSubmit)
        assertTrue(ready.canSubmit)
        assertFalse(ready.copy(importInProgress = true).canSubmit)
    }

    @Test
    fun `attachment permits an empty text body`() {
        assertTrue(
            ComposerUiState(
                recipient = "+15551234567",
                attachmentLabel = "GIF · 20 KB",
            ).canSubmit,
        )
    }
}
