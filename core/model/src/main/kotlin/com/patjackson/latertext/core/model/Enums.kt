package com.patjackson.latertext.core.model

enum class RecurrenceFrequency { ONCE, DAILY, WEEKLY, MONTHLY }

enum class ZonePolicy { FOLLOW_DEVICE_ZONE, FIXED_ZONE }

enum class DstGapPolicy { SHIFT_TO_FIRST_VALID_TIME }

enum class DstOverlapPolicy { EARLIER_OFFSET }

enum class DstResolution { EXACT, GAP_SHIFTED_FORWARD, OVERLAP_EARLIER_OFFSET }

enum class MonthlyDayPolicy { SKIP_MONTH, LAST_DAY_OF_MONTH }

enum class MonthlyAdjustment { NONE, USED_LAST_DAY_OF_MONTH }

enum class MissedPolicy { SEND_AS_SOON_AS_POSSIBLE, ASK_ME, SKIP }

enum class ScheduleState { ACTIVE, PAUSED, NEEDS_ATTENTION, COMPLETED, CANCELLED }

enum class ScheduleAttentionReason {
    SMS_PERMISSION_MISSING,
    EXACT_ALARM_ACCESS_REVOKED,
    SIM_UNAVAILABLE,
    NOTIFICATION_ACTIONS_UNAVAILABLE,
    ATTACHMENT_MISSING,
    UNKNOWN,
}

enum class OccurrenceState {
    PLANNED,
    ARMED,
    DUE,
    CLAIMED,
    SENDING,
    RETRY_WAIT,
    READY_FOR_USER,
    OPENED_IN_LATER_TEXT,
    SHARED_TO_MESSAGING_APP,
    SENT_TO_CARRIER,
    DELIVERED,
    DELIVERY_FAILED,
    DELIVERY_UNAVAILABLE,
    FAILED_TERMINAL,
    PARTIAL_AMBIGUOUS,
    EXPIRED,
    SKIPPED_PAUSED,
    MISSED,
    CANCELLED,
}

enum class OccurrenceTrigger { SCHEDULED, RETRY, MANUAL }

enum class AttemptState {
    CREATED,
    AWAITING_SENT_CALLBACKS,
    ACCEPTED,
    RETRYABLE_FAILURE,
    AMBIGUOUS_FAILURE,
    TERMINAL_FAILURE,
    PARTIAL_AMBIGUOUS,
}

enum class PartSubmissionOutcome {
    ACCEPTED,
    FAILED_DEFINITE_PRE_ACCEPTANCE,
    FAILED_AMBIGUOUS,
    FAILED_TERMINAL,
}

enum class DeliveryPartOutcome { DELIVERED, FAILED }

enum class SendOutcome {
    NOT_STARTED,
    PENDING,
    SENT_TO_CARRIER,
    FAILED,
    PARTIAL_OR_AMBIGUOUS,
    SKIPPED,
    USER_ACTION_REQUIRED,
    SHARED_UNVERIFIED,
}

enum class DeliveryOutcome { NOT_REQUESTED, PENDING, DELIVERED, FAILED, UNAVAILABLE }

enum class TransportKind { AUTOMATIC_SMS, AUTOMATIC_MMS, ASSISTED_TEXT, ASSISTED_MEDIA }

enum class AssistedHandoffState {
    READY_FOR_USER,
    OPENED_IN_LATER_TEXT,
    SHARED_TO_MESSAGING_APP,
    EXPIRED,
}

enum class AttachmentSource { KEYBOARD, CLIPBOARD, PHOTO_PICKER, SHARE, DRAG_DROP }

/** Android result codes are translated into this platform-neutral vocabulary. */
enum class SmsFailureCode {
    NO_SERVICE,
    RADIO_OFF,
    NETWORK_ERROR,
    RETRYABLE_MODEM_ERROR,
    GENERIC_FAILURE,
    MODEM_ERROR,
    NULL_PDU,
    INVALID_ARGUMENTS,
    INVALID_ADDRESS,
    FDN_BLOCKED,
    SHORT_CODE_BLOCKED,
    LIMIT_EXCEEDED,
    SIM_UNAVAILABLE,
    PERMISSION_DENIED,
    UNKNOWN,
}

enum class FailureCertainty { DEFINITE_PRE_ACCEPTANCE, AMBIGUOUS, TERMINAL }

enum class RetryDisposition { RETRY_AT, DO_NOT_RETRY }

enum class RetryReason {
    DEFINITE_PRE_ACCEPTANCE_FAILURE,
    PART_ALREADY_ACCEPTED,
    AMBIGUOUS_RESULT,
    TERMINAL_FAILURE,
    ATTEMPT_LIMIT_REACHED,
    GRACE_DEADLINE_EXCEEDED,
    ATTEMPT_NOT_RETRYABLE,
}

val OccurrenceState.isTerminal: Boolean
    get() = this in setOf(
        OccurrenceState.SHARED_TO_MESSAGING_APP,
        OccurrenceState.DELIVERED,
        OccurrenceState.DELIVERY_FAILED,
        OccurrenceState.DELIVERY_UNAVAILABLE,
        OccurrenceState.FAILED_TERMINAL,
        OccurrenceState.PARTIAL_AMBIGUOUS,
        OccurrenceState.EXPIRED,
        OccurrenceState.SKIPPED_PAUSED,
        OccurrenceState.MISSED,
        OccurrenceState.CANCELLED,
    )

val ScheduleState.isTerminal: Boolean
    get() = this == ScheduleState.COMPLETED || this == ScheduleState.CANCELLED
