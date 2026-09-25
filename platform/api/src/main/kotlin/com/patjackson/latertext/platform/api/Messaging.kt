package com.patjackson.latertext.platform.api

enum class SmsPartResultCode {
    ACCEPTED,
    RADIO_OFF,
    NO_SERVICE,
    LIMIT_EXCEEDED,
    FDN_BLOCKED,
    INVALID_ARGUMENTS,
    MODEM_ERROR,
    NETWORK_ERROR,
    PERMISSION_DENIED,
    UNKNOWN_ERROR,
}

data class SmsSendRequest(
    val attemptId: String,
    val recipientAddress: String,
    val body: String,
    val subscriptionId: Int?,
    val requestDeliveryReport: Boolean,
)

data class SmsEnqueueResult(
    val partCount: Int,
    val acceptedByPlatform: Boolean,
    val immediateError: String? = null,
)

data class MmsCapability(
    val supported: Boolean,
    val maxMessageBytes: Int,
    val maxImageWidth: Int,
    val maxImageHeight: Int,
    val maxTextBytes: Int = 30 * 1_024,
    val reason: String? = null,
) {
    fun acceptsText(byteCount: Int): Boolean = supported && byteCount in 0..maxTextBytes

    fun accepts(
        mimeType: String,
        byteCount: Long,
        widthPixels: Int?,
        heightPixels: Int?,
    ): Boolean {
        if (!supported || mimeType.lowercase() !in SUPPORTED_IMAGE_TYPES) return false
        if (byteCount <= 0 || byteCount + PDU_OVERHEAD_RESERVE_BYTES > maxMessageBytes) return false
        if (widthPixels == null || heightPixels == null) return true
        return (widthPixels <= maxImageWidth && heightPixels <= maxImageHeight) ||
            (widthPixels <= maxImageHeight && heightPixels <= maxImageWidth)
    }

    /** Static images can be resized/re-encoded; animated GIFs must already fit unchanged. */
    fun canPrepare(
        mimeType: String,
        byteCount: Long,
        widthPixels: Int?,
        heightPixels: Int?,
        isAnimated: Boolean = mimeType.equals("image/gif", ignoreCase = true),
    ): Boolean {
        val normalizedType = mimeType.lowercase()
        if (!supported || normalizedType !in PREPARABLE_IMAGE_TYPES) return false
        if (byteCount <= 0 || byteCount > MAX_SOURCE_IMAGE_BYTES) return false
        if (isAnimated) return accepts(mimeType, byteCount, widthPixels, heightPixels)
        return widthPixels != null && heightPixels != null && widthPixels > 0 && heightPixels > 0
    }

    companion object {
        private const val PDU_OVERHEAD_RESERVE_BYTES = 16 * 1_024L
        private const val MAX_SOURCE_IMAGE_BYTES = 25 * 1_024 * 1_024L
        private val SUPPORTED_IMAGE_TYPES = setOf("image/jpeg", "image/jpg", "image/png", "image/gif")
        private val PREPARABLE_IMAGE_TYPES = SUPPORTED_IMAGE_TYPES + "image/webp"
    }
}

data class MmsSendRequest(
    val attemptId: String,
    val recipientAddress: String,
    val body: String,
    val attachmentBytes: ByteArray,
    val attachmentMimeType: String,
    val attachmentIsAnimated: Boolean,
    val attachmentWidthPixels: Int?,
    val attachmentHeightPixels: Int?,
    val subscriptionId: Int,
)

data class MmsEnqueueResult(
    val acceptedByPlatform: Boolean,
    val pduByteCount: Int = 0,
    val immediateError: String? = null,
)

interface AutomaticSmsGateway {
    fun divideMessage(body: String, subscriptionId: Int?): List<String>
    fun enqueue(request: SmsSendRequest): SmsEnqueueResult
    fun mmsCapability(subscriptionId: Int): MmsCapability
    fun enqueueMms(request: MmsSendRequest): MmsEnqueueResult
    fun cleanupMmsPayload(attemptId: String)
}

data class SubscriptionSummary(
    val subscriptionId: Int,
    val displayName: String,
    val carrierName: String?,
    val isDefaultForSms: Boolean,
)

interface SubscriptionGateway {
    fun activeSubscriptions(): List<SubscriptionSummary>
    fun defaultSmsSubscriptionId(): Int?
    fun isActive(subscriptionId: Int): Boolean
}

enum class AssistedHandoffKind {
    TEXT,
    MEDIA,
}

data class AssistedHandoffRequest(
    val kind: AssistedHandoffKind,
    val recipientAddress: String,
    val body: String,
    val attachmentPath: String? = null,
    val attachmentMimeType: String? = null,
)

interface AssistedMessagingGateway {
    /** Returns false when no compatible messaging activity can be resolved. */
    fun open(request: AssistedHandoffRequest): Boolean
}
