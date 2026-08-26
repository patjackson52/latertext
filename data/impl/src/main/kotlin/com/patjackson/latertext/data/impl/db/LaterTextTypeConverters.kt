package com.patjackson.latertext.data.impl.db

import androidx.room.TypeConverter
import com.patjackson.latertext.data.api.AttachmentIntakeSource
import com.patjackson.latertext.data.api.AttachmentState
import com.patjackson.latertext.data.api.AttachmentStorageClass
import com.patjackson.latertext.data.api.CallbackKind
import com.patjackson.latertext.data.api.CallbackTokenState
import com.patjackson.latertext.data.api.DeliveryOutcome
import com.patjackson.latertext.data.api.DraftState
import com.patjackson.latertext.data.api.DstResolution
import com.patjackson.latertext.data.api.EndCondition
import com.patjackson.latertext.data.api.MissedPolicy
import com.patjackson.latertext.data.api.MonthlyEdgePolicy
import com.patjackson.latertext.data.api.NotificationRecordState
import com.patjackson.latertext.data.api.OccurrenceState
import com.patjackson.latertext.data.api.OutboxState
import com.patjackson.latertext.data.api.PartOutcome
import com.patjackson.latertext.data.api.RecipientSource
import com.patjackson.latertext.data.api.RecurrenceFrequency
import com.patjackson.latertext.data.api.ScheduleState
import com.patjackson.latertext.data.api.SendOutcome
import com.patjackson.latertext.data.api.TransportMode
import com.patjackson.latertext.data.api.ZonePolicy

/** Explicit enum-name converters make the on-disk representation stable and reviewable. */
class LaterTextTypeConverters {
    @TypeConverter fun recipientSourceToString(value: RecipientSource): String = value.name
    @TypeConverter fun stringToRecipientSource(value: String): RecipientSource = enumValueOf(value)

    @TypeConverter fun scheduleStateToString(value: ScheduleState): String = value.name
    @TypeConverter fun stringToScheduleState(value: String): ScheduleState = enumValueOf(value)

    @TypeConverter fun transportModeToString(value: TransportMode): String = value.name
    @TypeConverter fun stringToTransportMode(value: String): TransportMode = enumValueOf(value)

    @TypeConverter fun intakeSourceToString(value: AttachmentIntakeSource): String = value.name
    @TypeConverter fun stringToIntakeSource(value: String): AttachmentIntakeSource = enumValueOf(value)

    @TypeConverter fun storageClassToString(value: AttachmentStorageClass): String = value.name
    @TypeConverter fun stringToStorageClass(value: String): AttachmentStorageClass = enumValueOf(value)

    @TypeConverter fun attachmentStateToString(value: AttachmentState): String = value.name
    @TypeConverter fun stringToAttachmentState(value: String): AttachmentState = enumValueOf(value)

    @TypeConverter fun recurrenceFrequencyToString(value: RecurrenceFrequency): String = value.name
    @TypeConverter fun stringToRecurrenceFrequency(value: String): RecurrenceFrequency = enumValueOf(value)

    @TypeConverter fun zonePolicyToString(value: ZonePolicy): String = value.name
    @TypeConverter fun stringToZonePolicy(value: String): ZonePolicy = enumValueOf(value)

    @TypeConverter fun monthlyEdgePolicyToString(value: MonthlyEdgePolicy): String = value.name
    @TypeConverter fun stringToMonthlyEdgePolicy(value: String): MonthlyEdgePolicy = enumValueOf(value)

    @TypeConverter fun endConditionToString(value: EndCondition): String = value.name
    @TypeConverter fun stringToEndCondition(value: String): EndCondition = enumValueOf(value)

    @TypeConverter fun missedPolicyToString(value: MissedPolicy): String = value.name
    @TypeConverter fun stringToMissedPolicy(value: String): MissedPolicy = enumValueOf(value)

    @TypeConverter fun dstResolutionToString(value: DstResolution): String = value.name
    @TypeConverter fun stringToDstResolution(value: String): DstResolution = enumValueOf(value)

    @TypeConverter fun occurrenceStateToString(value: OccurrenceState): String = value.name
    @TypeConverter fun stringToOccurrenceState(value: String): OccurrenceState = enumValueOf(value)

    @TypeConverter fun sendOutcomeToString(value: SendOutcome): String = value.name
    @TypeConverter fun stringToSendOutcome(value: String): SendOutcome = enumValueOf(value)

    @TypeConverter fun deliveryOutcomeToString(value: DeliveryOutcome): String = value.name
    @TypeConverter fun stringToDeliveryOutcome(value: String): DeliveryOutcome = enumValueOf(value)

    @TypeConverter fun partOutcomeToString(value: PartOutcome): String = value.name
    @TypeConverter fun stringToPartOutcome(value: String): PartOutcome = enumValueOf(value)

    @TypeConverter fun callbackKindToString(value: CallbackKind): String = value.name
    @TypeConverter fun stringToCallbackKind(value: String): CallbackKind = enumValueOf(value)

    @TypeConverter fun callbackTokenStateToString(value: CallbackTokenState): String = value.name
    @TypeConverter fun stringToCallbackTokenState(value: String): CallbackTokenState = enumValueOf(value)

    @TypeConverter fun draftStateToString(value: DraftState): String = value.name
    @TypeConverter fun stringToDraftState(value: String): DraftState = enumValueOf(value)

    @TypeConverter fun notificationStateToString(value: NotificationRecordState): String = value.name
    @TypeConverter fun stringToNotificationState(value: String): NotificationRecordState = enumValueOf(value)

    @TypeConverter fun outboxStateToString(value: OutboxState): String = value.name
    @TypeConverter fun stringToOutboxState(value: String): OutboxState = enumValueOf(value)
}
