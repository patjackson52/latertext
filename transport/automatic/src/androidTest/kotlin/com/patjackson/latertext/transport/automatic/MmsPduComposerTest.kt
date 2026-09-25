package com.patjackson.latertext.transport.automatic

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.mms.pdu_alt.PduParser
import com.google.android.mms.pdu_alt.SendReq
import com.patjackson.latertext.platform.api.MmsCapability
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MmsPduComposerTest {
    @Test
    fun encodesParseableSendRequestWithTextAndImage() {
        val image = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)
        val encoded = MmsPduComposer(ApplicationProvider.getApplicationContext()).compose(
            recipientAddress = "+14155550123",
            text = "Hello from LaterText",
            attachmentBytes = image,
            attachmentMimeType = "image/gif",
            nowEpochSeconds = 1_700_000_000,
        )

        val parsed = PduParser(encoded, true).parse() as SendReq
        assertEquals("+14155550123", parsed.to.single().string)
        assertEquals(3, parsed.body.partsNum)
        assertTrue(parsed.body.getPartByContentLocation("smil.xml") != null)
        assertTrue(parsed.body.getPartByContentLocation("text.txt") != null)
        assertArrayEquals(image, parsed.body.getPartByContentLocation("image.gif").data)
    }

    @Test
    fun resizesLargeStaticImageToCarrierLimits() {
        val source = Bitmap.createBitmap(1_600, 1_200, Bitmap.Config.ARGB_8888).run {
            eraseColor(android.graphics.Color.MAGENTA)
            ByteArrayOutputStream().use { output ->
                compress(Bitmap.CompressFormat.PNG, 100, output)
                recycle()
                output.toByteArray()
            }
        }
        val capability = MmsCapability(
            supported = true,
            maxMessageBytes = 300 * 1_024,
            maxImageWidth = 640,
            maxImageHeight = 480,
        )

        val prepared = MmsAttachmentPreparer.prepare(source, "image/png", capability, textByteCount = 20)
        val bounds = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(prepared.bytes, 0, prepared.bytes.size, bounds)

        assertEquals("image/jpeg", prepared.mimeType)
        assertTrue(prepared.bytes.size + 16 * 1_024 < capability.maxMessageBytes)
        assertTrue(bounds.outWidth <= 640)
        assertTrue(bounds.outHeight <= 480)
    }
}
