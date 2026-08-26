package com.patjackson.latertext.platform.android.contacts

import android.content.ContentResolver
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsPickerSessionContract
import android.telephony.PhoneNumberUtils
import androidx.annotation.RequiresApi
import com.patjackson.latertext.platform.api.ContactPhoneSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ContactPickerKind { API_37_SESSION, LEGACY_PHONE_ROW }

data class ContactPickerRequest(
    val intent: Intent,
    val kind: ContactPickerKind,
)

/**
 * Creates privacy-preserving contact picker requests. The Activity owns launchers and passes the
 * returned Intent back to [resolveResult] while its temporary URI grant is still alive.
 */
class ContactSelectionHelper(
    private val contentResolver: ContentResolver,
    private val resources: Resources,
) {
    fun createPhonePickerRequest(): ContactPickerRequest = if (Build.VERSION.SDK_INT >= 37) {
        ContactPickerRequest(Api37Contacts.phonePickerIntent(), ContactPickerKind.API_37_SESSION)
    } else {
        ContactPickerRequest(
            intent = Intent(Intent.ACTION_PICK).apply {
                setDataAndType(Phone.CONTENT_URI, Phone.CONTENT_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            kind = ContactPickerKind.LEGACY_PHONE_ROW,
        )
    }

    /** No READ_CONTACTS permission is required; access is restricted to the returned result URI. */
    suspend fun resolveResult(resultIntent: Intent?): ContactPhoneSelection? = withContext(Dispatchers.IO) {
        val uri = resultIntent?.data ?: return@withContext null
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return@withContext null
        runCatching { querySelectedPhone(uri) }.getOrNull()
    }

    private fun querySelectedPhone(uri: Uri): ContactPhoneSelection? {
        val projection = arrayOf(
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA2,
            ContactsContract.Data.DATA3,
            Phone.NORMALIZED_NUMBER,
        )
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val lookupIndex = cursor.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)
            val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val mimeIndex = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE)
            val numberIndex = cursor.getColumnIndex(ContactsContract.Data.DATA1)
            val typeIndex = cursor.getColumnIndex(ContactsContract.Data.DATA2)
            val customLabelIndex = cursor.getColumnIndex(ContactsContract.Data.DATA3)
            val normalizedIndex = cursor.getColumnIndex(Phone.NORMALIZED_NUMBER)
            while (cursor.moveToNext()) {
                val mimeType = cursor.stringOrNull(mimeIndex)
                if (mimeType != null && mimeType != Phone.CONTENT_ITEM_TYPE) continue
                val enteredNumber = cursor.stringOrNull(numberIndex)?.trim().orEmpty()
                if (enteredNumber.isEmpty()) continue
                val normalized = cursor.stringOrNull(normalizedIndex)
                    ?.takeIf { it.isNotBlank() }
                    ?: PhoneNumberUtils.normalizeNumber(enteredNumber).takeIf { it.isNotBlank() }
                    ?: enteredNumber
                val type = cursor.intOrNull(typeIndex)
                val customLabel = cursor.stringOrNull(customLabelIndex)
                val label = type?.let { Phone.getTypeLabel(resources, it, customLabel).toString() }
                return ContactPhoneSelection(
                    displayName = cursor.stringOrNull(nameIndex),
                    normalizedAddress = normalized,
                    label = label,
                    lookupKey = cursor.stringOrNull(lookupIndex),
                )
            }
        }
        return null
    }

    private fun android.database.Cursor.stringOrNull(index: Int): String? =
        if (index >= 0 && !isNull(index)) getString(index) else null

    private fun android.database.Cursor.intOrNull(index: Int): Int? =
        if (index >= 0 && !isNull(index)) getInt(index) else null
}

@RequiresApi(37)
private object Api37Contacts {
    fun phonePickerIntent(): Intent = Intent(ContactsPickerSessionContract.ACTION_PICK_CONTACTS).apply {
        putStringArrayListExtra(
            ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_REQUESTED_DATA_FIELDS,
            arrayListOf(Phone.CONTENT_ITEM_TYPE),
        )
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        putExtra(ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_SELECTION_LIMIT, 1)
    }
}
