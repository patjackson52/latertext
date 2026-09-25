package com.patjackson.latertext.core.designsystem

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders app-private message media without cropping it. Android's native decoder preserves GIF
 * and animated WebP frames, while static images and memes use the same bounded preview surface.
 */
@Composable
fun MessageMediaPreview(
    absolutePath: String,
    mimeType: String?,
    isAnimated: Boolean,
    widthPixels: Int?,
    heightPixels: Int?,
    modifier: Modifier = Modifier,
    maximumHeight: Dp = 420.dp,
) {
    val state by produceState<MediaDecodeState>(
        initialValue = MediaDecodeState.Loading,
        key1 = absolutePath,
    ) {
        value = withContext(Dispatchers.IO) {
            decodeMediaPreview(absolutePath)
                ?.let(MediaDecodeState::Ready)
                ?: MediaDecodeState.Failed
        }
    }
    val description = when {
        isAnimated -> "Animated GIF preview"
        mimeType == "image/gif" -> "GIF preview"
        else -> "Image preview"
    }
    val aspectRatio = if (
        widthPixels != null && heightPixels != null && widthPixels > 0 && heightPixels > 0
    ) {
        widthPixels.toFloat() / heightPixels.toFloat()
    } else {
        DEFAULT_ASPECT_RATIO
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val previewHeight = (maxWidth / aspectRatio)
            .coerceIn(MINIMUM_PREVIEW_HEIGHT, maximumHeight)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(previewHeight)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            when (val decoded = state) {
                MediaDecodeState.Loading -> Text(
                    "Loading preview…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                MediaDecodeState.Failed -> Text(
                    "Preview unavailable",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is MediaDecodeState.Ready -> AndroidView(
                    factory = { context -> PreviewImageView(context) },
                    update = { imageView ->
                        if (imageView.drawable !== decoded.drawable) {
                            imageView.setImageDrawable(decoded.drawable)
                        }
                        imageView.contentDescription = description
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (isAnimated) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.72f),
                    contentColor = Color.White,
                ) {
                    Text(
                        "GIF · animated",
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

private sealed interface MediaDecodeState {
    data object Loading : MediaDecodeState
    data object Failed : MediaDecodeState
    data class Ready(val drawable: Drawable) : MediaDecodeState
}

internal fun decodeMediaPreview(absolutePath: String): Drawable? = runCatching {
    val file = File(absolutePath)
    if (!file.isFile) return null
    ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
        val largestEdge = maxOf(info.size.width, info.size.height)
        val sampleSize = ceil(largestEdge.toDouble() / MAXIMUM_DECODED_EDGE_PIXELS)
            .toInt()
            .coerceAtLeast(1)
        decoder.setTargetSampleSize(sampleSize)
    }.also { drawable ->
        (drawable as? AnimatedImageDrawable)?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
    }
}.getOrNull()

private class PreviewImageView(context: Context) : ImageView(context) {
    init {
        adjustViewBounds = true
        scaleType = ScaleType.FIT_CENTER
    }

    override fun setImageDrawable(drawable: Drawable?) {
        (this.drawable as? Animatable)?.stop()
        super.setImageDrawable(drawable)
        updatePlayback()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updatePlayback()
    }

    override fun onDetachedFromWindow() {
        (drawable as? Animatable)?.stop()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updatePlayback()
    }

    private fun updatePlayback() {
        (drawable as? Animatable)?.let { animation ->
            if (isAttachedToWindow && windowVisibility == View.VISIBLE) animation.start()
            else animation.stop()
        }
    }
}

private val MINIMUM_PREVIEW_HEIGHT = 140.dp
private const val DEFAULT_ASPECT_RATIO = 4f / 3f
private const val MAXIMUM_DECODED_EDGE_PIXELS = 1_440.0
