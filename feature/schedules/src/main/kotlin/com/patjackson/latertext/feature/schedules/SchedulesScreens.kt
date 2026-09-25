package com.patjackson.latertext.feature.schedules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.patjackson.latertext.core.designsystem.AdaptiveActionRow
import com.patjackson.latertext.core.designsystem.MessageMediaPreview
import com.patjackson.latertext.core.model.MissedPolicy
import com.patjackson.latertext.core.model.MonthlyDayPolicy
import com.patjackson.latertext.core.model.RecurrenceFrequency
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle

data class UpcomingScheduleUi(
    val id: String,
    val recipient: String,
    val messagePreview: String,
    val timing: String,
    val status: String,
    val paused: Boolean = false,
    val actionRequired: Boolean = false,
    val assistedMedia: Boolean = false,
    val outcomeUnverified: Boolean = false,
    val occurrenceId: String? = null,
    val attachmentPath: String? = null,
    val attachmentMimeType: String? = null,
    val recipientAddress: String? = null,
    val deliveryStatus: String? = null,
    val canSendNow: Boolean = true,
    val attachmentWidthPixels: Int? = null,
    val attachmentHeightPixels: Int? = null,
    val attachmentIsAnimated: Boolean = false,
)

@Composable
fun UpcomingScreen(
    schedules: List<UpcomingScheduleUi>,
    globallyPaused: Boolean,
    onToggleGlobalPause: () -> Unit,
    onNewMessage: () -> Unit,
    onOpenSchedule: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        floatingActionButton = { Button(onClick = onNewMessage) { Text("New message") } },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                top = 20.dp,
                end = 20.dp,
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column {
                        Text("Upcoming", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            if (schedules.isEmpty()) "No messages scheduled" else
                                "${schedules.size} scheduled item${if (schedules.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    OutlinedButton(
                        onClick = onToggleGlobalPause,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (globallyPaused) "Resume all" else "Pause all")
                    }
                }
            }
            if (globallyPaused) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Nothing sends until you resume. Overdue occurrences are skipped instead of bursting later.",
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            items(schedules, key = { it.id }) { schedule ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onOpenSchedule(schedule.id) },
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(schedule.recipient, fontWeight = FontWeight.SemiBold)
                        Text(
                            schedule.status,
                            modifier = Modifier.clearAndSetSemantics {
                                contentDescription = "Schedule status: ${schedule.status}"
                            },
                            color = if (schedule.actionRequired) {
                                MaterialTheme.colorScheme.error
                            } else MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        schedule.attachmentPath?.let { path ->
                            MessageMediaPreview(
                                absolutePath = path,
                                mimeType = schedule.attachmentMimeType,
                                isAnimated = schedule.attachmentIsAnimated,
                                widthPixels = schedule.attachmentWidthPixels,
                                heightPixels = schedule.attachmentHeightPixels,
                                maximumHeight = 260.dp,
                            )
                        }
                        if (schedule.attachmentPath == null || schedule.messagePreview != "Media message") {
                            Text(
                                schedule.messagePreview,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(schedule.timing, style = MaterialTheme.typography.bodySmall)
                        if (schedule.outcomeUnverified) {
                            Text(
                                "Shared to a messaging app · outcome not verified",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

data class ScheduleEditorUiState(
    val frequency: RecurrenceFrequency = RecurrenceFrequency.ONCE,
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime = LocalTime.now().plusMinutes(5).withSecond(0).withNano(0),
    val weekDays: Set<DayOfWeek> = setOf(LocalDate.now().dayOfWeek),
    val monthlyDayPolicy: MonthlyDayPolicy = MonthlyDayPolicy.LAST_DAY_OF_MONTH,
    val jitterMinutes: Int = 0,
    val endCountText: String = "",
    val missedPolicy: MissedPolicy = MissedPolicy.SEND_AS_SOON_AS_POSSIBLE,
    val exactTimingAvailable: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val maximumJitterMinutes: Int = 60,
    val actionNotificationsAvailable: Boolean = true,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditorScreen(
    state: ScheduleEditorUiState,
    onFrequency: (RecurrenceFrequency) -> Unit,
    onPickDate: () -> Unit,
    onPickTime: () -> Unit,
    onToggleWeekDay: (DayOfWeek) -> Unit,
    onMonthlyPolicy: (MonthlyDayPolicy) -> Unit,
    onJitter: (Int) -> Unit,
    onEndCount: (String) -> Unit,
    onMissedPolicy: (MissedPolicy) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Schedule", style = MaterialTheme.typography.headlineSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RecurrenceFrequency.entries.forEach { frequency ->
                    FilterChip(
                        selected = state.frequency == frequency,
                        onClick = { onFrequency(frequency) },
                        label = { Text(frequency.name.lowercase().replaceFirstChar(Char::uppercase)) },
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onPickDate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Choose date, ${state.date}" },
                ) { Text("Date · ${state.date}") }
                OutlinedButton(
                    onClick = onPickTime,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Choose time, ${state.time}" },
                ) { Text("Time · ${state.time}") }
            }
            Text(
                if (state.exactTimingAvailable) "Exact timing is available" else
                    "Android-delayed timing · this may send later than the selected time",
                color = if (state.exactTimingAvailable) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodyMedium,
            )

            if (state.frequency == RecurrenceFrequency.WEEKLY) {
                Text("Days", style = MaterialTheme.typography.titleMedium)
                val locale = LocalLocale.current.platformLocale
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    DayOfWeek.entries.forEach { day ->
                        val fullDay = day.getDisplayName(TextStyle.FULL, locale)
                        FilterChip(
                            selected = day in state.weekDays,
                            onClick = { onToggleWeekDay(day) },
                            modifier = Modifier.semantics { contentDescription = fullDay },
                            label = {
                                Text(day.getDisplayName(TextStyle.SHORT, locale))
                            },
                        )
                    }
                }
            }
            if (state.frequency == RecurrenceFrequency.MONTHLY) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Use last day in short months")
                        Text(
                            "Otherwise months without day ${state.date.dayOfMonth} are skipped",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = state.monthlyDayPolicy == MonthlyDayPolicy.LAST_DAY_OF_MONTH,
                        onCheckedChange = {
                            onMonthlyPolicy(
                                if (it) MonthlyDayPolicy.LAST_DAY_OF_MONTH else
                                    MonthlyDayPolicy.SKIP_MONTH,
                            )
                        },
                    )
                }
            }

            Text("Timing variation: ±${state.jitterMinutes} minutes")
            Slider(
                value = state.jitterMinutes.coerceIn(0, state.maximumJitterMinutes).toFloat(),
                onValueChange = { onJitter(it.toInt()) },
                valueRange = 0f..state.maximumJitterMinutes.coerceAtLeast(1).toFloat(),
                steps = (state.maximumJitterMinutes - 1).coerceAtLeast(0),
                enabled = state.maximumJitterMinutes > 0,
                modifier = Modifier.semantics {
                    stateDescription = "Plus or minus ${state.jitterMinutes} minutes"
                },
            )

            if (state.frequency != RecurrenceFrequency.ONCE) {
                OutlinedTextField(
                    value = state.endCountText,
                    onValueChange = onEndCount,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Stop after occurrences (optional)") },
                    supportingText = { Text("Leave empty to repeat until you stop it") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            Text("If Android misses the time", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MissedPolicy.entries.forEach { policy ->
                    val enabled = policy != MissedPolicy.ASK_ME || state.actionNotificationsAvailable
                    FilterChip(
                        selected = state.missedPolicy == policy,
                        onClick = { onMissedPolicy(policy) },
                        enabled = enabled,
                        label = { Text(missedPolicyLabel(policy)) },
                    )
                }
            }
            if (!state.actionNotificationsAvailable) {
                Text(
                    "Enable action-needed notifications to use Ask me.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        AdaptiveActionRow(
            modifier = Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            secondary = {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cancel") }
            },
            primary = {
                Button(
                    onClick = onSave,
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.saving) "Saving…" else "Save schedule") }
            },
        )
    }
}

@Composable
fun ScheduleDetailScreen(
    schedule: UpcomingScheduleUi,
    onBack: () -> Unit,
    onPauseResume: () -> Unit,
    onSendNow: () -> Unit,
    onOpenConversation: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedButton(onClick = onBack) { Text("Back") }
        Text(schedule.recipient, style = MaterialTheme.typography.headlineSmall)
        schedule.attachmentPath?.let { path ->
            MessageMediaPreview(
                absolutePath = path,
                mimeType = schedule.attachmentMimeType,
                isAnimated = schedule.attachmentIsAnimated,
                widthPixels = schedule.attachmentWidthPixels,
                heightPixels = schedule.attachmentHeightPixels,
                maximumHeight = 520.dp,
            )
        }
        if (schedule.attachmentPath == null || schedule.messagePreview != "Media message") {
            Text(schedule.messagePreview, style = MaterialTheme.typography.bodyLarge)
        }
        Text(schedule.timing)
        Text(
            schedule.status,
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = "Schedule status: ${schedule.status}"
            },
        )
        schedule.deliveryStatus?.let { delivery ->
            Text(
                "Delivery: $delivery",
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "Delivery status: $delivery"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (schedule.assistedMedia || schedule.outcomeUnverified) {
            Text(
                "LaterText can prepare and share this media, but cannot verify that it was sent or delivered.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (schedule.canSendNow) {
            Button(onClick = onSendNow, modifier = Modifier.fillMaxWidth()) {
                Text(if (schedule.assistedMedia) "Review & share now" else "Send now")
            }
        }
        OutlinedButton(onClick = onPauseResume, modifier = Modifier.fillMaxWidth()) {
            Text(if (schedule.paused) "Resume schedule" else "Pause schedule")
        }
        OutlinedButton(onClick = onOpenConversation, modifier = Modifier.fillMaxWidth()) {
            Text("Open recipient in messaging app")
        }
        OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
            Text("Delete schedule")
        }
    }
}

private fun missedPolicyLabel(policy: MissedPolicy): String = when (policy) {
    MissedPolicy.SEND_AS_SOON_AS_POSSIBLE -> "Send as soon as possible"
    MissedPolicy.ASK_ME -> "Ask me"
    MissedPolicy.SKIP -> "Skip"
}
