package com.patjackson.latertext.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class HistoryFilter { ALL, SENT, FAILED, ACTION_NEEDED }

data class HistoryItemUi(
    val id: String,
    val recipient: String,
    val preview: String,
    val happenedAt: String,
    val sendStatus: String,
    val deliveryStatus: String,
    val warning: Boolean = false,
    val assistedOutcomeUnverified: Boolean = false,
    val sortEpochMillis: Long = 0,
)

@Composable
fun HistoryScreen(
    items: List<HistoryItemUi>,
    filter: HistoryFilter,
    onFilter: (HistoryFilter) -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("History", style = MaterialTheme.typography.headlineMedium) }
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                HistoryFilter.entries.forEach { option ->
                    FilterChip(
                        selected = option == filter,
                        onClick = { onFilter(option) },
                        label = { Text(option.name.lowercase().replace('_', ' ')) },
                    )
                }
            }
        }
        if (items.isEmpty()) {
            item {
                Column(modifier = Modifier.padding(vertical = 64.dp)) {
                    Text("No results yet", style = MaterialTheme.typography.titleLarge)
                    Text("Automatic sends and assisted handoffs will appear here.")
                }
            }
        }
        items(items, key = { it.id }) { item ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onOpen(item.id) },
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(item.recipient, fontWeight = FontWeight.SemiBold)
                    Text(
                        item.sendStatus,
                        modifier = Modifier.clearAndSetSemantics {
                            contentDescription = "Send status: ${item.sendStatus}"
                        },
                        color = if (item.warning) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(item.preview, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(item.happenedAt, style = MaterialTheme.typography.bodySmall)
                    if (item.assistedOutcomeUnverified) {
                        Text(
                            "Shared to a messaging app · send and delivery are not verified",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "Delivery: ${item.deliveryStatus}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}
