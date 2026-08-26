package com.patjackson.latertext.platform.android.execution

import android.app.Activity
import android.telephony.SmsManager
import com.patjackson.latertext.core.model.SmsFailureCode
import com.patjackson.latertext.data.api.PartOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AndroidSmsResultMapperTest {
    @Test
    fun `result ok is independently accepted for sent and delivery callbacks`() {
        assertEquals(PartOutcome.ACCEPTED, AndroidSmsResultMapper.sentPartOutcome(Activity.RESULT_OK))
        assertEquals(PartOutcome.ACCEPTED, AndroidSmsResultMapper.deliveryPartOutcome(Activity.RESULT_OK))
    }

    @Test
    fun `platform failures remain failed while classifier preserves retry meaning`() {
        assertEquals(
            PartOutcome.FAILED,
            AndroidSmsResultMapper.sentPartOutcome(SmsManager.RESULT_ERROR_NO_SERVICE),
        )
        assertEquals(
            SmsFailureCode.NO_SERVICE,
            AndroidSmsResultMapper.failureCode(SmsManager.RESULT_ERROR_NO_SERVICE),
        )
        assertEquals(
            SmsFailureCode.LIMIT_EXCEEDED,
            AndroidSmsResultMapper.failureCode(SmsManager.RESULT_ERROR_LIMIT_EXCEEDED),
        )
    }

    @Test
    fun `unrecognized result is ambiguous unknown rather than assumed retryable`() {
        assertEquals(SmsFailureCode.UNKNOWN, AndroidSmsResultMapper.failureCode(Int.MAX_VALUE))
    }
}
