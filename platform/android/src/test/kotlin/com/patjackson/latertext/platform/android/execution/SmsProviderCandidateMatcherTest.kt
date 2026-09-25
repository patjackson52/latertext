package com.patjackson.latertext.platform.android.execution

import android.provider.Telephony
import com.patjackson.latertext.data.api.DeliveryOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class SmsProviderCandidateMatcherTest {
    private val query = SmsProviderQuery(
        creatorPackage = "com.example.latertext.debug",
        recipientAddress = "+14155550100",
        body = "hello from LaterText",
        subscriptionId = 7,
        fromEpochMillis = 1_000,
        toEpochMillis = 2_000,
        knownProviderMessageId = null,
    )

    @Test
    fun `unique candidate requires body number subscription and time`() {
        val match = SmsProviderCandidateMatcher.select(
            query,
            listOf(message(id = 11, address = "(415) 555-0100")),
        )

        assertEquals(11, (match as SmsProviderMatch.Unique).message.id)
    }

    @Test
    fun `unique exact candidate from default messaging app is accepted`() {
        val match = SmsProviderCandidateMatcher.select(
            query,
            listOf(message(id = 11).copy(creatorPackage = "com.google.android.apps.messaging")),
        )

        assertEquals(11, (match as SmsProviderMatch.Unique).message.id)
    }

    @Test
    fun `latertext creator wins when otherwise exact rows are duplicated`() {
        val match = SmsProviderCandidateMatcher.select(
            query,
            listOf(
                message(id = 11).copy(creatorPackage = "com.google.android.apps.messaging"),
                message(id = 12),
            ),
        )

        assertEquals(12, (match as SmsProviderMatch.Unique).message.id)
    }

    @Test
    fun `multiple otherwise identical candidates remain ambiguous`() {
        val match = SmsProviderCandidateMatcher.select(
            query,
            listOf(message(id = 11), message(id = 12)),
        )

        assertEquals(2, (match as SmsProviderMatch.Ambiguous).count)
    }

    @Test
    fun `known provider id makes future reads exact`() {
        val exact = query.copy(knownProviderMessageId = 12)

        val match = SmsProviderCandidateMatcher.select(
            exact,
            listOf(message(id = 11), message(id = 12)),
        )

        assertEquals(12, (match as SmsProviderMatch.Unique).message.id)
    }

    @Test
    fun `missing rows never imply failure`() {
        val mismatch = message(id = 11, body = "a different message")

        assertInstanceOf(
            SmsProviderMatch.None::class.java,
            SmsProviderCandidateMatcher.select(query, listOf(mismatch)),
        )
    }

    @Test
    fun `outbox and queued rows can be captured before sent transition`() {
        val outbox = message(id = 11).copy(type = Telephony.Sms.MESSAGE_TYPE_OUTBOX)
        val queued = message(id = 12).copy(type = Telephony.Sms.MESSAGE_TYPE_QUEUED)

        assertEquals(
            11,
            (SmsProviderCandidateMatcher.select(query, listOf(outbox)) as SmsProviderMatch.Unique)
                .message.id,
        )
        assertEquals(
            12,
            (SmsProviderCandidateMatcher.select(query, listOf(queued)) as SmsProviderMatch.Unique)
                .message.id,
        )
    }

    @Test
    fun `incoming row is never treated as send evidence`() {
        val incoming = message(id = 11).copy(type = Telephony.Sms.MESSAGE_TYPE_INBOX)

        assertInstanceOf(
            SmsProviderMatch.None::class.java,
            SmsProviderCandidateMatcher.select(query, listOf(incoming)),
        )
    }

    @Test
    fun `date sent can match when provider date is outside window`() {
        val candidate = message(id = 11).copy(
            dateEpochMillis = 10,
            dateSentEpochMillis = 1_500,
        )

        assertEquals(
            11,
            (SmsProviderCandidateMatcher.select(query, listOf(candidate)) as SmsProviderMatch.Unique)
                .message.id,
        )
    }

    @Test
    fun `provider tp status maps send and delivery independently`() {
        assertEquals(
            DeliveryOutcome.PENDING,
            SmsProviderReconciler.providerDeliveryOutcome(Telephony.Sms.STATUS_NONE),
        )
        assertEquals(
            DeliveryOutcome.DELIVERED,
            SmsProviderReconciler.providerDeliveryOutcome(Telephony.Sms.STATUS_COMPLETE),
        )
        assertEquals(
            DeliveryOutcome.PENDING,
            SmsProviderReconciler.providerDeliveryOutcome(Telephony.Sms.STATUS_PENDING),
        )
        assertEquals(
            DeliveryOutcome.FAILED,
            SmsProviderReconciler.providerDeliveryOutcome(Telephony.Sms.STATUS_FAILED),
        )
    }

    private fun message(
        id: Long,
        address: String = "+14155550100",
        body: String = query.body,
    ) = SmsProviderMessage(
        id = id,
        address = address,
        body = body,
        dateEpochMillis = 1_500,
        dateSentEpochMillis = 1_500,
        type = Telephony.Sms.MESSAGE_TYPE_SENT,
        status = Telephony.Sms.STATUS_NONE,
        errorCode = 0,
        subscriptionId = 7,
        creatorPackage = query.creatorPackage,
    )
}
