package com.patjackson.latertext.transport.automatic

import android.app.PendingIntent
import android.os.Build
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmsCallbackPendingIntentFlagsTest {
    @Test
    fun `api 31 callback is immutable and updateable by its creator`() {
        val flags = smsCallbackPendingIntentFlags(Build.VERSION_CODES.S)

        assertTrue(flags and PendingIntent.FLAG_UPDATE_CURRENT != 0)
        assertTrue(flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertEquals(0, flags and PendingIntent.FLAG_ONE_SHOT)
        assertEquals(0, flags and PendingIntent.FLAG_MUTABLE)
    }

    @Test
    fun `pre api 31 uses the same secure replay-tolerant flags`() {
        assertEquals(
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            smsCallbackPendingIntentFlags(Build.VERSION_CODES.R),
        )
    }

    @Test
    fun `diagnostic message id is stable and attempt specific`() {
        assertEquals(smsDiagnosticMessageId("attempt-a"), smsDiagnosticMessageId("attempt-a"))
        assertTrue(smsDiagnosticMessageId("attempt-a") != smsDiagnosticMessageId("attempt-b"))
    }
}
