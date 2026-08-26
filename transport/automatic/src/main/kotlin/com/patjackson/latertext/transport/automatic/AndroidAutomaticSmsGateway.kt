package com.patjackson.latertext.transport.automatic

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import com.patjackson.latertext.platform.api.AutomaticSmsGateway
import com.patjackson.latertext.platform.api.SmsEnqueueResult
import com.patjackson.latertext.platform.api.SmsSendRequest
import java.util.ArrayList

class AndroidAutomaticSmsGateway(
    private val context: Context,
) : AutomaticSmsGateway {
    override fun divideMessage(body: String, subscriptionId: Int?): List<String> =
        manager(subscriptionId).divideMessage(body).orEmpty()

    override fun enqueue(request: SmsSendRequest): SmsEnqueueResult {
        if (request.recipientAddress.isBlank() || request.body.isBlank()) {
            return SmsEnqueueResult(0, false, "Recipient and message are required")
        }

        val smsManager = manager(request.subscriptionId)
        val parts = smsManager.divideMessage(request.body).orEmpty()
        if (parts.isEmpty()) return SmsEnqueueResult(0, false, "Message produced no SMS parts")

        val sent = ArrayList<PendingIntent>(parts.size)
        val delivered = ArrayList<PendingIntent>(parts.size)
        repeat(parts.size) { index ->
            sent += callbackIntent(
                receiver = SmsSentResultReceiver::class.java,
                action = ACTION_SMS_SENT_RESULT,
                request = request,
                partIndex = index,
                totalParts = parts.size,
            )
            if (request.requestDeliveryReport) {
                delivered += callbackIntent(
                    receiver = SmsDeliveryResultReceiver::class.java,
                    action = ACTION_SMS_DELIVERY_RESULT,
                    request = request,
                    partIndex = index,
                    totalParts = parts.size,
                )
            }
        }

        return runCatching {
            if (parts.size == 1) {
                smsManager.sendTextMessage(
                    request.recipientAddress,
                    null,
                    parts.single(),
                    sent.single(),
                    delivered.singleOrNull(),
                )
            } else {
                smsManager.sendMultipartTextMessage(
                    request.recipientAddress,
                    null,
                    ArrayList(parts),
                    sent,
                    delivered.takeIf { it.isNotEmpty() },
                )
            }
            SmsEnqueueResult(parts.size, true)
        }.getOrElse { error ->
            SmsEnqueueResult(parts.size, false, error.message ?: error.javaClass.simpleName)
        }
    }

    @Suppress("DEPRECATION")
    private fun manager(subscriptionId: Int?): SmsManager =
        if (subscriptionId == null) SmsManager.getDefault()
        else SmsManager.getSmsManagerForSubscriptionId(subscriptionId)

    private fun callbackIntent(
        receiver: Class<*>,
        action: String,
        request: SmsSendRequest,
        partIndex: Int,
        totalParts: Int,
    ): PendingIntent {
        val intent = Intent(context, receiver).apply {
            this.action = action
            data = Uri.parse("latertext://sms/${request.attemptId}/$partIndex/$action")
            putExtra(EXTRA_ATTEMPT_ID, request.attemptId)
            putExtra(EXTRA_PART_INDEX, partIndex)
            putExtra(EXTRA_TOTAL_PARTS, totalParts)
        }
        return PendingIntent.getBroadcast(
            context,
            31 * request.attemptId.hashCode() + partIndex + action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_SMS_SENT_RESULT = "com.patjackson.latertext.action.SMS_SENT_RESULT"
        const val ACTION_SMS_DELIVERY_RESULT = "com.patjackson.latertext.action.SMS_DELIVERY_RESULT"
        const val ACTION_PROCESS_SMS_CALLBACK =
            "com.patjackson.latertext.action.PROCESS_SMS_CALLBACK"
        const val EXTRA_ATTEMPT_ID = "attempt_id"
        const val EXTRA_PART_INDEX = "part_index"
        const val EXTRA_TOTAL_PARTS = "total_parts"
        const val EXTRA_CALLBACK_KIND = "callback_kind"
        const val EXTRA_RESULT_CODE = "result_code"
    }
}
