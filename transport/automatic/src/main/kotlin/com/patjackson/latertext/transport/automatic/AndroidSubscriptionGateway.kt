package com.patjackson.latertext.transport.automatic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import com.patjackson.latertext.platform.api.SubscriptionGateway
import com.patjackson.latertext.platform.api.SubscriptionSummary

class AndroidSubscriptionGateway(
    private val context: Context,
    private val manager: SubscriptionManager = context.getSystemService(SubscriptionManager::class.java),
) : SubscriptionGateway {
    override fun activeSubscriptions(): List<SubscriptionSummary> {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val defaultId = defaultSmsSubscriptionId()
        return runCatching { manager.activeSubscriptionInfoList.orEmpty() }
            .getOrDefault(emptyList())
            .map { it.toSummary(defaultId) }
    }

    override fun defaultSmsSubscriptionId(): Int? =
        SubscriptionManager.getDefaultSmsSubscriptionId()
            .takeIf { it >= 0 }

    override fun isActive(subscriptionId: Int): Boolean =
        activeSubscriptions().any { it.subscriptionId == subscriptionId }

    private fun SubscriptionInfo.toSummary(defaultId: Int?): SubscriptionSummary =
        SubscriptionSummary(
            subscriptionId = subscriptionId,
            displayName = displayName?.toString().orEmpty().ifBlank { "SIM ${simSlotIndex + 1}" },
            carrierName = carrierName?.toString(),
            isDefaultForSms = subscriptionId == defaultId,
        )
}
