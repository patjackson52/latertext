package com.patjackson.latertext.platform.android.intake

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.patjackson.latertext.platform.api.ContentIntakeSource
import com.patjackson.latertext.platform.api.IncomingContent

enum class IncomingAction { SHARE_ONE, SHARE_MULTIPLE, PROCESS_TEXT }

enum class IncomingDraftWarning {
    TEXT_TRUNCATED,
    MISSING_READ_URI_GRANT,
    UNSUPPORTED_URI_SCHEME,
    UNSUPPORTED_MEDIA_TYPE,
    EXTRA_MEDIA_IGNORED,
}

enum class DraftLaunchDisposition { REVIEW_AND_EDIT }

/** A parsed inbound request can only open an editable draft; it never represents a send command. */
data class IncomingDraftRequest(
    val text: String,
    val content: IncomingContent?,
    val action: IncomingAction,
    val warnings: Set<IncomingDraftWarning> = emptySet(),
    val ignoredMediaCount: Int = 0,
    val disposition: DraftLaunchDisposition = DraftLaunchDisposition.REVIEW_AND_EDIT,
) {
    init {
        require(text.isNotEmpty() || content != null || warnings.isNotEmpty()) {
            "An incoming draft must contain text, media, or an actionable intake warning"
        }
    }
}

internal data class RawIncomingUri(
    val uri: String,
    val resolvedMimeType: String?,
    val displayName: String?,
)

internal data class RawIncomingPayload(
    val action: IncomingAction,
    val text: String?,
    val subject: String?,
    val declaredMimeType: String?,
    val uris: List<RawIncomingUri>,
    val hasReadUriGrant: Boolean,
)

/** Pure normalization kept separate from Android extraction for fast local tests. */
internal class IncomingPayloadNormalizer(
    private val maximumTextLength: Int = 100_000,
) {
    fun normalize(payload: RawIncomingPayload): IncomingDraftRequest? {
        val warnings = linkedSetOf<IncomingDraftWarning>()
        val combinedText = combineText(payload.subject, payload.text)
        val text = if (combinedText.length > maximumTextLength) {
            warnings += IncomingDraftWarning.TEXT_TRUNCATED
            combinedText.take(maximumTextLength)
        } else {
            combinedText
        }

        if (payload.action == IncomingAction.PROCESS_TEXT) {
            return text.takeIf { it.isNotEmpty() }?.let {
                IncomingDraftRequest(text = it, content = null, action = payload.action, warnings = warnings)
            }
        }

        var ignoredCount = 0
        var selected: IncomingContent? = null
        for (candidate in payload.uris.distinctBy { it.uri }) {
            if (selected != null) {
                ignoredCount++
                warnings += IncomingDraftWarning.EXTRA_MEDIA_IGNORED
                continue
            }
            val scheme = runCatching { java.net.URI(candidate.uri).scheme?.lowercase() }.getOrNull()
            if (scheme != ContentResolver.SCHEME_CONTENT) {
                ignoredCount++
                warnings += IncomingDraftWarning.UNSUPPORTED_URI_SCHEME
                continue
            }
            if (!payload.hasReadUriGrant) {
                ignoredCount++
                warnings += IncomingDraftWarning.MISSING_READ_URI_GRANT
                continue
            }
            val mimeType = preferredMimeType(candidate.resolvedMimeType, payload.declaredMimeType)
            if (mimeType != null && mimeType !in ACCEPTED_MIME_TYPES) {
                ignoredCount++
                warnings += IncomingDraftWarning.UNSUPPORTED_MEDIA_TYPE
                continue
            }
            selected = IncomingContent(
                source = ContentIntakeSource.SHARE,
                uri = candidate.uri,
                declaredMimeType = mimeType,
                displayName = candidate.displayName,
            )
        }

        if (text.isEmpty() && selected == null && warnings.isEmpty()) return null
        return IncomingDraftRequest(
            text = text,
            content = selected,
            action = payload.action,
            warnings = warnings,
            ignoredMediaCount = ignoredCount,
        )
    }

    private fun combineText(subject: String?, body: String?): String {
        val cleanSubject = subject.orEmpty().trim()
        val cleanBody = body.orEmpty().trim()
        return when {
            cleanSubject.isEmpty() -> cleanBody
            cleanBody.isEmpty() -> cleanSubject
            cleanBody.startsWith(cleanSubject) -> cleanBody
            else -> "$cleanSubject\n$cleanBody"
        }
    }

    private fun preferredMimeType(resolved: String?, declared: String?): String? {
        val resolvedNormalized = normalizeMimeType(resolved)
        if (resolvedNormalized != null && '*' !in resolvedNormalized) return resolvedNormalized
        val declaredNormalized = normalizeMimeType(declared)
        return declaredNormalized?.takeUnless { '*' in it }
    }

    private fun normalizeMimeType(value: String?): String? {
        val normalized = value
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
        return when (normalized) {
            "image/jpg", "image/pjpeg" -> "image/jpeg"
            "image/x-png" -> "image/png"
            else -> normalized
        }
    }

    companion object {
        private val ACCEPTED_MIME_TYPES = setOf(
            "image/gif",
            "image/webp",
            "image/png",
            "image/jpeg",
        )
    }
}

/** Extracts supported Android inbound intents into an editable draft request. */
class IncomingIntentParser(
    private val contentResolver: ContentResolver,
) {
    private val normalizer = IncomingPayloadNormalizer()

    fun parse(intent: Intent?): IncomingDraftRequest? {
        intent ?: return null
        val action = when (intent.action) {
            Intent.ACTION_SEND -> IncomingAction.SHARE_ONE
            Intent.ACTION_SEND_MULTIPLE -> IncomingAction.SHARE_MULTIPLE
            Intent.ACTION_PROCESS_TEXT -> IncomingAction.PROCESS_TEXT
            else -> return null
        }
        val text = runCatching {
            when (action) {
                IncomingAction.PROCESS_TEXT ->
                    intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                else -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            }
        }.getOrNull()
        val subject = if (action == IncomingAction.PROCESS_TEXT) {
            null
        } else {
            runCatching { intent.getStringExtra(Intent.EXTRA_SUBJECT) }.getOrNull()
        }
        val uris = if (action == IncomingAction.PROCESS_TEXT) {
            emptyList()
        } else {
            runCatching { extractUris(intent) }.getOrDefault(emptyList())
        }
        return normalizer.normalize(
            RawIncomingPayload(
                action = action,
                text = text,
                subject = subject,
                declaredMimeType = intent.type,
                uris = uris.map { uri ->
                    val isContentUri = uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)
                    RawIncomingUri(
                        uri = uri.toString(),
                        resolvedMimeType = if (isContentUri) {
                            runCatching { contentResolver.getType(uri) }.getOrNull()
                        } else {
                            null
                        },
                        displayName = if (isContentUri) displayName(uri) else null,
                    )
                },
                hasReadUriGrant = intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
            ),
        )
    }

    private fun extractUris(intent: Intent): List<Uri> {
        val streams = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(parcelableUri(intent, Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> parcelableUriList(intent, Intent.EXTRA_STREAM)
            else -> emptyList()
        }
        val clipUris = buildList {
            val clip = intent.clipData ?: return@buildList
            repeat(clip.itemCount) { index -> clip.getItemAt(index).uri?.let(::add) }
        }
        return (streams + clipUris).distinctBy(Uri::toString)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun parcelableUri(intent: Intent, key: String): Uri? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(key, Uri::class.java)
    } else {
        intent.getParcelableExtra(key)
    }

    @Suppress("DEPRECATION")
    private fun parcelableUriList(intent: Intent, key: String): List<Uri> =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(key, Uri::class.java).orEmpty()
        } else {
            intent.getParcelableArrayListExtra<Uri>(key).orEmpty()
        }
}
