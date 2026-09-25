package com.patjackson.latertext

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.lifecycleScope
import com.patjackson.latertext.core.designsystem.LaterTextTheme
import com.patjackson.latertext.core.model.MissedPolicy
import com.patjackson.latertext.feature.composer.ComposerScreen
import com.patjackson.latertext.feature.history.HistoryFilter
import com.patjackson.latertext.feature.history.HistoryScreen
import com.patjackson.latertext.feature.schedules.ScheduleDetailScreen
import com.patjackson.latertext.feature.schedules.ScheduleEditorScreen
import com.patjackson.latertext.feature.schedules.UpcomingScreen
import com.patjackson.latertext.feature.settings.SettingsScreen
import com.patjackson.latertext.platform.android.contacts.ContactSelectionHelper
import com.patjackson.latertext.platform.android.intake.IncomingIntentParser
import com.patjackson.latertext.platform.android.AndroidNotificationPublisher
import com.patjackson.latertext.platform.android.sharing.RecipientShareShortcutPublisher
import com.patjackson.latertext.platform.android.execution.SmsProviderChangeObserver
import dagger.hilt.android.AndroidEntryPoint
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var shareShortcuts: RecipientShareShortcutPublisher
    @Inject lateinit var smsProviderObserver: SmsProviderChangeObserver
    @Inject lateinit var diagnostics: LaterTextDiagnostics
    private val viewModel: AppViewModel by viewModels()
    internal val contactSelectionHelper by lazy { ContactSelectionHelper(contentResolver, resources) }
    private val incomingIntentParser by lazy { IncomingIntentParser(contentResolver) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIncomingIntent(intent)
        setContent {
            LaterTextTheme {
                diagnostics.Host { LaterTextApp(viewModel) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        smsProviderObserver.start()
        viewModel.refresh()
    }

    private fun handleIncomingIntent(intent: Intent?) {
        intent?.getStringExtra(AndroidNotificationPublisher.EXTRA_OCCURRENCE_ID)?.let {
            viewModel.openOccurrence(it)
            intent.removeExtra(AndroidNotificationPublisher.EXTRA_OCCURRENCE_ID)
            return
        }
        val recipientId = shareShortcuts.recipientIdFromLaunch(intent)
        val request = incomingIntentParser.parse(intent)
        if (request == null && recipientId == null) return
        viewModel.newMessage()
        recipientId?.let(viewModel::setRecipientById)
        request?.text?.takeIf(String::isNotBlank)?.let(viewModel::setMessage)
        request?.content?.let { incoming ->
            viewModel.importShare(Uri.parse(incoming.uri), incoming.declaredMimeType, request.text)
        }
    }

    fun openConversation(recipient: String, body: String? = null) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", recipient, null)).apply {
            body?.let { putExtra("sms_body", it) }
        }
        if (intent.resolveActivity(packageManager) != null) startActivity(intent)
    }
}

@Composable
private fun MainActivity.LaterTextApp(viewModel: AppViewModel) {
    val ui by viewModel.uiState.collectAsState()
    LaunchedEffect(ui.screen) { diagnostics.screenViewed(ui.screen) }
    val snackbarHost = remember { SnackbarHostState() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refresh() }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }
    val photoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { viewModel.importPhoto(it, contentResolver.getType(it)) } }
    val contactLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        lifecycleScope.launch {
            contactSelectionHelper.resolveResult(result.data)?.let { selected ->
                viewModel.setRecipient(selected.normalizedAddress, selected.displayName)
            }
        }
    }

    LaunchedEffect(ui.snackbar) {
        val message = ui.snackbar ?: return@LaunchedEffect
        snackbarHost.showSnackbar(message)
        viewModel.consumeSnackbar()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            if (ui.screen in MAIN_SCREENS) {
                NavigationBar {
                    MainDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = ui.screen == destination.screen,
                            onClick = { viewModel.navigate(destination.screen) },
                            icon = { Text(destination.symbol) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(ui.screen.productComponentId)
                .padding(contentPadding),
        ) {
            when (ui.screen) {
                AppScreen.UPCOMING -> UpcomingScreen(
                    schedules = ui.upcoming,
                    globallyPaused = ui.settings.globallyPaused,
                    onToggleGlobalPause = viewModel::toggleGlobalPause,
                    onNewMessage = viewModel::newMessage,
                    onOpenSchedule = viewModel::openSchedule,
                    modifier = Modifier.fillMaxSize(),
                )

                AppScreen.HISTORY -> HistoryScreen(
                    items = ui.history.filter { item ->
                        when (ui.historyFilter) {
                            HistoryFilter.ALL -> true
                            HistoryFilter.SENT -> item.sendStatus in setOf("Sent to carrier", "Delivered")
                            HistoryFilter.FAILED -> item.warning
                            HistoryFilter.ACTION_NEEDED -> item.sendStatus == "Action needed"
                        }
                    },
                    filter = ui.historyFilter,
                    onFilter = viewModel::setHistoryFilter,
                    onOpen = viewModel::openOccurrence,
                )

                AppScreen.SETTINGS -> SettingsScreen(
                    state = ui.settings,
                    onRequestSmsPermission = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.SEND_SMS,
                                Manifest.permission.READ_PHONE_STATE,
                                Manifest.permission.READ_SMS,
                            ),
                        )
                    },
                    onRequestSmsHistoryPermission = {
                        permissionLauncher.launch(arrayOf(Manifest.permission.READ_SMS))
                    },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else viewModel.refresh()
                    },
                    onOpenExactAlarmSettings = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:$packageName"),
                                ),
                            )
                        }
                    },
                    onNotifications = { value ->
                        viewModel.updateSettings { it.copy(notificationsEnabled = value) }
                    },
                    onResultNotifications = { value ->
                        viewModel.updateSettings { it.copy(sendResultNotificationsEnabled = value) }
                    },
                    onActionRequiredNotifications = { value ->
                        viewModel.updateSettings { it.copy(actionRequiredNotificationsEnabled = value) }
                    },
                    onDeliveryNotifications = { value ->
                        viewModel.updateSettings { it.copy(deliveryNotificationsEnabled = value) }
                    },
                    onGlobalPause = { value ->
                        viewModel.updateSettings { it.copy(globalPaused = value) }
                    },
                    onOpenAppNotificationSettings = {
                        startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                        )
                    },
                )

                AppScreen.COMPOSER -> ComposerScreen(
                    state = ui.composer,
                    onRecipientChange = viewModel::setRecipient,
                    onMessageChange = viewModel::setMessage,
                    onPickContact = {
                        contactLauncher.launch(contactSelectionHelper.createPhonePickerRequest().intent)
                    },
                    onPickPhoto = {
                        photoLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onRichContent = viewModel::importRichContent,
                    onRemoveAttachment = viewModel::removeAttachment,
                    onSendNow = viewModel::sendNow,
                    onSchedule = viewModel::openScheduleEditor,
                )

                AppScreen.SCHEDULE_EDITOR -> ScheduleEditorScreen(
                    state = ui.editor,
                    onFrequency = { value -> viewModel.updateEditor { it.copy(frequency = value) } },
                    onPickDate = {
                        DatePickerDialog(
                            this@LaterTextApp,
                            { _, year, month, day ->
                                viewModel.setPickedDate(LocalDate.of(year, month + 1, day))
                            },
                            ui.editor.date.year,
                            ui.editor.date.monthValue - 1,
                            ui.editor.date.dayOfMonth,
                        ).show()
                    },
                    onPickTime = {
                        TimePickerDialog(
                            this@LaterTextApp,
                            { _, hour, minute -> viewModel.setPickedTime(LocalTime.of(hour, minute)) },
                            ui.editor.time.hour,
                            ui.editor.time.minute,
                            android.text.format.DateFormat.is24HourFormat(this@LaterTextApp),
                        ).show()
                    },
                    onToggleWeekDay = { day: DayOfWeek ->
                        viewModel.updateEditor { state ->
                            val next = state.weekDays.toMutableSet().apply {
                                if (!add(day)) remove(day)
                            }
                            state.copy(weekDays = next.ifEmpty { setOf(day) })
                        }
                    },
                    onMonthlyPolicy = { value ->
                        viewModel.updateEditor { it.copy(monthlyDayPolicy = value) }
                    },
                    onJitter = { value -> viewModel.updateEditor { it.copy(jitterMinutes = value) } },
                    onEndCount = { value ->
                        viewModel.updateEditor { it.copy(endCountText = value.filter(Char::isDigit)) }
                    },
                    onMissedPolicy = { value: MissedPolicy ->
                        viewModel.updateEditor { it.copy(missedPolicy = value) }
                    },
                    onSave = { viewModel.saveSchedule() },
                    onCancel = { viewModel.navigate(AppScreen.COMPOSER) },
                )

                AppScreen.DETAIL -> ui.selectedSchedule?.let { selected ->
                    ScheduleDetailScreen(
                        schedule = selected,
                        onBack = { viewModel.navigate(AppScreen.UPCOMING) },
                        onPauseResume = viewModel::toggleSelectedPause,
                        onSendNow = viewModel::openSelectedNow,
                        onOpenConversation = {
                            openConversation(selected.recipientAddress ?: selected.recipient)
                        },
                        onDelete = viewModel::deleteSelected,
                    )
                }
            }
            if (ui.loading) LinearProgressIndicator(modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}

private enum class MainDestination(val screen: AppScreen, val symbol: String, val label: String) {
    UPCOMING(AppScreen.UPCOMING, "◷", "Upcoming"),
    HISTORY(AppScreen.HISTORY, "↺", "History"),
    SETTINGS(AppScreen.SETTINGS, "⚙", "Settings"),
}

private val MAIN_SCREENS = MainDestination.entries.map(MainDestination::screen).toSet()
