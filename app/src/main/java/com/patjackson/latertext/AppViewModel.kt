package com.patjackson.latertext

import android.content.Context
import android.net.Uri
import android.telephony.PhoneNumberUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.patjackson.latertext.core.domain.recurrence.DefaultJitterRandom
import com.patjackson.latertext.core.domain.recurrence.OccurrenceMaterializer
import com.patjackson.latertext.core.model.MaterializedOccurrence
import com.patjackson.latertext.core.model.RecurrenceEnd
import com.patjackson.latertext.core.model.RecurrenceRule
import com.patjackson.latertext.core.model.RuleRevisionId
import com.patjackson.latertext.core.model.ScheduleId
import com.patjackson.latertext.core.model.ZonePolicy
import com.patjackson.latertext.data.api.AttachmentAssetRecord
import com.patjackson.latertext.data.api.AttachmentInput
import com.patjackson.latertext.data.api.AttachmentIntakeSource
import com.patjackson.latertext.data.api.AttachmentRepository
import com.patjackson.latertext.data.api.AttachmentStorageClass
import com.patjackson.latertext.data.api.AttachmentWriteRequest
import com.patjackson.latertext.data.api.ContentAttachmentRecord
import com.patjackson.latertext.data.api.ContentRevisionRecord
import com.patjackson.latertext.data.api.ComposerDraftRecord
import com.patjackson.latertext.data.api.CreateScheduleCommand
import com.patjackson.latertext.data.api.DraftAttachmentRecord
import com.patjackson.latertext.data.api.DraftRepository
import com.patjackson.latertext.data.api.DraftState
import com.patjackson.latertext.data.api.DeliveryOutcome
import com.patjackson.latertext.data.api.EndCondition
import com.patjackson.latertext.data.api.MissedPolicy as DataMissedPolicy
import com.patjackson.latertext.data.api.MonthlyEdgePolicy
import com.patjackson.latertext.data.api.OccurrenceRecord
import com.patjackson.latertext.data.api.OccurrenceRepository
import com.patjackson.latertext.data.api.OccurrenceState
import com.patjackson.latertext.data.api.RecipientEndpointRecord
import com.patjackson.latertext.data.api.RecipientRepository
import com.patjackson.latertext.data.api.RecentRecipientRecord
import com.patjackson.latertext.data.api.RecipientSource
import com.patjackson.latertext.data.api.RuleRevisionRecord
import com.patjackson.latertext.data.api.ScheduleGraph
import com.patjackson.latertext.data.api.ScheduleRecord
import com.patjackson.latertext.data.api.ScheduleRepository
import com.patjackson.latertext.data.api.ScheduleState
import com.patjackson.latertext.data.api.SendOutcome
import com.patjackson.latertext.data.api.SettingsRepository
import com.patjackson.latertext.data.api.TransportMode
import com.patjackson.latertext.data.api.UserSettings
import com.patjackson.latertext.data.api.ZonePolicy as DataZonePolicy
import com.patjackson.latertext.feature.composer.ComposerUiState
import com.patjackson.latertext.feature.composer.RichContentSelection
import com.patjackson.latertext.feature.composer.RichContentSource
import com.patjackson.latertext.feature.history.HistoryFilter
import com.patjackson.latertext.feature.history.HistoryItemUi
import com.patjackson.latertext.feature.schedules.ScheduleEditorUiState
import com.patjackson.latertext.feature.schedules.UpcomingScheduleUi
import com.patjackson.latertext.feature.settings.SettingsUiState
import com.patjackson.latertext.platform.android.execution.AlarmCoordinator
import com.patjackson.latertext.platform.android.execution.ReconciliationCause
import com.patjackson.latertext.platform.android.sharing.RecipientShareShortcut
import com.patjackson.latertext.platform.android.sharing.RecipientShareShortcutPublisher
import com.patjackson.latertext.platform.api.AutomaticSmsGateway
import com.patjackson.latertext.platform.api.AssistedHandoffKind
import com.patjackson.latertext.platform.api.AssistedHandoffRequest
import com.patjackson.latertext.platform.api.AssistedMessagingGateway
import com.patjackson.latertext.platform.api.ReadinessGateway
import com.patjackson.latertext.platform.api.SubscriptionGateway
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AppScreen { UPCOMING, HISTORY, SETTINGS, COMPOSER, SCHEDULE_EDITOR, DETAIL }

data class LaterTextUiState(
    val screen: AppScreen = AppScreen.UPCOMING,
    val loading: Boolean = true,
    val composer: ComposerUiState = ComposerUiState(),
    val editor: ScheduleEditorUiState = ScheduleEditorUiState(),
    val upcoming: List<UpcomingScheduleUi> = emptyList(),
    val history: List<HistoryItemUi> = emptyList(),
    val historyFilter: HistoryFilter = HistoryFilter.ALL,
    val settings: SettingsUiState = SettingsUiState(),
    val selectedSchedule: UpcomingScheduleUi? = null,
    val snackbar: String? = null,
)

@HiltViewModel
class AppViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val schedules: ScheduleRepository,
    private val settingsRepository: SettingsRepository,
    private val recipients: RecipientRepository,
    private val drafts: DraftRepository,
    private val occurrences: OccurrenceRepository,
    private val attachments: AttachmentRepository,
    private val alarmCoordinator: AlarmCoordinator,
    private val readinessGateway: ReadinessGateway,
    private val subscriptions: SubscriptionGateway,
    private val smsGateway: AutomaticSmsGateway,
    private val assistedMessaging: AssistedMessagingGateway,
    private val shareShortcuts: RecipientShareShortcutPublisher,
) : ViewModel() {
    private val materializer = OccurrenceMaterializer(DefaultJitterRandom())
    private val _uiState = MutableStateFlow(LaterTextUiState())
    val uiState: StateFlow<LaterTextUiState> = _uiState.asStateFlow()
    private var stagedAttachment: AttachmentAssetRecord? = null
    private var selectedScheduleId: String? = null
    private var selectedOccurrenceId: String? = null
    private var openSelectionWhenLoaded: Boolean = false
    private var draftCreatedAtEpochMillis = System.currentTimeMillis()
    private var draftSaveJob: Job? = null
    private val refreshMutex = Mutex()

    init {
        viewModelScope.launch {
            schedules.observeChanges().conflate().collect {
                loadLatest(showLoading = _uiState.value.loading)
            }
        }
        restoreDraft()
    }

    fun refresh() {
        viewModelScope.launch { loadLatest(showLoading = true) }
    }

    private suspend fun loadLatest(showLoading: Boolean) = refreshMutex.withLock {
        if (showLoading) _uiState.update { it.copy(loading = true) }
        runCatching {
            val userSettings = settingsRepository.get()
            val graphs = schedules.listAll(250)
            val readiness = readinessGateway.snapshot()
            val activeSubscriptions = subscriptions.activeSubscriptions()
            val subscriptionLabel = activeSubscriptions
                .firstOrNull { it.subscriptionId == userSettings.preferredSubscriptionId }
                ?.displayName
                ?: activeSubscriptions.firstOrNull { it.isDefaultForSms }?.displayName
                ?: "System default"
            Triple(userSettings, graphs, readiness to subscriptionLabel)
        }.onSuccess { (userSettings, graphs, readinessAndLabel) ->
            val (readiness, label) = readinessAndLabel
            val upcoming = graphs.mapNotNull(::toUpcoming)
            val selected = selectedOccurrenceId?.let { occurrenceId ->
                graphs.firstNotNullOfOrNull { graph ->
                    graph.occurrences.firstOrNull { it.id == occurrenceId }
                        ?.let { toScheduleUi(graph, it) }
                }
            } ?: selectedScheduleId?.let { scheduleId ->
                graphs.firstOrNull { it.schedule.id == scheduleId }?.let(::toUpcoming)
            }
            val shouldOpenSelection = openSelectionWhenLoaded && selected != null
            if (shouldOpenSelection) openSelectionWhenLoaded = false
            _uiState.update {
                it.copy(
                    loading = false,
                    upcoming = upcoming,
                    history = graphs.flatMap(::toHistory)
                        .sortedByDescending(HistoryItemUi::sortEpochMillis),
                    settings = userSettings.toUi(readiness.canSendSms, readiness.canPostNotifications,
                        readiness.canScheduleExactAlarms, readiness.hasActiveSmsSubscription,
                        readiness.canReadSmsHistory, label),
                    editor = it.editor.copy(
                        exactTimingAvailable = readiness.canScheduleExactAlarms,
                        actionNotificationsAvailable = readiness.canPostNotifications &&
                            userSettings.notificationsEnabled &&
                            userSettings.actionRequiredNotificationsEnabled,
                    ),
                    selectedSchedule = selected,
                    screen = when {
                        shouldOpenSelection -> AppScreen.DETAIL
                        it.screen == AppScreen.DETAIL && selected == null -> AppScreen.UPCOMING
                        else -> it.screen
                    },
                )
            }
        }.onFailure { error ->
            _uiState.update {
                it.copy(loading = false, snackbar = error.message ?: "Unable to load LaterText")
            }
        }
    }

    fun navigate(screen: AppScreen) {
        _uiState.update { it.copy(screen = screen, snackbar = null) }
        if (screen in setOf(AppScreen.UPCOMING, AppScreen.HISTORY, AppScreen.SETTINGS)) refresh()
    }

    fun newMessage() {
        _uiState.update {
            val existing = it.composer.takeIf { composer ->
                composer.recipient.isNotBlank() || composer.message.isNotBlank() ||
                    composer.attachmentLabel != null
            } ?: ComposerUiState()
            it.copy(
                screen = AppScreen.COMPOSER,
                composer = existing,
                editor = ScheduleEditorUiState(
                    date = LocalDate.now(),
                    time = LocalTime.now().plusMinutes(5).withSecond(0).withNano(0),
                    exactTimingAvailable = it.settings.exactAlarmAccess,
                    actionNotificationsAvailable = it.settings.notificationsEnabled &&
                        it.settings.notificationsPermission &&
                        it.settings.actionRequiredNotifications,
                ),
                selectedSchedule = null,
            )
        }
        stagedAttachment = null
        selectedScheduleId = null
        selectedOccurrenceId = null
    }

    fun setRecipient(value: String, displayName: String? = null) {
        _uiState.update {
            it.copy(composer = it.composer.copy(recipient = value, recipientName = displayName, error = null))
        }
        persistDraftDebounced()
    }

    fun setRecipientById(recipientId: String) {
        viewModelScope.launch {
            recipients.get(recipientId)?.let {
                setRecipient(it.rawAddress, it.displayName)
                shareShortcuts.reportUsed(recipientId)
            }
        }
    }

    fun setMessage(value: String) {
        val parts = runCatching {
            if (value.isBlank()) 1 else smsGateway.divideMessage(value, preferredSubscription()).size.coerceAtLeast(1)
        }.getOrDefault(1)
        _uiState.update {
            it.copy(composer = it.composer.copy(message = value, smsPartCount = parts, error = null))
        }
        persistDraftDebounced()
    }

    fun importRichContent(selection: RichContentSelection) {
        importAttachment(
            selection.uri,
            selection.declaredMimeType,
            when (selection.source) {
                RichContentSource.KEYBOARD -> AttachmentIntakeSource.KEYBOARD
                RichContentSource.CLIPBOARD -> AttachmentIntakeSource.CLIPBOARD
                RichContentSource.DRAG_DROP -> AttachmentIntakeSource.DRAG_DROP
            },
        )
    }

    fun importPhoto(uri: Uri, declaredMimeType: String?) {
        importAttachment(uri, declaredMimeType, AttachmentIntakeSource.PHOTO_PICKER)
    }

    fun importShare(uri: Uri, declaredMimeType: String?, text: String?) {
        text?.takeIf(String::isNotBlank)?.let(::setMessage)
        importAttachment(uri, declaredMimeType, AttachmentIntakeSource.SHARE)
    }

    private fun importAttachment(uri: Uri, mimeType: String?, source: AttachmentIntakeSource) {
        if (stagedAttachment != null) {
            _uiState.update {
                it.copy(composer = it.composer.copy(error = "Remove the current attachment first"))
            }
            return
        }
        _uiState.update { it.copy(composer = it.composer.copy(importInProgress = true, error = null)) }
        viewModelScope.launch {
            persistDraftNow(DraftState.IMPORTING_ATTACHMENT)
            runCatching {
                val now = System.currentTimeMillis()
                attachments.stage(
                    AttachmentWriteRequest(
                        id = UUID.randomUUID().toString(),
                        input = AttachmentInput {
                            context.contentResolver.openInputStream(uri)
                                ?: error("The selected content is no longer readable")
                        },
                        declaredMimeType = mimeType ?: context.contentResolver.getType(uri),
                        intakeSource = source,
                        createdAtEpochMillis = now,
                        stagingExpiresAtEpochMillis = now + Duration.ofDays(7).toMillis(),
                        maxBytes = 25L * 1024L * 1024L,
                    ),
                )
            }.onSuccess { asset ->
                stagedAttachment = asset
                drafts.attach(
                    DraftAttachmentRecord(
                        draftId = ACTIVE_DRAFT_ID,
                        attachmentAssetId = asset.id,
                        createdAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
                _uiState.update {
                    it.copy(
                        composer = it.composer.copy(
                            attachmentLabel = "${asset.mimeType.substringAfter('/').uppercase()} · ${formatBytes(asset.byteCount)}",
                            attachmentMimeType = asset.mimeType,
                            attachmentPath = asset.absolutePrivatePath(),
                            attachmentWidthPixels = asset.widthPixels,
                            attachmentHeightPixels = asset.heightPixels,
                            attachmentIsAnimated = asset.isAnimated,
                            importInProgress = false,
                            error = null,
                        ),
                    )
                }
                persistDraftNow(DraftState.READY)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        composer = it.composer.copy(
                            importInProgress = false,
                            error = error.message ?: "Could not add that image or GIF",
                        ),
                    )
                }
                persistDraftNow(DraftState.FAILED)
            }
        }
    }

    fun removeAttachment() {
        val asset = stagedAttachment
        stagedAttachment = null
        _uiState.update {
            it.copy(
                composer = it.composer.copy(
                    attachmentLabel = null,
                    attachmentMimeType = null,
                    attachmentPath = null,
                    attachmentWidthPixels = null,
                    attachmentHeightPixels = null,
                    attachmentIsAnimated = false,
                ),
            )
        }
        if (asset != null) viewModelScope.launch {
            drafts.detach(ACTIVE_DRAFT_ID)
            attachments.removeIfUnreferenced(asset.id)
            persistDraftNow(DraftState.EDITING)
        }
    }

    fun openScheduleEditor() {
        _uiState.update { it.copy(screen = AppScreen.SCHEDULE_EDITOR) }
    }

    fun updateEditor(transform: (ScheduleEditorUiState) -> ScheduleEditorUiState) {
        _uiState.update { it.copy(editor = transform(it.editor).copy(error = null)) }
    }

    fun sendNow() {
        val ready = readinessGateway.snapshot()
        if (stagedAttachment == null && !ready.canSendSms) {
            _uiState.update {
                it.copy(composer = it.composer.copy(error = "Allow SMS permission in Settings first"))
            }
            return
        }
        val target = Instant.now().plusSeconds(2).atZone(ZoneId.systemDefault())
        updateEditor { it.copy(date = target.toLocalDate(), time = target.toLocalTime()) }
        saveSchedule(successMessage = "Sending now…")
    }

    fun saveSchedule(successMessage: String = "Message scheduled") {
        val snapshot = _uiState.value
        if (!snapshot.composer.canSubmit) return
        _uiState.update { it.copy(editor = it.editor.copy(saving = true, error = null)) }
        viewModelScope.launch {
            runCatching { createSchedule(snapshot.composer, snapshot.editor, stagedAttachment) }
                .onSuccess {
                    stagedAttachment = null
                    drafts.delete(ACTIVE_DRAFT_ID)
                    draftCreatedAtEpochMillis = System.currentTimeMillis()
                    alarmCoordinator.reconcile(ReconciliationCause.OCCURRENCE_CHANGED)
                    _uiState.update {
                        it.copy(
                            screen = AppScreen.UPCOMING,
                            composer = ComposerUiState(),
                            editor = it.editor.copy(saving = false),
                            snackbar = successMessage,
                        )
                    }
                    refresh()
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(editor = it.editor.copy(saving = false, error = error.message ?: "Could not save schedule"))
                    }
                }
        }
    }

    private suspend fun createSchedule(
        composer: ComposerUiState,
        editor: ScheduleEditorUiState,
        attachment: AttachmentAssetRecord?,
    ): OccurrenceRecord = withContext(Dispatchers.Default) {
        val normalized = PhoneNumberUtils.normalizeNumber(composer.recipient)
        require(normalized.isNotBlank()) { "Enter a valid phone number" }
        val now = Instant.now()
        val scheduleId = UUID.randomUUID().toString()
        val recipientId = UUID.randomUUID().toString()
        val contentId = UUID.randomUUID().toString()
        val ruleId = UUID.randomUUID().toString()
        val endCount = editor.endCountText.toIntOrNull()
        require(editor.endCountText.isBlank() || (endCount != null && endCount > 0)) {
            "End count must be a positive number"
        }
        val coreRule = RecurrenceRule(
            frequency = editor.frequency,
            startDate = editor.date,
            localTime = editor.time,
            daysOfWeek = editor.weekDays.ifEmpty { setOf(editor.date.dayOfWeek) },
            dayOfMonth = editor.date.dayOfMonth,
            end = endCount?.let(RecurrenceEnd::AfterOccurrences) ?: if (
                editor.frequency == com.patjackson.latertext.core.model.RecurrenceFrequency.ONCE
            ) RecurrenceEnd.AfterOccurrences(1) else RecurrenceEnd.Never,
            zonePolicy = ZonePolicy.FOLLOW_DEVICE_ZONE,
            zoneId = ZoneId.systemDefault().id,
            monthlyDayPolicy = editor.monthlyDayPolicy,
            jitterRangeMinutes = editor.jitterMinutes,
        )
        val materialized = materializer.materialize(
            scheduleId = ScheduleId(scheduleId),
            ruleRevisionId = RuleRevisionId(ruleId),
            rule = coreRule,
            activeDeviceZone = ZoneId.systemDefault(),
            now = now,
            gracePeriod = Duration.ofHours(4),
        ).added
        require(materialized.isNotEmpty()) { "Choose a future date and time" }
        val transport = if (attachment == null) {
            TransportMode.AUTOMATIC_SMS
        } else {
            val userSettings = settingsRepository.get()
            val subscriptionId = resolveActiveSubscription(userSettings.preferredSubscriptionId)
            val supportsAutomaticMms = subscriptionId?.let { id ->
                runCatching { smsGateway.mmsCapability(id) }.getOrNull()?.let { capability ->
                    capability.acceptsText(composer.message.toByteArray(Charsets.UTF_8).size) &&
                        capability.canPrepare(
                            attachment.mimeType,
                            attachment.byteCount,
                            attachment.widthPixels,
                            attachment.heightPixels,
                            attachment.isAnimated,
                        )
                }
            } == true
            if (supportsAutomaticMms) TransportMode.AUTOMATIC_MMS else TransportMode.ASSISTED_MEDIA
        }
        val createdMillis = now.toEpochMilli()
        val command = CreateScheduleCommand(
            recipient = RecipientEndpointRecord(
                id = recipientId,
                rawAddress = composer.recipient,
                normalizedAddress = normalized,
                displayName = composer.recipientName,
                source = RecipientSource.MANUAL,
                contactLookupKey = null,
                createdAtEpochMillis = createdMillis,
                updatedAtEpochMillis = createdMillis,
            ),
            schedule = ScheduleRecord(
                id = scheduleId,
                recipientEndpointId = recipientId,
                activeContentRevisionId = contentId,
                activeRuleRevisionId = ruleId,
                state = ScheduleState.ACTIVE,
                transportMode = transport,
                createdAtEpochMillis = createdMillis,
                updatedAtEpochMillis = createdMillis,
            ),
            contentRevision = ContentRevisionRecord(
                id = contentId,
                scheduleId = scheduleId,
                revisionNumber = 1,
                text = composer.message,
                createdAtEpochMillis = createdMillis,
            ),
            contentAttachment = attachment?.let { ContentAttachmentRecord(contentId, it.id) },
            ruleRevision = coreRule.toRecord(ruleId, scheduleId, editor.missedPolicy, createdMillis, endCount),
            occurrences = materialized.map { it.toRecord(contentId, createdMillis) },
        )
        schedules.create(command)
        recipients.recordRecent(
            RecentRecipientRecord(
                normalizedAddress = normalized,
                rawAddress = composer.recipient,
                displayName = composer.recipientName,
                recipientEndpointId = recipientId,
                lastUsedAtEpochMillis = createdMillis,
                useCount = 1,
            ),
        )
        publishRecentShareShortcuts()
        command.occurrences.minBy(OccurrenceRecord::targetAtEpochMillis)
    }

    private suspend fun publishRecentShareShortcuts() {
        shareShortcuts.replace(
            recipients.listRecent(4).mapNotNull { recent ->
                val id = recent.recipientEndpointId ?: return@mapNotNull null
                RecipientShareShortcut(
                    recipientId = id,
                    displayName = recent.displayName ?: recent.rawAddress,
                )
            },
        )
    }

    private fun restoreDraft() {
        viewModelScope.launch {
            val draft = drafts.get(ACTIVE_DRAFT_ID) ?: return@launch
            draftCreatedAtEpochMillis = draft.createdAtEpochMillis
            val attachment = drafts.getAttachment(ACTIVE_DRAFT_ID)
            stagedAttachment = attachment
            val restored = ComposerUiState(
                recipient = draft.rawRecipientAddress.orEmpty(),
                message = draft.text,
                attachmentLabel = attachment?.let {
                    "${it.mimeType.substringAfter('/').uppercase()} · ${formatBytes(it.byteCount)}"
                },
                attachmentMimeType = attachment?.mimeType,
                attachmentPath = attachment?.absolutePrivatePath(),
                attachmentWidthPixels = attachment?.widthPixels,
                attachmentHeightPixels = attachment?.heightPixels,
                attachmentIsAnimated = attachment?.isAnimated == true,
                error = if (draft.state == DraftState.IMPORTING_ATTACHMENT && attachment == null) {
                    "The interrupted attachment import could not be restored"
                } else null,
            )
            if (
                restored.recipient.isNotBlank() || restored.message.isNotBlank() ||
                restored.attachmentLabel != null || restored.error != null
            ) {
                _uiState.update { it.copy(composer = restored, screen = AppScreen.COMPOSER) }
            }
        }
    }

    private fun persistDraftDebounced() {
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            delay(250)
            persistDraftNow(DraftState.EDITING)
        }
    }

    private suspend fun persistDraftNow(state: DraftState) {
        val composer = _uiState.value.composer
        drafts.save(
            ComposerDraftRecord(
                id = ACTIVE_DRAFT_ID,
                text = composer.message,
                recipientEndpointId = null,
                rawRecipientAddress = composer.recipient.takeIf(String::isNotBlank),
                source = "COMPOSER",
                state = state,
                createdAtEpochMillis = draftCreatedAtEpochMillis,
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    fun openSchedule(id: String) {
        val selected = _uiState.value.upcoming.firstOrNull { it.id == id } ?: return
        selectedScheduleId = selected.id
        selectedOccurrenceId = selected.occurrenceId
        _uiState.update { it.copy(selectedSchedule = selected, screen = AppScreen.DETAIL) }
    }

    fun openOccurrence(occurrenceId: String) {
        selectedOccurrenceId = occurrenceId
        selectedScheduleId = null
        val selected = _uiState.value.upcoming.firstOrNull { it.occurrenceId == occurrenceId }
        if (selected != null) {
            selectedScheduleId = selected.id
            _uiState.update { it.copy(selectedSchedule = selected, screen = AppScreen.DETAIL) }
        } else {
            openSelectionWhenLoaded = true
            refresh()
        }
    }

    fun openSelectedNow() {
        val selected = _uiState.value.selectedSchedule ?: return
        val address = selected.recipientAddress ?: return
        val request = AssistedHandoffRequest(
            kind = if (selected.assistedMedia) AssistedHandoffKind.MEDIA else AssistedHandoffKind.TEXT,
            recipientAddress = address,
            body = selected.messagePreview.takeUnless { it == "Media message" }.orEmpty(),
            attachmentPath = selected.attachmentPath,
            attachmentMimeType = selected.attachmentMimeType,
        )
        if (!assistedMessaging.open(request)) {
            _uiState.update { it.copy(snackbar = "No compatible messaging app was found") }
            return
        }
        val occurrenceId = selected.occurrenceId ?: return
        viewModelScope.launch {
            occurrences.compareAndSetState(
                occurrenceId = occurrenceId,
                expectedStates = setOf(
                    OccurrenceState.PLANNED,
                    OccurrenceState.ARMED,
                    OccurrenceState.READY_FOR_USER,
                    OccurrenceState.OPENED_IN_LATER_TEXT,
                ),
                newState = OccurrenceState.SHARED_TO_MESSAGING_APP,
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
            alarmCoordinator.reconcile(ReconciliationCause.OCCURRENCE_CHANGED)
            navigate(AppScreen.UPCOMING)
        }
    }

    fun toggleSelectedPause() {
        val selected = _uiState.value.selectedSchedule ?: return
        viewModelScope.launch {
            schedules.setPaused(selected.id, !selected.paused, System.currentTimeMillis())
            alarmCoordinator.reconcile(ReconciliationCause.OCCURRENCE_CHANGED)
            navigate(AppScreen.UPCOMING)
        }
    }

    fun deleteSelected() {
        val selected = _uiState.value.selectedSchedule ?: return
        viewModelScope.launch {
            schedules.softDelete(selected.id, System.currentTimeMillis())
            selectedScheduleId = null
            selectedOccurrenceId = null
            alarmCoordinator.reconcile(ReconciliationCause.OCCURRENCE_CHANGED)
            navigate(AppScreen.UPCOMING)
        }
    }

    fun toggleGlobalPause() = updateSettings { it.copy(globalPaused = !it.globalPaused) }

    fun updateSettings(transform: (UserSettings) -> UserSettings) {
        viewModelScope.launch {
            runCatching { settingsRepository.update(transform) }
                .onSuccess {
                    alarmCoordinator.reconcile(ReconciliationCause.OCCURRENCE_CHANGED)
                    refresh()
                }
                .onFailure { error -> _uiState.update { it.copy(snackbar = error.message) } }
        }
    }

    fun setHistoryFilter(filter: HistoryFilter) {
        _uiState.update { it.copy(historyFilter = filter) }
    }

    fun setPickedDate(date: LocalDate) = updateEditor { it.copy(date = date) }
    fun setPickedTime(time: LocalTime) = updateEditor { it.copy(time = time) }
    fun consumeSnackbar() = _uiState.update { it.copy(snackbar = null) }

    private fun preferredSubscription(): Int? = _uiState.value.settings.let {
        subscriptions.defaultSmsSubscriptionId()
    }

    private fun resolveActiveSubscription(preferredId: Int?): Int? {
        if (preferredId != null && subscriptions.isActive(preferredId)) return preferredId
        val defaultId = subscriptions.defaultSmsSubscriptionId()
        if (defaultId != null && subscriptions.isActive(defaultId)) return defaultId
        return subscriptions.activeSubscriptions().singleOrNull()?.subscriptionId
    }

    private fun toUpcoming(graph: ScheduleGraph): UpcomingScheduleUi? {
        val occurrence = graph.occurrences
            .filter { it.state !in DATA_TERMINAL_STATES }
            .minByOrNull(OccurrenceRecord::targetAtEpochMillis) ?: return null
        return toScheduleUi(graph, occurrence)
    }

    private fun toScheduleUi(graph: ScheduleGraph, occurrence: OccurrenceRecord): UpcomingScheduleUi {
        val attachment = graph.attachmentsByContentRevisionId[occurrence.contentRevisionId]
            ?: graph.activeAttachment.takeIf { graph.activeContent?.id == occurrence.contentRevisionId }
        val content = graph.contentRevisions.firstOrNull { it.id == occurrence.contentRevisionId }
            ?: graph.activeContent
        return UpcomingScheduleUi(
            id = graph.schedule.id,
            recipient = graph.recipient.displayName ?: graph.recipient.rawAddress,
            messagePreview = content?.text.orEmpty().ifBlank { "Media message" },
            timing = occurrence.describeTiming(),
            status = when {
                graph.schedule.state == ScheduleState.PAUSED -> "Paused"
                else -> occurrence.sendStatusDisplay()
            },
            paused = graph.schedule.state == ScheduleState.PAUSED,
            actionRequired = occurrence.state == OccurrenceState.READY_FOR_USER,
            assistedMedia = graph.schedule.transportMode == TransportMode.ASSISTED_MEDIA,
            outcomeUnverified = graph.schedule.transportMode !in AUTOMATIC_TRANSPORTS,
            occurrenceId = occurrence.id,
            attachmentPath = attachment?.absolutePrivatePath(),
            attachmentMimeType = attachment?.mimeType,
            recipientAddress = graph.recipient.normalizedAddress,
            deliveryStatus = occurrence.deliveryStatusDisplay(graph.schedule.transportMode),
            canSendNow = occurrence.state in USER_SENDABLE_STATES,
            attachmentWidthPixels = attachment?.widthPixels,
            attachmentHeightPixels = attachment?.heightPixels,
            attachmentIsAnimated = attachment?.isAnimated == true,
        )
    }

    private fun AttachmentAssetRecord.absolutePrivatePath(): String {
        val root = when (storageClass) {
            AttachmentStorageClass.STAGING -> File(context.filesDir, "latertext_attachments/staging")
            AttachmentStorageClass.DURABLE -> File(context.filesDir, "latertext_attachments/durable")
            AttachmentStorageClass.CACHE -> File(context.cacheDir, "latertext_attachments")
        }
        return File(root, relativePath).absolutePath
    }

    private fun toHistory(graph: ScheduleGraph): List<HistoryItemUi> = graph.occurrences
        .filter { it.state !in setOf(OccurrenceState.PLANNED, OccurrenceState.ARMED) }
        .map { occurrence ->
            val content = graph.contentRevisions.firstOrNull { it.id == occurrence.contentRevisionId }
                ?: graph.activeContent
            val attachment = graph.attachmentsByContentRevisionId[occurrence.contentRevisionId]
                ?: graph.activeAttachment.takeIf { graph.activeContent?.id == occurrence.contentRevisionId }
            HistoryItemUi(
                id = occurrence.id,
                recipient = graph.recipient.displayName ?: graph.recipient.rawAddress,
                preview = content?.text.orEmpty().ifBlank { "Media message" },
                happenedAt = Instant.ofEpochMilli(occurrence.updatedAtEpochMillis)
                    .atZone(ZoneId.systemDefault()).format(HISTORY_FORMAT),
                sendStatus = occurrence.sendStatusDisplay(),
                deliveryStatus = occurrence.deliveryStatusDisplay(graph.schedule.transportMode),
                warning = occurrence.state in setOf(
                    OccurrenceState.FAILED_TERMINAL,
                    OccurrenceState.PARTIAL_AMBIGUOUS,
                    OccurrenceState.EXPIRED,
                ),
                assistedOutcomeUnverified = graph.schedule.transportMode !in AUTOMATIC_TRANSPORTS,
                sortEpochMillis = occurrence.updatedAtEpochMillis,
                attachmentPath = attachment?.absolutePrivatePath(),
                attachmentMimeType = attachment?.mimeType,
                attachmentWidthPixels = attachment?.widthPixels,
                attachmentHeightPixels = attachment?.heightPixels,
                attachmentIsAnimated = attachment?.isAnimated == true,
            )
        }

    companion object {
        private const val ACTIVE_DRAFT_ID = "active_composer"
        private val HISTORY_FORMAT = DateTimeFormatter.ofPattern("MMM d, h:mm a")
        private val DATA_TERMINAL_STATES = setOf(
            OccurrenceState.SHARED_TO_MESSAGING_APP,
            OccurrenceState.DELIVERED,
            OccurrenceState.DELIVERY_FAILED,
            OccurrenceState.DELIVERY_UNAVAILABLE,
            OccurrenceState.FAILED_TERMINAL,
            OccurrenceState.PARTIAL_AMBIGUOUS,
            OccurrenceState.EXPIRED,
            OccurrenceState.MISSED,
            OccurrenceState.SKIPPED_PAUSED,
            OccurrenceState.SKIPPED_MISSED,
            OccurrenceState.CANCELLED,
        )
        private val USER_SENDABLE_STATES = setOf(
            OccurrenceState.PLANNED,
            OccurrenceState.ARMED,
            OccurrenceState.DUE,
            OccurrenceState.READY_FOR_USER,
            OccurrenceState.OPENED_IN_LATER_TEXT,
        )
        private val AUTOMATIC_TRANSPORTS = setOf(
            TransportMode.AUTOMATIC_SMS,
            TransportMode.AUTOMATIC_MMS,
        )
    }
}

private fun RecurrenceRule.toRecord(
    id: String,
    scheduleId: String,
    missedPolicy: com.patjackson.latertext.core.model.MissedPolicy,
    createdAt: Long,
    endCount: Int?,
) = RuleRevisionRecord(
    id = id,
    scheduleId = scheduleId,
    revisionNumber = 1,
    frequency = com.patjackson.latertext.data.api.RecurrenceFrequency.valueOf(frequency.name),
    interval = interval,
    startEpochDay = startDate.toEpochDay(),
    secondsOfDay = localTime.toSecondOfDay(),
    daysOfWeekMask = daysOfWeek.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) },
    monthlyDayOfMonth = dayOfMonth,
    monthlyEdgePolicy = MonthlyEdgePolicy.valueOf(monthlyDayPolicy.name),
    zonePolicy = DataZonePolicy.valueOf(zonePolicy.name),
    zoneId = zoneId,
    endCondition = if (endCount != null || frequency == com.patjackson.latertext.core.model.RecurrenceFrequency.ONCE) {
        EndCondition.AFTER_COUNT
    } else EndCondition.NEVER,
    endCount = endCount ?: if (frequency == com.patjackson.latertext.core.model.RecurrenceFrequency.ONCE) 1 else null,
    endEpochDay = null,
    jitterRangeMinutes = jitterRangeMinutes,
    missedPolicy = DataMissedPolicy.valueOf(missedPolicy.name),
    gracePeriodMinutes = 240,
    createdAtEpochMillis = createdAt,
)

private fun MaterializedOccurrence.toRecord(contentId: String, createdAt: Long) = OccurrenceRecord(
    id = UUID.randomUUID().toString(),
    scheduleId = key.scheduleId.value,
    ruleRevisionId = key.ruleRevisionId.value,
    contentRevisionId = contentId,
    logicalRecurrenceKey = "${key.scheduleId.value}:${key.ruleRevisionId.value}:${key.sequence}",
    nominalEpochDay = nominalLocalDateTime.toLocalDate().toEpochDay(),
    nominalSecondsOfDay = nominalLocalDateTime.toLocalTime().toSecondOfDay(),
    selectedZoneId = zoneId,
    selectedOffsetSeconds = selectedOffset.totalSeconds,
    dstResolution = com.patjackson.latertext.data.api.DstResolution.valueOf(dstResolution.name),
    jitterOffsetMinutes = jitterOffsetMinutes,
    targetAtEpochMillis = targetAt.toEpochMilli(),
    deadlineAtEpochMillis = deadlineAt.toEpochMilli(),
    state = OccurrenceState.PLANNED,
    createdAtEpochMillis = createdAt,
    updatedAtEpochMillis = createdAt,
)

private fun OccurrenceRecord.describeTiming(): String {
    val dateTime = Instant.ofEpochMilli(targetAtEpochMillis).atZone(ZoneId.systemDefault())
    val base = dateTime.format(DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a"))
    return if (jitterOffsetMinutes == 0) base else "$base · jitter ${if (jitterOffsetMinutes > 0) "+" else ""}$jitterOffsetMinutes min"
}

private fun OccurrenceState.displayName(): String = when (this) {
    OccurrenceState.PLANNED, OccurrenceState.ARMED -> "Scheduled"
    OccurrenceState.DUE, OccurrenceState.CLAIMED -> "Preparing to send"
    OccurrenceState.SENDING -> "Waiting for carrier confirmation"
    OccurrenceState.READY_FOR_USER -> "Action needed"
    OccurrenceState.OPENED_IN_LATER_TEXT -> "Opened in LaterText"
    OccurrenceState.SHARED_TO_MESSAGING_APP -> "Shared · unverified"
    OccurrenceState.SENT_TO_CARRIER -> "Sent to carrier"
    OccurrenceState.DELIVERED -> "Delivered"
    OccurrenceState.DELIVERY_FAILED -> "Delivery failed"
    OccurrenceState.DELIVERY_UNAVAILABLE -> "Delivery unavailable"
    OccurrenceState.FAILED_TERMINAL -> "Failed"
    OccurrenceState.PARTIAL_AMBIGUOUS -> "Status unknown · message may have sent"
    OccurrenceState.SKIPPED_PAUSED -> "Skipped while paused"
    OccurrenceState.SKIPPED_MISSED -> "Missed"
    OccurrenceState.MISSED -> "Missed"
    OccurrenceState.EXPIRED -> "Expired"
    OccurrenceState.CANCELLED -> "Cancelled"
    else -> name.lowercase().replace('_', ' ')
}

internal fun DeliveryOutcome.displayName(): String = when (this) {
    DeliveryOutcome.NOT_REQUESTED -> "Not requested yet"
    DeliveryOutcome.PENDING -> "Pending carrier report"
    DeliveryOutcome.DELIVERED -> "Delivered"
    DeliveryOutcome.FAILED -> "Failed"
    DeliveryOutcome.UNAVAILABLE -> "Unavailable"
}

private fun OccurrenceRecord.deliveryStatusDisplay(transportMode: TransportMode): String =
    if (transportMode == TransportMode.AUTOMATIC_MMS && sendOutcome == SendOutcome.SENT_TO_CARRIER) {
        "Not available for MMS"
    } else {
        deliveryOutcome.displayName()
    }

internal fun OccurrenceRecord.sendStatusDisplay(): String = when (sendOutcome) {
    SendOutcome.NOT_STARTED -> state.displayName()
    SendOutcome.PENDING -> when (state) {
        OccurrenceState.CLAIMED, OccurrenceState.DUE -> "Preparing to send"
        else -> "Waiting for carrier confirmation"
    }
    SendOutcome.SENT_TO_CARRIER -> "Sent to carrier"
    SendOutcome.FAILED -> "Failed"
    SendOutcome.PARTIAL_OR_AMBIGUOUS -> "Status unknown · message may have sent"
    SendOutcome.SKIPPED -> state.displayName()
    SendOutcome.USER_ACTION_REQUIRED -> "Action needed"
    SendOutcome.SHARED_UNVERIFIED -> "Shared · unverified"
}

private fun UserSettings.toUi(
    canSendSms: Boolean,
    canNotify: Boolean,
    canExact: Boolean,
    hasSim: Boolean,
    canReadSmsHistory: Boolean,
    simLabel: String,
) = SettingsUiState(
    smsPermission = canSendSms,
    notificationsPermission = canNotify,
    exactAlarmAccess = canExact,
    activeSim = hasSim,
    notificationsEnabled = notificationsEnabled,
    resultNotifications = sendResultNotificationsEnabled,
    actionRequiredNotifications = actionRequiredNotificationsEnabled,
    deliveryNotifications = deliveryNotificationsEnabled,
    globallyPaused = globalPaused,
    simLabel = simLabel,
    smsHistoryPermission = canReadSmsHistory,
)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
