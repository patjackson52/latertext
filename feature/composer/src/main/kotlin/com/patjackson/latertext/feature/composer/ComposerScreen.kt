package com.patjackson.latertext.feature.composer

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.patjackson.latertext.core.designsystem.AdaptiveActionRow
import com.patjackson.latertext.core.designsystem.MessageMediaPreview
import kotlinx.coroutines.flow.distinctUntilChanged

data class ComposerUiState(
    val recipient: String = "",
    val recipientName: String? = null,
    val message: String = "",
    val attachmentLabel: String? = null,
    val attachmentMimeType: String? = null,
    val importInProgress: Boolean = false,
    val error: String? = null,
    val smsPartCount: Int = 1,
    val attachmentPath: String? = null,
    val attachmentWidthPixels: Int? = null,
    val attachmentHeightPixels: Int? = null,
    val attachmentIsAnimated: Boolean = false,
) {
    val canSubmit: Boolean
        get() = !importInProgress && recipient.isNotBlank() &&
            (message.isNotBlank() || attachmentLabel != null)
}

enum class RichContentSource { KEYBOARD, CLIPBOARD, DRAG_DROP }

data class RichContentSelection(
    val uri: Uri,
    val declaredMimeType: String?,
    val source: RichContentSource,
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ComposerScreen(
    state: ComposerUiState,
    onRecipientChange: (String) -> Unit,
    onMessageChange: (String) -> Unit,
    onPickContact: () -> Unit,
    onPickPhoto: () -> Unit,
    onRichContent: (RichContentSelection) -> Unit,
    onRemoveAttachment: () -> Unit,
    onSendNow: () -> Unit,
    onSchedule: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    Column(
        modifier = modifier
            .fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("New message", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = state.recipient,
                onValueChange = onRecipientChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("To") },
                placeholder = { Text("Phone number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                supportingText = state.recipientName?.let { name -> ({ Text(name) }) },
            )
            OutlinedButton(
                onClick = onPickContact,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Choose a contact") }

            RichContentTextField(
                initialText = state.message,
                onTextChange = onMessageChange,
                onRichContent = onRichContent,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.attachmentLabel != null) {
                androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
                    state.attachmentPath?.let { path ->
                        MessageMediaPreview(
                            absolutePath = path,
                            mimeType = state.attachmentMimeType,
                            isAnimated = state.attachmentIsAnimated,
                            widthPixels = state.attachmentWidthPixels,
                            heightPixels = state.attachmentHeightPixels,
                            modifier = Modifier.padding(start = 14.dp, top = 14.dp, end = 14.dp),
                            maximumHeight = 360.dp,
                        )
                    }
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(state.attachmentLabel, style = MaterialTheme.typography.titleSmall)
                            Text(
                                state.attachmentMimeType.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "At send time, LaterText will ask you to review and share this in a messaging app. " +
                                    "LaterText cannot verify that it was sent.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = onRemoveAttachment,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) { Text("Remove attachment") }
                }
            } else {
                OutlinedButton(
                    onClick = onPickPhoto,
                    enabled = !state.importInProgress,
                ) {
                    Text(if (state.importInProgress) "Adding…" else "Add photo or GIF")
                }
                Text(
                    "You can also use your keyboard’s GIF or sticker button here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                if (state.attachmentLabel == null) {
                    "Automatic SMS · ${state.smsPartCount} part${if (state.smsPartCount == 1) "" else "s"}"
                } else {
                    "Automatic MMS when carrier limits allow · otherwise review before sharing"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }

        AdaptiveActionRow(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            secondary = {
                OutlinedButton(
                    onClick = {
                        focusManager.clearFocus()
                        onSendNow()
                    },
                    enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.attachmentLabel == null) "Send now" else "Send / review now")
                }
            },
            primary = {
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        onSchedule()
                    },
                    enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Schedule") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RichContentTextField(
    initialText: String,
    onTextChange: (String) -> Unit,
    onRichContent: (RichContentSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textState = rememberTextFieldState(initialText)
    LaunchedEffect(textState) {
        snapshotFlow { textState.text.toString() }
            .distinctUntilChanged()
            .collect(onTextChange)
    }
    LaunchedEffect(initialText) {
        if (textState.text.toString() != initialText) {
            textState.edit { replace(0, length, initialText) }
        }
    }

    OutlinedTextField(
        state = textState,
        modifier = modifier.contentReceiver { content: TransferableContent ->
            if (!content.hasMediaType(MediaType.Image)) return@contentReceiver content
            var acceptedOne = false
            val description = content.clipMetadata.clipDescription
            val mime = (0 until description.mimeTypeCount)
                .map(description::getMimeType)
                .firstOrNull { it.startsWith("image/") }
            content.consume { item ->
                val uri = item.uri
                if (uri == null) {
                    false
                } else {
                    if (!acceptedOne) {
                        acceptedOne = true
                        val source = when (content.source) {
                            TransferableContent.Source.Keyboard -> RichContentSource.KEYBOARD
                            TransferableContent.Source.Clipboard -> RichContentSource.CLIPBOARD
                            else -> RichContentSource.DRAG_DROP
                        }
                        onRichContent(RichContentSelection(uri, mime, source))
                    }
                    // Consume additional image URIs so a one-attachment composer cannot insert them.
                    true
                }
            }
        },
        label = { Text("Message") },
        placeholder = { Text("Write something thoughtful…") },
        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 5, maxHeightInLines = 12),
    )
}
