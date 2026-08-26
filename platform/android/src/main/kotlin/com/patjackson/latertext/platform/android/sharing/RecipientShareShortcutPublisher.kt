package com.patjackson.latertext.platform.android.sharing

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat

data class RecipientShareShortcut(
    /** A local opaque recipient ID; never use a phone number as a shortcut ID. */
    val recipientId: String,
    val displayName: String,
)

/** Publishes at most four recent recipients for Android Direct Share. */
class RecipientShareShortcutPublisher(
    context: Context,
    private val targetActivity: Class<out Activity>,
    private val shareTargetCategory: String,
) {
    private val appContext = context.applicationContext

    init { require(shareTargetCategory.isNotBlank()) }

    fun replace(recipients: List<RecipientShareShortcut>): Boolean {
        clear()
        val platformLimit = ShortcutManagerCompat.getMaxShortcutCountPerActivity(appContext)
        val limit = minOf(MAX_RECIPIENT_SHORTCUTS, platformLimit.coerceAtLeast(0))
        if (limit == 0) return recipients.isEmpty()
        val shortcuts = recipients
            .distinctBy { it.recipientId }
            .take(limit)
            .mapIndexed { rank, recipient -> shortcut(recipient, rank) }
        return shortcuts.isEmpty() || ShortcutManagerCompat.addDynamicShortcuts(appContext, shortcuts)
    }

    fun clear() {
        val managedIds = ShortcutManagerCompat.getDynamicShortcuts(appContext)
            .map { it.id }
            .filter { it.startsWith(SHORTCUT_PREFIX) }
        if (managedIds.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(appContext, managedIds)
    }

    fun reportUsed(recipientId: String) {
        require(recipientId.isNotBlank())
        ShortcutManagerCompat.reportShortcutUsed(appContext, shortcutId(recipientId))
    }

    /** Handles both launcher shortcut intents and Direct Share's standard shortcut-ID extra. */
    fun recipientIdFromLaunch(intent: Intent?): String? {
        intent ?: return null
        val explicit = runCatching { intent.getStringExtra(EXTRA_RECIPIENT_ID) }.getOrNull()
        if (!explicit.isNullOrBlank()) return explicit
        val shortcutId = runCatching {
            intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID)
        }.getOrNull() ?: return null
        return shortcutId.takeIf { it.startsWith(SHORTCUT_PREFIX) }
            ?.removePrefix(SHORTCUT_PREFIX)
            ?.takeIf { it.isNotBlank() }
    }

    private fun shortcut(recipient: RecipientShareShortcut, rank: Int): ShortcutInfoCompat {
        require(recipient.recipientId.isNotBlank())
        require(recipient.displayName.isNotBlank())
        val id = shortcutId(recipient.recipientId)
        val launchIntent = Intent(appContext, targetActivity).apply {
            action = ACTION_COMPOSE_TO_RECIPIENT
            putExtra(EXTRA_RECIPIENT_ID, recipient.recipientId)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val person = Person.Builder()
            .setName(recipient.displayName)
            .setKey(recipient.recipientId)
            .build()
        return ShortcutInfoCompat.Builder(appContext, id)
            .setShortLabel(recipient.displayName)
            .setLongLabel(recipient.displayName)
            .setIntent(launchIntent)
            .setCategories(setOf(shareTargetCategory))
            .setPerson(person)
            .setLongLived(true)
            .setRank(rank)
            .build()
    }

    private fun shortcutId(recipientId: String): String = SHORTCUT_PREFIX + recipientId

    companion object {
        const val ACTION_COMPOSE_TO_RECIPIENT =
            "com.patjackson.latertext.action.COMPOSE_TO_RECIPIENT"
        const val EXTRA_RECIPIENT_ID = "com.patjackson.latertext.extra.RECIPIENT_ID"
        const val DEFAULT_SHARE_TARGET_CATEGORY =
            "com.patjackson.latertext.category.TEXT_OR_MEDIA_SHARE"
        private const val SHORTCUT_PREFIX = "recipient_"
        private const val MAX_RECIPIENT_SHORTCUTS = 4
    }
}
