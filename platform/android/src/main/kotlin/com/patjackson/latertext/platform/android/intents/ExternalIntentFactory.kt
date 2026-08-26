package com.patjackson.latertext.platform.android.intents

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi

object ExternalIntentFactory {
    /** Launch from an Activity Result launcher and refresh readiness after the Activity resumes. */
    @RequiresApi(Build.VERSION_CODES.S)
    fun requestExactAlarmAccess(packageName: String): Intent {
        require(packageName.isNotBlank())
        return Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.fromParts("package", packageName, null),
        )
    }

    /**
     * Best-effort recipient handoff. Android does not expose another messaging app's thread ID,
     * so callers must label this as opening the recipient in the messaging app.
     */
    fun openRecipientInMessagingApp(normalizedAddress: String): Intent {
        require(normalizedAddress.isNotBlank())
        return Intent(
            Intent.ACTION_SENDTO,
            Uri.fromParts("smsto", normalizedAddress, null),
        )
    }
}
