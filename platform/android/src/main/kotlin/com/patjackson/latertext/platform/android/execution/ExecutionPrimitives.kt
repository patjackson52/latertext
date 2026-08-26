package com.patjackson.latertext.platform.android.execution

import com.patjackson.latertext.data.api.CallbackKind
import java.util.UUID

fun interface ExecutionIdFactory {
    fun newId(): String
}

class UuidExecutionIdFactory : ExecutionIdFactory {
    override fun newId(): String = UUID.randomUUID().toString()
}

internal object SmsCallbackToken {
    fun create(attemptId: String, partIndex: Int, kind: CallbackKind): String {
        require(attemptId.isNotBlank())
        require(partIndex >= 0)
        return "sms:$attemptId:$partIndex:${kind.name}"
    }
}

internal fun notificationId(occurrenceId: String, discriminator: String): Int =
    (31 * occurrenceId.hashCode() + discriminator.hashCode()).and(Int.MAX_VALUE).coerceAtLeast(1)
