package com.patjackson.latertext.core.model

import java.time.Duration
import java.time.Instant

data class PhoneAddress(
    val enteredValue: String,
    val normalizedValue: String,
) {
    init {
        require(enteredValue.isNotBlank()) { "Entered phone address must not be blank" }
        require(normalizedValue.isNotBlank()) { "Normalized phone address must not be blank" }
    }
}

data class RecipientEndpoint(
    val id: RecipientId,
    val address: PhoneAddress,
    val displayName: String? = null,
    val numberLabel: String? = null,
)

data class ContentRevision(
    val id: ContentRevisionId,
    val text: String,
    val attachmentId: AttachmentId? = null,
    val createdAt: Instant,
) {
    init { require(text.isNotBlank() || attachmentId != null) { "Content cannot be empty" } }

    val hasMedia: Boolean get() = attachmentId != null
}

data class RuleRevision(
    val id: RuleRevisionId,
    val rule: RecurrenceRule,
    val createdAt: Instant,
)

enum class SubscriptionSelection { DEFAULT_AT_SEND_TIME, SPECIFIC_SUBSCRIPTION }

data class ScheduleConfiguration(
    val scheduleId: ScheduleId,
    val recipientId: RecipientId,
    val contentRevisionId: ContentRevisionId,
    val ruleRevisionId: RuleRevisionId,
    val transport: TransportKind,
    val subscriptionSelection: SubscriptionSelection = SubscriptionSelection.DEFAULT_AT_SEND_TIME,
    val subscriptionId: Int? = null,
    val missedPolicy: MissedPolicy = MissedPolicy.SEND_AS_SOON_AS_POSSIBLE,
    val gracePeriod: Duration = Duration.ofHours(4),
) {
    init {
        require(!gracePeriod.isNegative) { "Grace period cannot be negative" }
        require(
            (subscriptionSelection == SubscriptionSelection.SPECIFIC_SUBSCRIPTION) ==
                (subscriptionId != null),
        ) { "Specific subscription selection and ID must be supplied together" }
    }
}

data class GlobalPauseSnapshot(
    val paused: Boolean,
    val changedAt: Instant,
)
