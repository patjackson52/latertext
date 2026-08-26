package com.patjackson.latertext.core.domain.usecase

import com.patjackson.latertext.core.domain.recurrence.JitterPolicyValidator
import com.patjackson.latertext.core.model.ContentRevision
import com.patjackson.latertext.core.model.MissedPolicy
import com.patjackson.latertext.core.model.RecurrenceRule
import com.patjackson.latertext.core.model.ScheduleConfiguration
import com.patjackson.latertext.core.model.TransportKind
import java.time.DateTimeException
import java.time.ZoneId

enum class ScheduleValidationIssue {
    INVALID_ZONE_ID,
    JITTER_CAN_REORDER_OCCURRENCES,
    ASK_ME_REQUIRES_ACTION_NOTIFICATIONS,
    AUTOMATIC_SMS_REQUIRES_TEXT_ONLY,
    ASSISTED_MEDIA_REQUIRES_ATTACHMENT,
}

data class ScheduleValidationResult(
    val issues: Set<ScheduleValidationIssue>,
) {
    val valid: Boolean get() = issues.isEmpty()
}

class ScheduleDefinitionValidator(
    private val jitterValidator: JitterPolicyValidator = JitterPolicyValidator(),
) {
    fun validate(
        configuration: ScheduleConfiguration,
        content: ContentRevision,
        rule: RecurrenceRule,
        activeDeviceZone: ZoneId,
        actionNotificationsAvailable: Boolean,
    ): ScheduleValidationResult {
        val issues = linkedSetOf<ScheduleValidationIssue>()
        val selectedZone = try {
            when (rule.zonePolicy) {
                com.patjackson.latertext.core.model.ZonePolicy.FOLLOW_DEVICE_ZONE -> activeDeviceZone
                com.patjackson.latertext.core.model.ZonePolicy.FIXED_ZONE -> ZoneId.of(rule.zoneId)
            }
        } catch (_: DateTimeException) {
            issues += ScheduleValidationIssue.INVALID_ZONE_ID
            null
        }
        if (selectedZone != null && !jitterValidator.validate(rule, activeDeviceZone).valid) {
            issues += ScheduleValidationIssue.JITTER_CAN_REORDER_OCCURRENCES
        }
        if (configuration.missedPolicy == MissedPolicy.ASK_ME && !actionNotificationsAvailable) {
            issues += ScheduleValidationIssue.ASK_ME_REQUIRES_ACTION_NOTIFICATIONS
        }
        if (configuration.transport == TransportKind.AUTOMATIC_SMS && content.hasMedia) {
            issues += ScheduleValidationIssue.AUTOMATIC_SMS_REQUIRES_TEXT_ONLY
        }
        if (configuration.transport == TransportKind.ASSISTED_MEDIA && !content.hasMedia) {
            issues += ScheduleValidationIssue.ASSISTED_MEDIA_REQUIRES_ATTACHMENT
        }
        return ScheduleValidationResult(issues)
    }
}
