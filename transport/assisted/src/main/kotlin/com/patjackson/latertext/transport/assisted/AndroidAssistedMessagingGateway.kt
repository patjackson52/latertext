package com.patjackson.latertext.transport.assisted

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.patjackson.latertext.platform.api.AssistedHandoffKind
import com.patjackson.latertext.platform.api.AssistedHandoffRequest
import com.patjackson.latertext.platform.api.AssistedMessagingGateway
import java.io.File

class AndroidAssistedMessagingGateway(
    private val context: Context,
    private val fileProviderAuthority: String = "${context.packageName}.files",
) : AssistedMessagingGateway {
    override fun open(request: AssistedHandoffRequest): Boolean {
        val intent = when (request.kind) {
            AssistedHandoffKind.TEXT -> Intent(
                Intent.ACTION_SENDTO,
                Uri.fromParts("smsto", request.recipientAddress, null),
            ).apply { putExtra("sms_body", request.body) }

            AssistedHandoffKind.MEDIA -> mediaIntent(request) ?: return false
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (intent.resolveActivity(context.packageManager) == null) return false
        context.startActivity(intent)
        return true
    }

    private fun mediaIntent(request: AssistedHandoffRequest): Intent? {
        val path = request.attachmentPath ?: return null
        val mimeType = request.attachmentMimeType ?: return null
        val file = File(path)
        if (!file.isFile) return null
        val uri = FileProvider.getUriForFile(context, fileProviderAuthority, file)

        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_TEXT, request.body)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Share to a messaging app",
        )
    }
}
