package com.patjackson.latertext.platform.android

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.patjackson.latertext.platform.api.ContentImportResult
import com.patjackson.latertext.platform.api.ContentRejection
import com.patjackson.latertext.platform.api.IncomingContent
import com.patjackson.latertext.platform.api.IncomingContentImporter
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidIncomingContentImporter(
    private val context: Context,
    private val maximumBytes: Long = 25L * 1024L * 1024L,
    private val maximumDimension: Int = 12_000,
) : IncomingContentImporter {
    override suspend fun import(content: IncomingContent): ContentImportResult = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(content.uri) }.getOrNull()
            ?: return@withContext ContentImportResult.Rejected(ContentRejection.UNREADABLE)
        val stagingDirectory = File(context.filesDir, "attachments/staging").apply { mkdirs() }
        if (!stagingDirectory.isDirectory) {
            return@withContext ContentImportResult.Rejected(ContentRejection.STORAGE_UNAVAILABLE)
        }

        val temporary = File(stagingDirectory, ".${UUID.randomUUID()}.copying")
        val copiedBytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maximumBytes) throw ContentTooLarge()
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                    total
                }
            } ?: throw ContentUnreadable()
        }.getOrElse { error ->
            temporary.delete()
            return@withContext ContentImportResult.Rejected(
                when (error) {
                    is ContentTooLarge -> ContentRejection.TOO_LARGE
                    else -> ContentRejection.UNREADABLE
                },
            )
        }

        if (copiedBytes == 0L) {
            temporary.delete()
            return@withContext ContentImportResult.Rejected(ContentRejection.EMPTY)
        }

        val detected = detectType(temporary)
        if (detected == null || detected !in ACCEPTED_TYPES) {
            temporary.delete()
            return@withContext ContentImportResult.Rejected(ContentRejection.INVALID_SIGNATURE)
        }
        val declared = content.declaredMimeType?.substringBefore(';')?.trim()?.lowercase()
        if (declared != null && declared !in ACCEPTED_TYPES) {
            temporary.delete()
            return@withContext ContentImportResult.Rejected(ContentRejection.UNSUPPORTED_TYPE)
        }

        val bounds = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
        BitmapFactory.decodeFile(temporary.absolutePath, bounds)
        val width = bounds.outWidth.takeIf { it > 0 }
        val height = bounds.outHeight.takeIf { it > 0 }
        if (width == null || height == null) {
            temporary.delete()
            return@withContext ContentImportResult.Rejected(ContentRejection.INVALID_SIGNATURE)
        }
        if (width > maximumDimension || height > maximumDimension) {
            temporary.delete()
            return@withContext ContentImportResult.Rejected(ContentRejection.DIMENSIONS_TOO_LARGE)
        }

        val extension = when (detected) {
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/png" -> "png"
            else -> "jpg"
        }
        val destination = File(stagingDirectory, "${UUID.randomUUID()}.$extension")
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            return@withContext ContentImportResult.Rejected(ContentRejection.STORAGE_UNAVAILABLE)
        }

        ContentImportResult.Imported(
            stagedPath = destination.absolutePath,
            mimeType = detected,
            byteCount = copiedBytes,
            width = width,
            height = height,
            animated = detected == "image/gif" || isAnimatedWebp(destination),
        )
    }

    private fun detectType(file: File): String? {
        val header = ByteArray(16)
        val count = file.inputStream().use { it.read(header) }
        if (count < 3) return null
        return when {
            header[0] == 0x47.toByte() && header[1] == 0x49.toByte() && header[2] == 0x46.toByte() ->
                "image/gif"
            count >= 12 && header[0] == 0x52.toByte() && header[1] == 0x49.toByte() &&
                header[2] == 0x46.toByte() && header[3] == 0x46.toByte() &&
                header[8] == 0x57.toByte() && header[9] == 0x45.toByte() &&
                header[10] == 0x42.toByte() && header[11] == 0x50.toByte() -> "image/webp"
            count >= 8 && header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
                header[2] == 0x4E.toByte() && header[3] == 0x47.toByte() -> "image/png"
            header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte() ->
                "image/jpeg"
            else -> null
        }
    }

    private fun isAnimatedWebp(file: File): Boolean {
        val bytes = file.inputStream().buffered().use { input ->
            val limit = minOf(file.length(), 128 * 1024L).toInt()
            val buffer = ByteArray(limit)
            var offset = 0
            while (offset < limit) {
                val count = input.read(buffer, offset, limit - offset)
                if (count < 0) break
                offset += count
            }
            if (offset == buffer.size) buffer else buffer.copyOf(offset)
        }
        return bytes.windowedAsciiContains("ANIM") || bytes.windowedAsciiContains("ANMF")
    }

    private fun ByteArray.windowedAsciiContains(value: String): Boolean {
        val target = value.encodeToByteArray()
        if (size < target.size) return false
        return (0..size - target.size).any { start ->
            target.indices.all { offset -> this[start + offset] == target[offset] }
        }
    }

    private class ContentTooLarge : Exception()
    private class ContentUnreadable : Exception()

    companion object {
        private val ACCEPTED_TYPES = setOf("image/gif", "image/webp", "image/png", "image/jpeg")
    }
}
