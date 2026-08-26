package com.patjackson.latertext.transport.automatic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

abstract class BaseSmsResultReceiver(
    private val callbackKind: String,
) : BroadcastReceiver() {
    final override fun onReceive(context: Context, intent: Intent) {
        val attemptId = intent.getStringExtra(AndroidAutomaticSmsGateway.EXTRA_ATTEMPT_ID) ?: return
        val partIndex = intent.getIntExtra(AndroidAutomaticSmsGateway.EXTRA_PART_INDEX, -1)
        val totalParts = intent.getIntExtra(AndroidAutomaticSmsGateway.EXTRA_TOTAL_PARTS, -1)
        if (partIndex !in 0 until totalParts) return

        context.sendBroadcast(
            Intent(AndroidAutomaticSmsGateway.ACTION_PROCESS_SMS_CALLBACK).apply {
                `package` = context.packageName
                putExtra(AndroidAutomaticSmsGateway.EXTRA_ATTEMPT_ID, attemptId)
                putExtra(AndroidAutomaticSmsGateway.EXTRA_PART_INDEX, partIndex)
                putExtra(AndroidAutomaticSmsGateway.EXTRA_TOTAL_PARTS, totalParts)
                putExtra(AndroidAutomaticSmsGateway.EXTRA_CALLBACK_KIND, callbackKind)
                putExtra(AndroidAutomaticSmsGateway.EXTRA_RESULT_CODE, resultCode)
            },
        )
    }
}

class SmsSentResultReceiver : BaseSmsResultReceiver("SENT")

class SmsDeliveryResultReceiver : BaseSmsResultReceiver("DELIVERED")
