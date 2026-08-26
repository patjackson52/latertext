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

interface AutomaticSmsGateway {
    fun divideMessage(body: String, subscriptionId: Int?): List<String>
    fun enqueue(request: SmsSendRequest): SmsEnqueueResult
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
