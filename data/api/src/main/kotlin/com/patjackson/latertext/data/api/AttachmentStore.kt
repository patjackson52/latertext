package com.patjackson.latertext.data.api

import java.io.InputStream

fun interface AttachmentInput {
    fun openStream(): InputStream
}

data class AttachmentWriteRequest(
    val id: String,
    val input: AttachmentInput,
    val declaredMimeType: String?,
    val intakeSource: AttachmentIntakeSource,
    val createdAtEpochMillis: Long,
    val stagingExpiresAtEpochMillis: Long,
    val maxBytes: Long,
    val maxWidthPixels: Int = 8_192,
    val maxHeightPixels: Int = 8_192,
)

/** Owns app-private attachment bytes; returned paths are always relative, never foreign URIs. */
interface AttachmentStore {
    suspend fun stage(request: AttachmentWriteRequest): AttachmentAssetRecord
    suspend fun promoteToDurable(asset: AttachmentAssetRecord, updatedAtEpochMillis: Long): AttachmentAssetRecord
    suspend fun open(asset: AttachmentAssetRecord): InputStream
    suspend fun exists(asset: AttachmentAssetRecord): Boolean
    suspend fun delete(asset: AttachmentAssetRecord): Boolean
    suspend fun deleteExpiredStaging(nowEpochMillis: Long): Int
}

/** Coordinates the byte store with its durable metadata row. */
interface AttachmentRepository {
    suspend fun stage(request: AttachmentWriteRequest): AttachmentAssetRecord
    suspend fun promoteToDurable(assetId: String, updatedAtEpochMillis: Long): AttachmentAssetRecord
    suspend fun get(assetId: String): AttachmentAssetRecord?
    suspend fun open(assetId: String): InputStream
    suspend fun removeIfUnreferenced(assetId: String): Boolean
    suspend fun cleanExpiredStaging(nowEpochMillis: Long): Int
}

class AttachmentTooLargeException(val limitBytes: Long) : Exception("Attachment exceeds $limitBytes bytes")

class InvalidAttachmentException(message: String) : Exception(message)
