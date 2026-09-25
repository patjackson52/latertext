package com.patjackson.latertext.transport.automatic

import android.content.Context
import com.google.android.mms.ContentType
import com.google.android.mms.pdu_alt.CharacterSets
import com.google.android.mms.pdu_alt.EncodedStringValue
import com.google.android.mms.pdu_alt.PduBody
import com.google.android.mms.pdu_alt.PduComposer
import com.google.android.mms.pdu_alt.PduHeaders
import com.google.android.mms.pdu_alt.PduPart
import com.google.android.mms.pdu_alt.SendReq
import java.nio.charset.StandardCharsets

/** Encodes an SDK-compatible MMS Send.req; Android's MMS service performs network transport. */
internal class MmsPduComposer(
    private val context: Context,
) {
    fun compose(
        recipientAddress: String,
        text: String,
        attachmentBytes: ByteArray,
        attachmentMimeType: String,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1_000,
    ): ByteArray {
        require(recipientAddress.isNotBlank())
        require(attachmentBytes.isNotEmpty())
        val imageName = "image.${attachmentMimeType.extension()}"
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        val body = PduBody()

        val smilBytes = smilDocument(imageName, textBytes.isNotEmpty())
            .toByteArray(StandardCharsets.UTF_8)
        body.addPart(0, part("smil.xml", ContentType.APP_SMIL, smilBytes, isText = true))
        body.addPart(part(imageName, attachmentMimeType, attachmentBytes, isText = false))
        if (textBytes.isNotEmpty()) {
            body.addPart(part("text.txt", ContentType.TEXT_PLAIN, textBytes, isText = true))
        }

        val request = SendReq().apply {
            addTo(EncodedStringValue(recipientAddress))
            setDate(nowEpochSeconds)
            setBody(body)
            setMessageSize((attachmentBytes.size + textBytes.size + smilBytes.size).toLong())
            setPriority(PduHeaders.PRIORITY_NORMAL)
            setDeliveryReport(PduHeaders.VALUE_NO)
            setReadReport(PduHeaders.VALUE_NO)
            setExpiry(SEVEN_DAYS_SECONDS)
            setMessageClass(PduHeaders.MESSAGE_CLASS_PERSONAL_STR.toByteArray(StandardCharsets.US_ASCII))
        }
        return requireNotNull(PduComposer(context, request).make()) {
            "MMS PDU encoder rejected the message"
        }
    }

    private fun part(name: String, mimeType: String, bytes: ByteArray, isText: Boolean): PduPart =
        PduPart().apply {
            setName(name.toByteArray(StandardCharsets.US_ASCII))
            setFilename(name.toByteArray(StandardCharsets.US_ASCII))
            setContentLocation(name.toByteArray(StandardCharsets.US_ASCII))
            setContentId(name.substringBefore('.').toByteArray(StandardCharsets.US_ASCII))
            setContentType(mimeType.toByteArray(StandardCharsets.US_ASCII))
            if (isText) setCharset(CharacterSets.UTF_8)
            setData(bytes)
        }

    private fun smilDocument(imageName: String, hasText: Boolean): String = buildString {
        append("<smil><head><layout><root-layout width=\"100%\" height=\"100%\"/>")
        append("<region id=\"Image\" left=\"0\" top=\"0\" width=\"100%\" height=\"80%\"/>")
        if (hasText) append("<region id=\"Text\" left=\"0\" top=\"80%\" width=\"100%\" height=\"20%\"/>")
        append("</layout></head><body><par dur=\"5000ms\">")
        append("<img src=\"").append(imageName).append("\" region=\"Image\"/>")
        if (hasText) append("<text src=\"text.txt\" region=\"Text\"/>")
        append("</par></body></smil>")
    }

    private fun String.extension(): String = when (lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        else -> error("Unsupported MMS attachment type: $this")
    }

    companion object {
        private const val SEVEN_DAYS_SECONDS = 7L * 24 * 60 * 60
    }
}
