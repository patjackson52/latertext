package com.patjackson.latertext

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.patjackson.latertext.core.model.RecurrenceFrequency
import com.sloopworks.debugdrawer.*
import com.sloopworks.debugdrawer.swip.SwipInspectorPlugin
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import works.sloop.swip.*
import works.sloop.swip.debug.RingDebugSink
import works.sloop.swip.schema.latertext.*
import kotlin.random.Random

/** Session-only diagnostics. No network transport, disk queue, or persistent identity. */
@Singleton
class LaterTextDiagnostics @Inject constructor(@ApplicationContext private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ring = RingDebugSink(scope, SystemClock::elapsedRealtime, maxEntries = 200)
    private val enabled = MutableStateFlow(false)
    private val ready = MutableStateFlow(false)
    private val failed = MutableStateFlow(false)
    private var epoch by mutableIntStateOf(0)
    private var pending: SwipEvent? = null
    private var installed = false
    private val swip = Swip.init(
        LatertextSwip.androidDev(),
        LatertextSwip.platformDeps(
            storage = InMemorySwipStorage(), appVersion = BuildConfig.VERSION_NAME,
            os = "android", nowMs = System::currentTimeMillis,
            monotonicNowMs = SystemClock::elapsedRealtime, random = Random::nextDouble,
            flushIntervalMs = 0,
        ).copy(debuggable = true, debugSink = object : SwipDebugSink {
            override fun record(rec: DebugRecord) {
                if (enabled.value) sanitizedDiagnosticRecord(rec, pending)?.let(ring::record)
            }
        }), scope,
    )

    fun install() {
        if (installed) return
        installed = true
        scope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    DebugDrawer.install(DebugDrawerConfig(
                        buildInfo = BuildInfo(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString()),
                        includeBuiltins = false,
                        plugins = listOf(object : DebugPlugin {
                            override val id = "latertext-diagnostics"
                            override val title = "Diagnostics"
                            @Composable override fun Content(scope: DebugScope) = Panel()
                        }, object : DebugPlugin {
                            override val id = "latertext-analytics"
                            override val title = "SWIP analytics"
                            @Composable override fun Content(scope: DebugScope) {
                                key(epoch) { SwipInspectorPlugin(ring.entries, showIdentifiers = false).Content(scope) }
                            }
                        })), context)
                }.isSuccess
            }
            ready.value = success
            failed.value = !success
        }
    }

    private fun setEnabled(value: Boolean) {
        enabled.value = false
        pending = null
        swip.analytics.setCollectionMode(CollectionMode.OFF)
        swip.analytics.setConsent(ConsentScope.entries.associateWith { ConsentDecision.DENIED })
        ring.clear()
        epoch++
        if (value) {
            swip.analytics.setCollectionMode(CollectionMode.ANONYMOUS)
            swip.analytics.setConsent(ConsentScope.entries.associateWith {
                if (it == ConsentScope.ANALYTICS) ConsentDecision.GRANTED else ConsentDecision.DENIED
            })
            enabled.value = true
        }
    }

    private fun track(event: SwipEvent) {
        if (!enabled.value) return
        pending = event
        try { swip.analytics.track(event) } finally { pending = null }
    }

    fun screenViewed(screen: AppScreen) = track(LatertextScreenViewed(LatertextScreenViewed.Screen.valueOf(screen.name)))
    fun scheduleSaved(success: Boolean, frequency: RecurrenceFrequency, hasMedia: Boolean) = track(
        LatertextScheduleSaved(
            if (success) LatertextScheduleSaved.Outcome.SAVED else LatertextScheduleSaved.Outcome.FAILED,
            LatertextScheduleSaved.Frequency.valueOf(frequency.name), hasMedia,
        ),
    )
    fun scheduleChanged(operation: DiagnosticOperation) = track(
        LatertextScheduleChanged(LatertextScheduleChanged.Operation.valueOf(operation.name)),
    )

    @Composable fun Host(content: @Composable () -> Unit) {
        val installed by ready.collectAsState()
        val unavailable by failed.collectAsState()
        Box(Modifier.fillMaxSize()) {
            content()
            if (installed) Box(Modifier.fillMaxSize().safeDrawingPadding()) { DebugDrawerHost {} }
            if (unavailable) Text("Diagnostics unavailable", Modifier.safeDrawingPadding())
        }
    }

    @Composable private fun Panel() {
        val recording by enabled.collectAsState()
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Local diagnostics", style = MaterialTheme.typography.titleLarge)
            Text("Record screen names and scheduling outcomes in memory for this session. Message text, recipients, attachments, and identifiers are excluded. Nothing is uploaded.")
            Button(onClick = { setEnabled(!recording) }) {
                Text(if (recording) "Stop and clear events" else "Enable for this session")
            }
            Text(if (recording) "Recording locally" else "Recording off")
            Text("LaterText ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        }
    }
}

internal fun sanitizedDiagnosticRecord(record: DebugRecord, expected: SwipEvent?): DebugRecord.Enqueued? {
    if (expected !is LatertextScreenViewed && expected !is LatertextScheduleSaved && expected !is LatertextScheduleChanged) return null
    if (record !is DebugRecord.Enqueued || record.schema != expected.schema) return null
    return record.copy(eventId = "", distinctId = null, sessionId = null,
        propsRaw = expected.props, propsStripped = null)
}
