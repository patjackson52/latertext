package com.patjackson.latertext.platform.android.execution

import android.app.Activity
import android.telephony.SmsManager
import com.patjackson.latertext.core.model.SmsFailureCode
import com.patjackson.latertext.data.api.PartOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AndroidMmsResultMapperTest {
    @Test
    fun `success is carrier acceptance`() {
        assertEquals(PartOutcome.ACCEPTED, AndroidMmsResultMapper.sentPartOutcome(Activity.RESULT_OK))
    }

    @Test
    fun `platform retry signal is safe to retry`() {
        assertEquals(
            SmsFailureCode.NETWORK_ERROR,
            AndroidMmsResultMapper.failureCode(SmsManager.MMS_ERROR_RETRY),
        )
    }

    @Test
    fun `lost MMSC response is ambiguous`() {
        assertEquals(
            SmsFailureCode.UNKNOWN,
            AndroidMmsResultMapper.failureCode(SmsManager.MMS_ERROR_HTTP_FAILURE),
        )
        assertEquals(
            SmsFailureCode.UNKNOWN,
            AndroidMmsResultMapper.failureCode(SmsManager.MMS_ERROR_IO_ERROR),
        )
    }

    @Test
    fun `carrier configuration and subscription errors are terminal`() {
        assertEquals(
            SmsFailureCode.INVALID_ARGUMENTS,
            AndroidMmsResultMapper.failureCode(SmsManager.MMS_ERROR_INVALID_APN),
        )
        assertEquals(
            SmsFailureCode.SIM_UNAVAILABLE,
            AndroidMmsResultMapper.failureCode(SmsManager.MMS_ERROR_INACTIVE_SUBSCRIPTION),
        )
        assertEquals(
            SmsFailureCode.PERMISSION_DENIED,
            AndroidMmsResultMapper.failureCode(SmsManager.MMS_ERROR_MMS_DISABLED_BY_CARRIER),
        )
    }
}
