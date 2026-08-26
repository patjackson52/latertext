package com.patjackson.latertext.data.impl.attachment

import android.content.Context
import com.patjackson.latertext.data.api.AttachmentAssetRecord
import com.patjackson.latertext.data.api.AttachmentState
import com.patjackson.latertext.data.api.AttachmentStorageClass
import com.patjackson.latertext.data.api.AttachmentStore
import com.patjackson.latertext.data.api.AttachmentTooLargeException
import com.patjackson.latertext.data.api.AttachmentWriteRequest
import com.patjackson.latertext.data.api.InvalidAttachmentException
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PrivateAttachmentStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AttachmentStore {
    private val filesRoot = File(context.filesDir, "latertext_attachments")
    private val stagingRoot = File(filesRoot, "staging")
    private val durableRoot = File(filesRoot, "durable")
    private val cacheRoot = File(context.cacheDir, "latertext_attachments")

    override suspend fun stage(request: AttachmentWriteRequest): AttachmentAssetRecord =
        withContext(ioDispatcher) {
            requireSafeId(request.id)
            require(request.maxBytes > 0)
            require(request.maxWidthPixels > 0 && request.maxHeightPixels > 0)
            stagingRoot.mkdirsOrThrow()
            val partial = File(stagingRoot, ".${request.id}.partial")
            if (partial.exists()) check(partial.delete()) { "Unable to replace abandoned attachment copy" }

            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var byteCount = 0L
                request.input.openStream().use { input ->
                    FileOutputStream(partial).use { fileOutput ->
                        val output = fileOutput.buffered()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            byteCount += count
                            if (byteCount > request.maxBytes) {
                                throw AttachmentTooLargeException(request.maxBytes)
                            }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        output.flush()
                        fileOutput.fd.sync()
                    }
                }
                if (byteCount == 0L) throw InvalidAttachmentException("Attachment is empty")

                val inspection = MediaInspector.inspect(partial)
                val declared = request.declaredMimeType?.lowercase()?.substringBefore(';')?.trim()
                if (declared != null && declared !in inspection.acceptedDeclaredMimeTypes) {
                    throw InvalidAttachmentException(
                        "Declared type $declared does not match ${inspection.mimeType}",
                    )
                }
                inspection.widthPixels?.let {
                    if (it > request.maxWidthPixels) {
                        throw InvalidAttachmentException("Image width exceeds ${request.maxWidthPixels} pixels")
                    }
                }
                inspection.heightPixels?.let {
                    if (it > request.maxHeightPixels) {
                        throw InvalidAttachmentException("Image height exceeds ${request.maxHeightPixels} pixels")
                    }
                }

                val relativePath = "${request.id}.${inspection.extension}"
                val destination = resolveIn(stagingRoot, relativePath)
                if (destination.exists()) throw InvalidAttachmentException("Attachment ID already exists")
                moveAtomically(partial, destination)
                AttachmentAssetRecord(
                    id = request.id,
                    storageClass = AttachmentStorageClass.STAGING,
                    relativePath = relativePath,
                    mimeType = inspection.mimeType,
                    byteCount = byteCount,
                    widthPixels = inspection.widthPixels,
                    heightPixels = inspection.heightPixels,
                    sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                    isAnimated = inspection.isAnimated,
                    intakeSource = request.intakeSource,
                    state = AttachmentState.READY,
                    failureReason = null,
                    createdAtEpochMillis = request.createdAtEpochMillis,
                    updatedAtEpochMillis = request.createdAtEpochMillis,
                    stagingExpiresAtEpochMillis = request.stagingExpiresAtEpochMillis,
                )
            } finally {
                if (partial.exists()) partial.delete()
            }
        }

    override suspend fun promoteToDurable(
        asset: AttachmentAssetRecord,
        updatedAtEpochMillis: Long,
    ): AttachmentAssetRecord = withContext(ioDispatcher) {
        if (asset.storageClass == AttachmentStorageClass.DURABLE) return@withContext asset
        require(asset.storageClass == AttachmentStorageClass.STAGING) {
            "Only staged attachments can be promoted"
        }
        durableRoot.mkdirsOrThrow()
        val source = resolve(asset)
        val destination = resolveIn(durableRoot, asset.relativePath)
        when {
            destination.exists() -> {
                if (source.exists()) source.delete()
            }
            source.exists() -> moveAtomically(source, destination)
            else -> throw InvalidAttachmentException("Staged attachment bytes are missing")
        }
        asset.copy(
            storageClass = AttachmentStorageClass.DURABLE,
            updatedAtEpochMillis = updatedAtEpochMillis,
            stagingExpiresAtEpochMillis = null,
        )
    }

    override suspend fun open(asset: AttachmentAssetRecord): InputStream = withContext(ioDispatcher) {
        val file = resolve(asset)
        if (!file.isFile) throw InvalidAttachmentException("Attachment bytes are missing")
        BufferedInputStream(FileInputStream(file))
    }

    override suspend fun exists(asset: AttachmentAssetRecord): Boolean = withContext(ioDispatcher) {
        resolve(asset).isFile
    }

    override suspend fun delete(asset: AttachmentAssetRecord): Boolean = withContext(ioDispatcher) {
        val file = resolve(asset)
        !file.exists() || file.delete()
    }

    override suspend fun deleteExpiredStaging(nowEpochMillis: Long): Int = withContext(ioDispatcher) {
        if (!stagingRoot.isDirectory) return@withContext 0
        val abandonedBefore = nowEpochMillis - ABANDONED_PARTIAL_AGE_MILLIS
        stagingRoot.listFiles().orEmpty().count { file ->
            file.name.endsWith(".partial") && file.lastModified() <= abandonedBefore && file.delete()
        }
    }

    private fun resolve(asset: AttachmentAssetRecord): File = when (asset.storageClass) {
        AttachmentStorageClass.STAGING -> resolveIn(stagingRoot, asset.relativePath)
        AttachmentStorageClass.DURABLE -> resolveIn(durableRoot, asset.relativePath)
        AttachmentStorageClass.CACHE -> resolveIn(cacheRoot, asset.relativePath)
    }

    private fun resolveIn(root: File, relativePath: String): File {
        require(!File(relativePath).isAbsolute) { "Attachment path must be relative" }
        val canonicalRoot = root.canonicalFile
        val resolved = File(canonicalRoot, relativePath).canonicalFile
        require(resolved.parentFile == canonicalRoot) { "Attachment path escapes its private directory" }
        return resolved
    }

    private fun requireSafeId(id: String) {
        require(id.matches(SAFE_ID)) { "Attachment ID contains unsupported characters" }
    }

    private fun File.mkdirsOrThrow() {
        check(isDirectory || mkdirs()) { "Unable to create private attachment directory" }
    }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    companion object {
        private val SAFE_ID = Regex("[A-Za-z0-9._-]{1,160}")
        private const val ABANDONED_PARTIAL_AGE_MILLIS = 24L * 60 * 60 * 1_000
    }
}

private data class MediaInspection(
    val mimeType: String,
    val extension: String,
    val widthPixels: Int?,
    val heightPixels: Int?,
    val isAnimated: Boolean,
    val acceptedDeclaredMimeTypes: Set<String> = setOf(mimeType),
)

private object MediaInspector {
    fun inspect(file: File): MediaInspection {
        val header = ByteArray(minOf(file.length(), 64L).toInt())
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read < 0) break
                offset += read
            }
        }
        return when {
            header.isGif() -> MediaInspection(
                "image/gif", "gif", header.u16le(6), header.u16le(8), true,
            )
            header.isPng() -> MediaInspection(
                "image/png", "png", header.i32be(16), header.i32be(20), false,
            )
            header.isJpeg() -> inspectJpeg(file)
            header.isWebp() -> inspectWebp(file)
            else -> throw InvalidAttachmentException("Only GIF, WebP, PNG, and JPEG images are supported")
        }.also {
            if (it.widthPixels != null && it.widthPixels <= 0) {
                throw InvalidAttachmentException("Image width is invalid")
            }
            if (it.heightPixels != null && it.heightPixels <= 0) {
                throw InvalidAttachmentException("Image height is invalid")
            }
        }
    }

    private fun inspectJpeg(file: File): MediaInspection {
        FileInputStream(file).buffered().use { input ->
            if (input.read() != 0xff || input.read() != 0xd8) {
                throw InvalidAttachmentException("JPEG signature is invalid")
            }
            while (true) {
                var markerStart = input.read()
                while (markerStart != -1 && markerStart != 0xff) markerStart = input.read()
                if (markerStart == -1) break
                var marker = input.read()
                while (marker == 0xff) marker = input.read()
                if (marker == -1 || marker == 0xd9 || marker == 0xda) break
                val length = input.readU16be()
                if (length < 2) throw InvalidAttachmentException("JPEG segment is malformed")
                if (marker in JPEG_SOF_MARKERS) {
                    if (length < 7) throw InvalidAttachmentException("JPEG dimensions are malformed")
                    input.read()
                    val height = input.readU16be()
                    val width = input.readU16be()
                    return MediaInspection(
                        "image/jpeg", "jpg", width, height, false,
                        setOf("image/jpeg", "image/jpg"),
                    )
                }
                input.skipFully((length - 2).toLong())
            }
        }
        throw InvalidAttachmentException("JPEG dimensions could not be read")
    }

    private fun inspectWebp(file: File): MediaInspection {
        val bytes = file.readBytesUpTo(MAX_WEBP_SCAN_BYTES)
        var offset = 12
        var width: Int? = null
        var height: Int? = null
        var animated = false
        while (offset + 8 <= bytes.size) {
            val chunk = bytes.ascii(offset, 4)
            val size = bytes.i32le(offset + 4)
            if (size < 0) throw InvalidAttachmentException("WebP chunk is malformed")
            val dataOffset = offset + 8
            if (dataOffset + minOf(size, 10) > bytes.size) break
            when (chunk) {
                "VP8X" -> if (size >= 10) {
                    animated = animated || (bytes[dataOffset].toInt() and 0x02) != 0
                    width = 1 + bytes.u24le(dataOffset + 4)
                    height = 1 + bytes.u24le(dataOffset + 7)
                }
                "ANIM", "ANMF" -> animated = true
                "VP8 " -> if (size >= 10 &&
                    bytes[dataOffset + 3] == 0x9d.toByte() &&
                    bytes[dataOffset + 4] == 0x01.toByte() &&
                    bytes[dataOffset + 5] == 0x2a.toByte()
                ) {
                    width = bytes.u16le(dataOffset + 6) and 0x3fff
                    height = bytes.u16le(dataOffset + 8) and 0x3fff
                }
                "VP8L" -> if (size >= 5 && bytes[dataOffset] == 0x2f.toByte()) {
                    val packed = bytes.i32le(dataOffset + 1)
                    width = 1 + (packed and 0x3fff)
                    height = 1 + ((packed ushr 14) and 0x3fff)
                }
            }
            val paddedSize = size.toLong() + (size and 1)
            if (paddedSize > Int.MAX_VALUE || offset + 8L + paddedSize > bytes.size.toLong()) break
            offset += 8 + paddedSize.toInt()
        }
        if (width == null || height == null) {
            throw InvalidAttachmentException("WebP dimensions could not be read")
        }
        return MediaInspection("image/webp", "webp", width, height, animated)
    }

    private fun ByteArray.isGif() = size >= 10 &&
        (ascii(0, 6) == "GIF87a" || ascii(0, 6) == "GIF89a")

    private fun ByteArray.isPng() = size >= 24 &&
        this[0] == 0x89.toByte() && ascii(1, 3) == "PNG" &&
        this[4] == 0x0d.toByte() && this[5] == 0x0a.toByte() &&
        this[6] == 0x1a.toByte() && this[7] == 0x0a.toByte() &&
        ascii(12, 4) == "IHDR"

    private fun ByteArray.isJpeg() = size >= 2 && this[0] == 0xff.toByte() && this[1] == 0xd8.toByte()

    private fun ByteArray.isWebp() = size >= 16 && ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP"

    private fun ByteArray.ascii(offset: Int, length: Int) =
        String(this, offset, length, Charsets.US_ASCII)

    private fun ByteArray.u16le(offset: Int) =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.u24le(offset: Int) =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16)

    private fun ByteArray.i32le(offset: Int) =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)

    private fun ByteArray.i32be(offset: Int) =
        ((this[offset].toInt() and 0xff) shl 24) or
            ((this[offset + 1].toInt() and 0xff) shl 16) or
            ((this[offset + 2].toInt() and 0xff) shl 8) or
            (this[offset + 3].toInt() and 0xff)

    private fun InputStream.readU16be(): Int {
        val high = read()
        val low = read()
        if (high < 0 || low < 0) throw InvalidAttachmentException("Unexpected end of image")
        return (high shl 8) or low
    }

    private fun InputStream.skipFully(count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) remaining -= skipped
            else if (read() < 0) throw InvalidAttachmentException("Unexpected end of image")
            else remaining--
        }
    }

    private fun File.readBytesUpTo(limit: Int): ByteArray = FileInputStream(this).use { input ->
        val output = java.io.ByteArrayOutputStream(minOf(length(), limit.toLong()).toInt())
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = limit
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            output.write(buffer, 0, count)
            remaining -= count
        }
        output.toByteArray()
    }

    private val JPEG_SOF_MARKERS = setOf(
        0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf,
    )
    private const val MAX_WEBP_SCAN_BYTES = 1024 * 1024
}
