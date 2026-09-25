package com.patjackson.latertext

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.patjackson.latertext.platform.android.AndroidNotificationPublisher
import com.patjackson.latertext.platform.android.execution.ExecutionRecoveryCoordinator
import com.patjackson.latertext.platform.android.execution.ExecutionWatchdogScheduler
import com.patjackson.latertext.platform.android.execution.SmsProviderChangeObserver
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class LaterTextApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var recovery: ExecutionRecoveryCoordinator
    @Inject lateinit var watchdog: ExecutionWatchdogScheduler
    @Inject lateinit var smsProviderObserver: SmsProviderChangeObserver
    @Inject lateinit var diagnostics: LaterTextDiagnostics

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        diagnostics.install()
        AndroidNotificationPublisher(this).ensureChannels()
        watchdog.ensureScheduled()
        smsProviderObserver.start()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { recovery.onAppStart() }
                .onSuccess { Log.d("LaterTextRecovery", "app-start recovery: $it") }
                .onFailure { Log.e("LaterTextRecovery", "app-start recovery failed", it) }
        }
    }
}
