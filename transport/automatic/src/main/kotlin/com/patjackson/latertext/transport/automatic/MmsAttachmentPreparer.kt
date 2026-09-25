package com.patjackson.latertext.transport.automatic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import com.patjackson.latertext.platform.api.MmsCapability
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class PreparedMmsAttachment(
    val bytes: ByteArray,
    val mimeType: String,
    val widthPixels: Int,
    val heightPixels: Int,
)

/** Resizes ordinary photos to live carrier limits. Animated GIFs are preserved or rejected. */
internal object MmsAttachmentPreparer {
    fun prepare(
        sourceBytes: ByteArray,
        sourceMimeType: String,
        capability: MmsCapability,
        textByteCount: Int,
        isAnimated: Boolean = sourceMimeType.equals("image/gif", ignoreCase = true),
    ): PreparedMmsAttachment {
        require(sourceBytes.isNotEmpty())
        val bounds = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "MMS image could not be decoded" }

        if (capability.accepts(sourceMimeType, sourceBytes.size.toLong(), bounds.outWidth, bounds.outHeight)) {
            return PreparedMmsAttachment(
                bytes = sourceBytes,
                mimeType = sourceMimeType.lowercase(),
                widthPixels = bounds.outWidth,
                heightPixels = bounds.outHeight,
            )
        }
        require(!isAnimated) {
            "Animated GIF exceeds carrier MMS limits and cannot be resized safely"
        }

        val orientation = exifRotation(sourceBytes, sourceMimeType)
        val orientedWidth = if (orientation == 90 || orientation == 270) bounds.outHeight else bounds.outWidth
        val orientedHeight = if (orientation == 90 || orientation == 270) bounds.outWidth else bounds.outHeight
        val (boundWidth, boundHeight) = orientedCarrierBounds(
            orientedWidth,
            orientedHeight,
            capability.maxImageWidth,
            capability.maxImageHeight,
        )
        val sampleSize = decodeSampleSize(bounds.outWidth, bounds.outHeight, boundWidth, boundHeight)
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = requireNotNull(BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, options)) {
            "MMS image could not be decoded"
        }
        try {
            if (orientation != 0) {
                val rotated = Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    bitmap.width,
                    bitmap.height,
                    Matrix().apply { postRotate(orientation.toFloat()) },
                    true,
                )
                if (rotated !== bitmap) bitmap.recycle()
                bitmap = rotated
            }
            bitmap = bitmap.scaledToFit(boundWidth, boundHeight)
            val payloadBudget = max(
                MIN_IMAGE_BUDGET_BYTES,
                capability.maxMessageBytes - PDU_AND_TEXT_RESERVE_BYTES - textByteCount,
            )
            repeat(MAX_SCALE_PASSES) {
                JPEG_QUALITIES.forEach { quality ->
                    val encoded = ByteArrayOutputStream().use { output ->
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                            "MMS image compression failed"
                        }
                        output.toByteArray()
                    }
                    if (encoded.size <= payloadBudget) {
                        return PreparedMmsAttachment(
                            bytes = encoded,
                            mimeType = "image/jpeg",
                            widthPixels = bitmap.width,
                            heightPixels = bitmap.height,
                        )
                    }
                }
                val nextWidth = max(MIN_IMAGE_EDGE_PIXELS, (bitmap.width * SCALE_STEP).roundToInt())
                val nextHeight = max(MIN_IMAGE_EDGE_PIXELS, (bitmap.height * SCALE_STEP).roundToInt())
                require(nextWidth < bitmap.width || nextHeight < bitmap.height) {
                    "Image cannot be reduced below the carrier MMS limit"
                }
                val smaller = bitmap.onWhiteBackground(nextWidth, nextHeight)
                bitmap.recycle()
                bitmap = smaller
            }
            error("Image cannot be reduced below the carrier MMS limit")
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun Bitmap.scaledToFit(maxWidth: Int, maxHeight: Int): Bitmap {
        val scale = min(1f, min(maxWidth.toFloat() / width, maxHeight.toFloat() / height))
        if (scale >= 1f) return onWhiteBackground(width, height)
        return onWhiteBackground(
            max(1, (width * scale).roundToInt()),
            max(1, (height * scale).roundToInt()),
        ).also { if (it !== this) recycle() }
    }

    private fun Bitmap.onWhiteBackground(targetWidth: Int, targetHeight: Int): Bitmap {
        val scaled = if (width == targetWidth && height == targetHeight) {
            this
        } else {
            Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
        }
        val flattened = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        Canvas(flattened).apply {
            drawColor(Color.WHITE)
            drawBitmap(scaled, 0f, 0f, null)
        }
        if (scaled !== this) scaled.recycle()
        return flattened
    }

    private fun orientedCarrierBounds(
        width: Int,
        height: Int,
        configuredWidth: Int,
        configuredHeight: Int,
    ): Pair<Int, Int> {
        val longEdge = max(configuredWidth, configuredHeight)
        val shortEdge = min(configuredWidth, configuredHeight)
        return if (width >= height) longEdge to shortEdge else shortEdge to longEdge
    }

    private fun decodeSampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        var sample = 1
        val sourceLongEdge = max(width, height)
        val sourceShortEdge = min(width, height)
        val targetLongEdge = max(targetWidth, targetHeight)
        val targetShortEdge = min(targetWidth, targetHeight)
        while (
            sourceLongEdge / (sample * 2) >= targetLongEdge &&
            sourceShortEdge / (sample * 2) >= targetShortEdge
        ) {
            sample *= 2
        }
        return sample
    }

    private fun exifRotation(bytes: ByteArray, mimeType: String): Int {
        if (mimeType.lowercase() !in setOf("image/jpeg", "image/jpg")) return 0
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }

    private val JPEG_QUALITIES = intArrayOf(88, 78, 68, 58, 48, 38)
    private const val MAX_SCALE_PASSES = 8
    private const val SCALE_STEP = 0.8f
    private const val MIN_IMAGE_EDGE_PIXELS = 160
    private const val MIN_IMAGE_BUDGET_BYTES = 32 * 1_024
    private const val PDU_AND_TEXT_RESERVE_BYTES = 24 * 1_024
}
