package com.patjackson.latertext.core.designsystem

import android.graphics.drawable.AnimatedImageDrawable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageMediaPreviewTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun animatedGifDecodesAndIsIdentifiedInThePreview() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val gif = File(context.cacheDir, "latertext-preview-test.gif")
        gif.writeBytes(TWO_FRAME_GIF)

        try {
            assertTrue(decodeMediaPreview(gif.absolutePath) is AnimatedImageDrawable)

            composeRule.setContent {
                MaterialTheme {
                    MessageMediaPreview(
                        absolutePath = gif.absolutePath,
                        mimeType = "image/gif",
                        isAnimated = true,
                        widthPixels = 1,
                        heightPixels = 1,
                    )
                }
            }

            composeRule.onNodeWithContentDescription("Animated GIF preview").assertExists()
            composeRule.onNodeWithText("GIF · animated").assertExists()
        } finally {
            gif.delete()
        }
    }

    private companion object {
        val TWO_FRAME_GIF = intArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61,
            0x01, 0x00, 0x01, 0x00, 0x80, 0x00, 0x00,
            0x00, 0x00, 0x00, 0xff, 0xff, 0xff,
            0x21, 0xff, 0x0b, 0x4e, 0x45, 0x54, 0x53, 0x43, 0x41, 0x50, 0x45, 0x32, 0x2e, 0x30,
            0x03, 0x01, 0x00, 0x00, 0x00,
            0x21, 0xf9, 0x04, 0x00, 0x0a, 0x00, 0x00, 0x00,
            0x2c, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0x02, 0x02, 0x44, 0x01, 0x00,
            0x21, 0xf9, 0x04, 0x00, 0x0a, 0x00, 0x00, 0x00,
            0x2c, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0x02, 0x02, 0x4c, 0x01, 0x00,
            0x3b,
        ).map(Int::toByte).toByteArray()
    }
}
