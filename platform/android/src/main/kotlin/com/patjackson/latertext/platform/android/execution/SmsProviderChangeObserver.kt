package com.patjackson.latertext.platform.android.execution

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.Telephony
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Process-lifetime fast path; WorkManager and app-start reconciliation cover process death. */
class SmsProviderChangeObserver(
    private val context: Context,
    private val reader: SmsProviderReader,
    private val reconciler: SmsProviderReconciler,
) {
    private val registered = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val observerThread = HandlerThread("LaterTextSmsProviderObserver").apply { start() }
    private val observer = object : ContentObserver(Handler(observerThread.looper)) {
        override fun onChange(selfChange: Boolean) {
            handleChange(null, 0)
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            handleChange(uri, 0)
        }

        override fun onChange(selfChange: Boolean, uri: Uri?, flags: Int) {
            handleChange(uri, flags)
        }

        override fun onChange(
            selfChange: Boolean,
            uris: MutableCollection<Uri>,
            flags: Int,
        ) {
            handleChanges(uris, flags)
        }
    }

    /** Runs on a dedicated observer thread so a short-lived provider row is read immediately. */
    private fun handleChange(uri: Uri?, flags: Int) {
        val records = uri
            ?.let(reader::readChangedUri)
            .let { it as? SmsProviderReadResult.Records }
            ?.values
            .orEmpty()
        reconcileSnapshot(records, uri?.toString(), flags)
    }

    /** API 30+ providers may batch several changed row URIs into one notification. */
    private fun handleChanges(uris: Collection<Uri>, flags: Int) {
        val records = uris.flatMap { uri ->
            (reader.readChangedUri(uri) as? SmsProviderReadResult.Records)?.values.orEmpty()
        }.distinctBy(SmsProviderMessage::id)
        reconcileSnapshot(records, uris.joinToString(limit = 3), flags)
    }

    private fun reconcileSnapshot(records: List<SmsProviderMessage>, source: String?, flags: Int) {
        if (records.isNotEmpty()) {
            records.forEach { message ->
                Log.d(
                    TAG,
                    "captured row=${message.id} type=${message.type} status=${message.status} flags=$flags",
                )
            }
        }
        scope.launch {
            runCatching {
                if (records.isEmpty()) reconciler.reconcilePending()
                else reconciler.reconcileObserved(records)
            }.onFailure { error ->
                Log.w(TAG, "provider reconciliation failed source=$source flags=$flags", error)
            }
        }
    }

    fun start() {
        if (!reader.canRead() || !registered.compareAndSet(false, true)) return
        runCatching {
            context.contentResolver.registerContentObserver(
                Telephony.Sms.CONTENT_URI,
                true,
                observer,
            )
            Log.d(TAG, "SMS provider observer registered")
        }.onFailure {
            registered.set(false)
            Log.w(TAG, "SMS provider observer registration failed", it)
            return
        }
        scope.launch { runCatching { reconciler.reconcilePending() } }
    }

    fun stop() {
        if (!registered.compareAndSet(true, false)) return
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
    }

    companion object {
        private const val TAG = "LaterTextSmsProvider"
    }
}
