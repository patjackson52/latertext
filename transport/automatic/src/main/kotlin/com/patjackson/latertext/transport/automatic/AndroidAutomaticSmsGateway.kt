package com.patjackson.latertext.transport.automatic

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.patjackson.latertext.platform.api.AutomaticSmsGateway
import com.patjackson.latertext.platform.api.MmsCapability
import com.patjackson.latertext.platform.api.MmsEnqueueResult
import com.patjackson.latertext.platform.api.MmsSendRequest
import com.patjackson.latertext.platform.api.SmsEnqueueResult
import com.patjackson.latertext.platform.api.SmsSendRequest
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayList

class AndroidAutomaticSmsGateway(
    private val context: Context,
    private val callbackReceiverClassName: String,
) : AutomaticSmsGateway {
    init {
        require(callbackReceiverClassName.isNotBlank())
    }

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
                action = ACTION_SMS_SENT_RESULT,
                attemptId = request.attemptId,
                partIndex = index,
                totalParts = parts.size,
            )
            if (request.requestDeliveryReport) {
                delivered += callbackIntent(
                    action = ACTION_SMS_DELIVERY_RESULT,
                    attemptId = request.attemptId,
                    partIndex = index,
                    totalParts = parts.size,
                )
            }
        }

        return runCatching {
            val diagnosticMessageId = smsDiagnosticMessageId(request.attemptId)
            if (parts.size == 1) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    smsManager.sendTextMessage(
                        request.recipientAddress,
                        null,
                        parts.single(),
                        sent.single(),
                        delivered.singleOrNull(),
                        diagnosticMessageId,
                    )
                } else {
                    smsManager.sendTextMessage(
                        request.recipientAddress,
                        null,
                        parts.single(),
                        sent.single(),
                        delivered.singleOrNull(),
                    )
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    smsManager.sendMultipartTextMessage(
                        request.recipientAddress,
                        null,
                        parts,
                        sent,
                        delivered.takeIf { it.isNotEmpty() },
                        diagnosticMessageId,
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
            }
            SmsEnqueueResult(parts.size, true)
        }.getOrElse { error ->
            SmsEnqueueResult(parts.size, false, error.message ?: error.javaClass.simpleName)
        }
    }

    override fun mmsCapability(subscriptionId: Int): MmsCapability {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)) {
            return MmsCapability(false, 0, 0, 0, reason = "telephony_messaging_unavailable")
        }
        val config = runCatching { manager(subscriptionId).carrierConfigValues }.getOrElse {
            return MmsCapability(false, 0, 0, 0, reason = "carrier_config_unavailable")
        }
        if (!config.getBoolean(SmsManager.MMS_CONFIG_MMS_ENABLED, false)) {
            return MmsCapability(false, 0, 0, 0, reason = "mms_disabled_by_carrier")
        }
        return MmsCapability(
            supported = true,
            maxMessageBytes = config.positiveInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, DEFAULT_MAX_MMS_BYTES),
            maxImageWidth = config.positiveInt(SmsManager.MMS_CONFIG_MAX_IMAGE_WIDTH, DEFAULT_MAX_IMAGE_WIDTH),
            maxImageHeight = config.positiveInt(SmsManager.MMS_CONFIG_MAX_IMAGE_HEIGHT, DEFAULT_MAX_IMAGE_HEIGHT),
            maxTextBytes = config.positiveInt(SmsManager.MMS_CONFIG_MESSAGE_TEXT_MAX_SIZE, DEFAULT_MAX_TEXT_BYTES),
        )
    }

    override fun enqueueMms(request: MmsSendRequest): MmsEnqueueResult {
        if (request.recipientAddress.isBlank() || request.attachmentBytes.isEmpty()) {
            return MmsEnqueueResult(false, immediateError = "Recipient and attachment are required")
        }
        val capability = mmsCapability(request.subscriptionId)
        val textByteCount = request.body.toByteArray(Charsets.UTF_8).size
        if (!capability.acceptsText(textByteCount)) {
            return MmsEnqueueResult(false, immediateError = "Message text exceeds the carrier MMS limit")
        }
        if (!capability.canPrepare(
                request.attachmentMimeType,
                request.attachmentBytes.size.toLong(),
                request.attachmentWidthPixels,
                request.attachmentHeightPixels,
                request.attachmentIsAnimated,
            )
        ) return MmsEnqueueResult(false, immediateError = capability.reason ?: "Attachment exceeds carrier MMS limits")

        return runCatching {
            pruneStaleMmsPayloads()
            val prepared = MmsAttachmentPreparer.prepare(
                sourceBytes = request.attachmentBytes,
                sourceMimeType = request.attachmentMimeType,
                capability = capability,
                textByteCount = textByteCount,
                isAnimated = request.attachmentIsAnimated,
            )
            val pdu = MmsPduComposer(context).compose(
                recipientAddress = request.recipientAddress,
                text = request.body,
                attachmentBytes = prepared.bytes,
                attachmentMimeType = prepared.mimeType,
            )
            if (pdu.size > capability.maxMessageBytes) {
                return MmsEnqueueResult(
                    false,
                    pduByteCount = pdu.size,
                    immediateError = "Encoded MMS exceeds the carrier limit of ${capability.maxMessageBytes} bytes",
                )
            }
            val pduFile = writeMmsPayload(request.attemptId, pdu)
            val pduUri = FileProvider.getUriForFile(context, "${context.packageName}.files", pduFile)
            val sentIntent = callbackIntent(
                action = ACTION_MMS_SENT_RESULT,
                attemptId = request.attemptId,
                partIndex = 0,
                totalParts = 1,
            )
            val smsManager = manager(request.subscriptionId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                smsManager.sendMultimediaMessage(
                    context,
                    pduUri,
                    null,
                    null,
                    sentIntent,
                    smsDiagnosticMessageId(request.attemptId),
                )
            } else {
                smsManager.sendMultimediaMessage(context, pduUri, null, null, sentIntent)
            }
            MmsEnqueueResult(true, pdu.size)
        }.getOrElse { error ->
            cleanupMmsPayload(request.attemptId)
            MmsEnqueueResult(false, immediateError = error.message ?: error.javaClass.simpleName)
        }
    }

    override fun cleanupMmsPayload(attemptId: String) {
        mmsPayloadFile(attemptId).delete()
        File(mmsDirectory(), "${mmsPayloadName(attemptId)}.tmp").delete()
    }

    @Suppress("DEPRECATION")
    private fun manager(subscriptionId: Int?): SmsManager {
        val defaultManager = context.getSystemService(SmsManager::class.java)
        if (subscriptionId == null) return defaultManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            defaultManager.createForSubscriptionId(subscriptionId)
        } else {
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }
    }

    private fun callbackIntent(
        action: String,
        attemptId: String,
        partIndex: Int,
        totalParts: Int,
    ): PendingIntent {
        val intent = Intent().setClassName(context, callbackReceiverClassName).apply {
            this.action = action
            data = "latertext://message/$attemptId/$partIndex/$action".toUri()
            putExtra(EXTRA_ATTEMPT_ID, attemptId)
            putExtra(EXTRA_PART_INDEX, partIndex)
            putExtra(EXTRA_TOTAL_PARTS, totalParts)
        }
        return PendingIntent.getBroadcast(
            context,
            31 * attemptId.hashCode() + partIndex + action.hashCode(),
            intent,
            smsCallbackPendingIntentFlags(Build.VERSION.SDK_INT),
        )
    }

    private fun writeMmsPayload(attemptId: String, bytes: ByteArray): File {
        val target = mmsPayloadFile(attemptId)
        val temporary = File(mmsDirectory(), "${mmsPayloadName(attemptId)}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        check(!target.exists() || target.delete()) { "Unable to replace prior MMS payload" }
        check(temporary.renameTo(target)) { "Unable to publish MMS payload" }
        return target
    }

    private fun mmsDirectory(): File = File(context.cacheDir, MMS_CACHE_DIRECTORY).apply {
        check(isDirectory || mkdirs()) { "Unable to create MMS cache directory" }
    }

    private fun mmsPayloadFile(attemptId: String): File = File(mmsDirectory(), "${mmsPayloadName(attemptId)}.pdu")

    private fun mmsPayloadName(attemptId: String): String = smsDiagnosticMessageId(attemptId).toULong().toString(16)

    private fun pruneStaleMmsPayloads(nowEpochMillis: Long = System.currentTimeMillis()) {
        mmsDirectory().listFiles().orEmpty()
            .filter { it.isFile && nowEpochMillis - it.lastModified() > MMS_CACHE_MAX_AGE_MILLIS }
            .forEach(File::delete)
    }

    companion object {
        const val ACTION_SMS_SENT_RESULT = "com.patjackson.latertext.action.SMS_SENT_RESULT"
        const val ACTION_SMS_DELIVERY_RESULT = "com.patjackson.latertext.action.SMS_DELIVERY_RESULT"
        const val ACTION_MMS_SENT_RESULT = "com.patjackson.latertext.action.MMS_SENT_RESULT"
        const val EXTRA_ATTEMPT_ID = "attempt_id"
        const val EXTRA_PART_INDEX = "part_index"
        const val EXTRA_TOTAL_PARTS = "total_parts"
        private const val DEFAULT_MAX_MMS_BYTES = 300 * 1_024
        private const val DEFAULT_MAX_IMAGE_WIDTH = 640
        private const val DEFAULT_MAX_IMAGE_HEIGHT = 480
        private const val DEFAULT_MAX_TEXT_BYTES = 30 * 1_024
        private const val MMS_CACHE_DIRECTORY = "latertext_mms"
        private const val MMS_CACHE_MAX_AGE_MILLIS = 48L * 60 * 60 * 1_000
    }
}

private fun android.os.Bundle.positiveInt(key: String, fallback: Int): Int =
    getInt(key, fallback).takeIf { it > 0 } ?: fallback

internal fun smsCallbackPendingIntentFlags(
    @Suppress("UNUSED_PARAMETER") apiLevel: Int,
): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

/** Stable, non-secret ID used only to correlate the request in Android telephony diagnostics. */
internal fun smsDiagnosticMessageId(attemptId: String): Long =
    attemptId.fold(1_125_899_906_842_597L) { value, character ->
        31L * value + character.code
    }
