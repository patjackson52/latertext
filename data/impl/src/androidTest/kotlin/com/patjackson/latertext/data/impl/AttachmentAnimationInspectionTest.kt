package com.patjackson.latertext.data.impl

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patjackson.latertext.data.api.AttachmentInput
import com.patjackson.latertext.data.api.AttachmentIntakeSource
import com.patjackson.latertext.data.api.AttachmentWriteRequest
import com.patjackson.latertext.data.impl.attachment.PrivateAttachmentStore
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttachmentAnimationInspectionTest {
    @Test
    fun distinguishesStaticAndAnimatedGifFiles() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PrivateAttachmentStore(context)
        val static = store.stage(request(gif(frameData = listOf(0x44 to 0x01))))
        val animated = store.stage(request(gif(frameData = listOf(0x44 to 0x01, 0x4c to 0x01))))

        try {
            assertFalse(static.isAnimated)
            assertTrue(animated.isAnimated)
        } finally {
            store.delete(static)
            store.delete(animated)
        }
    }

    private fun request(bytes: ByteArray): AttachmentWriteRequest = AttachmentWriteRequest(
        id = "gif-inspection-${UUID.randomUUID()}",
        input = AttachmentInput { ByteArrayInputStream(bytes) },
        declaredMimeType = "image/gif",
        intakeSource = AttachmentIntakeSource.KEYBOARD,
        createdAtEpochMillis = 1,
        stagingExpiresAtEpochMillis = Long.MAX_VALUE,
        maxBytes = 1_024,
    )

    private fun gif(frameData: List<Pair<Int, Int>>): ByteArray = buildList {
        addAll(
            listOf(
                0x47, 0x49, 0x46, 0x38, 0x39, 0x61,
                0x01, 0x00, 0x01, 0x00, 0x80, 0x00, 0x00,
                0x00, 0x00, 0x00, 0xff, 0xff, 0xff,
            ),
        )
        frameData.forEach { (firstByte, secondByte) ->
            addAll(
                listOf(
                    0x21, 0xf9, 0x04, 0x00, 0x0a, 0x00, 0x00, 0x00,
                    0x2c, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
                    0x02, 0x02, firstByte, secondByte, 0x00,
                ),
            )
        }
        add(0x3b)
    }.map(Int::toByte).toByteArray()
}
