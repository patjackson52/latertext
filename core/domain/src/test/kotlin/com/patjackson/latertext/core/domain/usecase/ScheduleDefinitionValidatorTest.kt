package com.patjackson.latertext.core.domain.usecase

import com.patjackson.latertext.core.model.AttachmentId
import com.patjackson.latertext.core.model.ContentRevision
import com.patjackson.latertext.core.model.ContentRevisionId
import com.patjackson.latertext.core.model.MissedPolicy
import com.patjackson.latertext.core.model.RecipientId
import com.patjackson.latertext.core.model.RecurrenceFrequency
import com.patjackson.latertext.core.model.RecurrenceRule
import com.patjackson.latertext.core.model.RuleRevisionId
import com.patjackson.latertext.core.model.ScheduleConfiguration
import com.patjackson.latertext.core.model.ScheduleId
import com.patjackson.latertext.core.model.TransportKind
import com.patjackson.latertext.core.model.ZonePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ScheduleDefinitionValidatorTest {
    private val validator = ScheduleDefinitionValidator()
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `valid automatic text schedule passes`() {
        val result = validator.validate(
            configuration = configuration(),
            content = content(),
            rule = rule(),
            activeDeviceZone = ZoneId.of("UTC"),
            actionNotificationsAvailable = true,
        )
        assertTrue(result.valid)
    }

    @Test
    fun `automatic media ask-me and unsafe jitter report independent issues`() {
        val result = validator.validate(
            configuration = configuration(
                transport = TransportKind.AUTOMATIC_SMS,
                missedPolicy = MissedPolicy.ASK_ME,
            ),
            content = content(attachment = AttachmentId("attachment")),
            rule = rule(jitter = 720),
            activeDeviceZone = ZoneId.of("UTC"),
            actionNotificationsAvailable = false,
        )
        assertFalse(result.valid)
        assertEquals(
            setOf(
                ScheduleValidationIssue.JITTER_CAN_REORDER_OCCURRENCES,
                ScheduleValidationIssue.ASK_ME_REQUIRES_ACTION_NOTIFICATIONS,
                ScheduleValidationIssue.AUTOMATIC_SMS_REQUIRES_TEXT_ONLY,
            ),
            result.issues,
        )
    }

    @Test
    fun `assisted media requires attachment and invalid fixed zone is reported`() {
        val result = validator.validate(
            configuration = configuration(transport = TransportKind.ASSISTED_MEDIA),
            content = content(),
            rule = rule(zonePolicy = ZonePolicy.FIXED_ZONE, zoneId = "Not/AZone"),
            activeDeviceZone = ZoneId.of("UTC"),
            actionNotificationsAvailable = true,
        )
        assertEquals(
            setOf(
                ScheduleValidationIssue.INVALID_ZONE_ID,
                ScheduleValidationIssue.ASSISTED_MEDIA_REQUIRES_ATTACHMENT,
            ),
            result.issues,
        )
    }

    private fun configuration(
        transport: TransportKind = TransportKind.AUTOMATIC_SMS,
        missedPolicy: MissedPolicy = MissedPolicy.SEND_AS_SOON_AS_POSSIBLE,
    ) = ScheduleConfiguration(
        scheduleId = ScheduleId("schedule"),
        recipientId = RecipientId("recipient"),
        contentRevisionId = ContentRevisionId("content"),
        ruleRevisionId = RuleRevisionId("rule"),
        transport = transport,
        missedPolicy = missedPolicy,
    )

    private fun content(attachment: AttachmentId? = null) = ContentRevision(
        id = ContentRevisionId("content"),
        text = "hello",
        attachmentId = attachment,
        createdAt = now,
    )

    private fun rule(
        jitter: Int = 0,
        zonePolicy: ZonePolicy = ZonePolicy.FOLLOW_DEVICE_ZONE,
        zoneId: String = "UTC",
    ) = RecurrenceRule(
        frequency = RecurrenceFrequency.DAILY,
        startDate = LocalDate.of(2026, 1, 1),
        localTime = LocalTime.of(17, 0),
        zonePolicy = zonePolicy,
        zoneId = zoneId,
        jitterRangeMinutes = jitter,
    )
}
