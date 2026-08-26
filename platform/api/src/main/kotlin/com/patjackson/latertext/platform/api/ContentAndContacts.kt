package com.patjackson.latertext.platform.api

enum class ContentIntakeSource {
    KEYBOARD,
    CLIPBOARD,
    PHOTO_PICKER,
    SHARE,
    DRAG_DROP,
}

data class IncomingContent(
    val source: ContentIntakeSource,
    val uri: String,
    val declaredMimeType: String?,
    val displayName: String? = null,
)

sealed interface ContentImportResult {
    data class Imported(
        val stagedPath: String,
        val mimeType: String,
        val byteCount: Long,
        val width: Int?,
        val height: Int?,
        val animated: Boolean,
    ) : ContentImportResult

    data class Rejected(val reason: ContentRejection) : ContentImportResult
}

enum class ContentRejection {
    UNSUPPORTED_TYPE,
    EMPTY,
    TOO_LARGE,
    INVALID_SIGNATURE,
    DIMENSIONS_TOO_LARGE,
    UNREADABLE,
    STORAGE_UNAVAILABLE,
}

interface IncomingContentImporter {
    suspend fun import(content: IncomingContent): ContentImportResult
}

data class ContactPhoneSelection(
    val displayName: String?,
    val normalizedAddress: String,
    val label: String?,
    val lookupKey: String?,
)

interface ContactSelectionGateway {
    suspend fun pickPhoneNumber(): ContactPhoneSelection?
}
