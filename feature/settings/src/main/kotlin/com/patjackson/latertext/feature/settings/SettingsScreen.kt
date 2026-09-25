package com.patjackson.latertext.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

data class SettingsUiState(
    val smsPermission: Boolean = false,
    val notificationsPermission: Boolean = false,
    val exactAlarmAccess: Boolean = false,
    val activeSim: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val resultNotifications: Boolean = true,
    val actionRequiredNotifications: Boolean = true,
    val deliveryNotifications: Boolean = false,
    val globallyPaused: Boolean = false,
    val simLabel: String = "System default",
    val smsHistoryPermission: Boolean = false,
)

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onRequestSmsPermission: () -> Unit,
    onRequestSmsHistoryPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onNotifications: (Boolean) -> Unit,
    onResultNotifications: (Boolean) -> Unit,
    onActionRequiredNotifications: (Boolean) -> Unit,
    onDeliveryNotifications: (Boolean) -> Unit,
    onGlobalPause: (Boolean) -> Unit,
    onOpenAppNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("Readiness", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ReadinessRow("SMS permission", state.smsPermission)
                if (!state.smsPermission) Button(onClick = onRequestSmsPermission) { Text("Allow SMS") }
                ReadinessRow(
                    "SMS history verification",
                    state.smsHistoryPermission,
                    required = false,
                    unavailableStatus = "Optional · enables recovery when Android callbacks are lost",
                )
                if (!state.smsHistoryPermission) {
                    OutlinedButton(onClick = onRequestSmsHistoryPermission) {
                        Text("Allow SMS verification")
                    }
                }
                ReadinessRow("Notifications", state.notificationsPermission)
                if (!state.notificationsPermission) {
                    Button(onClick = onRequestNotificationPermission) { Text("Allow notifications") }
                }
                ReadinessRow("Exact timing", state.exactAlarmAccess, required = false)
                if (!state.exactAlarmAccess) {
                    OutlinedButton(onClick = onOpenExactAlarmSettings) { Text("Alarms & reminders") }
                }
                ReadinessRow("Active SIM", state.activeSim)
            }
        }

        Text("Notifications", style = MaterialTheme.typography.titleMedium)
        SettingSwitch("LaterText notifications", state.notificationsEnabled, onNotifications)
        val notificationOptionsEnabled = state.notificationsPermission && state.notificationsEnabled
        SettingSwitch(
            "Send results",
            state.resultNotifications,
            onResultNotifications,
            enabled = notificationOptionsEnabled,
        )
        SettingSwitch(
            "Action-needed reminders",
            state.actionRequiredNotifications,
            onActionRequiredNotifications,
            enabled = notificationOptionsEnabled,
            description = "Required for Ask me and scheduled media review",
        )
        SettingSwitch(
            "Delivery receipts",
            state.deliveryNotifications,
            onDeliveryNotifications,
            enabled = notificationOptionsEnabled,
            description = "Carrier best effort; a receipt may never arrive",
        )
        OutlinedButton(onClick = onOpenAppNotificationSettings) { Text("Android notification settings") }

        Text("Sending", style = MaterialTheme.typography.titleMedium)
        Text("SIM: ${state.simLabel}")
        SettingSwitch("Pause all schedules", state.globallyPaused, onGlobalPause)

        Text("Privacy", style = MaterialTheme.typography.titleMedium)
        Text(
            "Messages, recipients, and attachments stay on this device and are excluded from cloud backup. " +
                "When allowed, LaterText checks only matching outgoing SMS records to recover send and delivery status.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("LaterText 0.1.0-dev", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ReadinessRow(
    label: String,
    ready: Boolean,
    required: Boolean = true,
    unavailableStatus: String? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label)
        val status = when {
            ready -> "Ready"
            required -> "Needs attention"
            else -> unavailableStatus ?: "Optional · Android may delay scheduled sends"
        }
        Text(
            status,
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = "$label: $status"
            },
            color = if (ready) MaterialTheme.colorScheme.primary else if (required) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.tertiary
            },
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    enabled: Boolean = true,
    description: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onChecked,
            )
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label)
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}
