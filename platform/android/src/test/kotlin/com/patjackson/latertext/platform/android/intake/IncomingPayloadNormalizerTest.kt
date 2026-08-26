package com.patjackson.latertext.platform.android.intake

import com.patjackson.latertext.platform.api.ContentIntakeSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IncomingPayloadNormalizerTest {
    @Test
    fun `process text creates reviewable text draft and ignores hostile media`() {
        val result = normalizer().normalize(
            payload(
                action = IncomingAction.PROCESS_TEXT,
                text = " selected text ",
                uris = listOf(uri()),
            ),
        )
        requireNotNull(result)
        assertEquals("selected text", result.text)
        assertNull(result.content)
        assertEquals(DraftLaunchDisposition.REVIEW_AND_EDIT, result.disposition)
    }

    @Test
    fun `share combines distinct subject and body into editable text`() {
        val result = normalizer().normalize(
            payload(text = "https://example.com/post", subject = "A useful post"),
        )
        assertEquals("A useful post\nhttps://example.com/post", result?.text)
    }

    @Test
    fun `subject already present in body is not duplicated`() {
        val result = normalizer().normalize(
            payload(text = "A useful post\nhttps://example.com", subject = "A useful post"),
        )
        assertEquals("A useful post\nhttps://example.com", result?.text)
    }

    @Test
    fun `first supported granted content URI becomes one share attachment`() {
        val result = normalizer().normalize(
            payload(
                declaredMime = "image/*",
                uris = listOf(
                    uri("content://provider/first", "image/gif", "first.gif"),
                    uri("content://provider/second", "image/png", "second.png"),
                ),
            ),
        )
        requireNotNull(result)
        assertEquals("content://provider/first", result.content?.uri)
        assertEquals("image/gif", result.content?.declaredMimeType)
        assertEquals("first.gif", result.content?.displayName)
        assertEquals(ContentIntakeSource.SHARE, result.content?.source)
        assertEquals(1, result.ignoredMediaCount)
        assertTrue(IncomingDraftWarning.EXTRA_MEDIA_IGNORED in result.warnings)
    }

    @Test
    fun `unsupported first URI does not prevent selecting next valid URI`() {
        val result = normalizer().normalize(
            payload(
                declaredMime = "application/octet-stream",
                uris = listOf(
                    uri("content://provider/document", "application/pdf"),
                    uri("content://provider/image", "image/jpeg"),
                ),
            ),
        )
        requireNotNull(result)
        assertEquals("content://provider/image", result.content?.uri)
        assertEquals(1, result.ignoredMediaCount)
        assertTrue(IncomingDraftWarning.UNSUPPORTED_MEDIA_TYPE in result.warnings)
    }

    @Test
    fun `missing URI grant yields actionable draft warning and no attachment`() {
        val result = normalizer().normalize(
            payload(
                uris = listOf(uri()),
                hasGrant = false,
            ),
        )
        requireNotNull(result)
        assertNull(result.content)
        assertEquals(setOf(IncomingDraftWarning.MISSING_READ_URI_GRANT), result.warnings)
        assertEquals(DraftLaunchDisposition.REVIEW_AND_EDIT, result.disposition)
    }

    @Test
    fun `file URI is rejected instead of bypassing content grants`() {
        val result = normalizer().normalize(
            payload(uris = listOf(uri("file:///sdcard/image.gif", "image/gif"))),
        )
        requireNotNull(result)
        assertNull(result.content)
        assertTrue(IncomingDraftWarning.UNSUPPORTED_URI_SCHEME in result.warnings)
    }

    @Test
    fun `unknown image wildcard remains sniffable while jpeg aliases normalize`() {
        val wildcard = normalizer().normalize(
            payload(declaredMime = "image/*", uris = listOf(uri(resolvedMime = null))),
        )
        assertNull(wildcard?.content?.declaredMimeType)

        val jpeg = normalizer().normalize(
            payload(declaredMime = "image/jpg; charset=binary", uris = listOf(uri(resolvedMime = null))),
        )
        assertEquals("image/jpeg", jpeg?.content?.declaredMimeType)
    }

    @Test
    fun `oversized text is bounded and marked`() {
        val result = IncomingPayloadNormalizer(maximumTextLength = 5).normalize(
            payload(text = "123456789"),
        )
        assertEquals("12345", result?.text)
        assertEquals(setOf(IncomingDraftWarning.TEXT_TRUNCATED), result?.warnings)
    }

    @Test
    fun `empty share intent is ignored`() {
        assertNull(normalizer().normalize(payload()))
    }

    private fun normalizer() = IncomingPayloadNormalizer()

    private fun payload(
        action: IncomingAction = IncomingAction.SHARE_ONE,
        text: String? = null,
        subject: String? = null,
        declaredMime: String? = null,
        uris: List<RawIncomingUri> = emptyList(),
        hasGrant: Boolean = true,
    ) = RawIncomingPayload(
        action = action,
        text = text,
        subject = subject,
        declaredMimeType = declaredMime,
        uris = uris,
        hasReadUriGrant = hasGrant,
    )

    private fun uri(
        value: String = "content://provider/media",
        resolvedMime: String? = "image/gif",
        displayName: String? = null,
    ) = RawIncomingUri(value, resolvedMime, displayName)
}
