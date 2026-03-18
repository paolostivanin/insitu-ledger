package com.insituledger.app.ui.scheduled

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.domain.model.ScheduledTransaction
import com.insituledger.app.ui.common.AmountText
import com.insituledger.app.ui.common.EmptyState
import com.insituledger.app.ui.common.LoadingIndicator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledScreen(
    onAddClick: () -> Unit,
    onEditClick: (Long) -> Unit,
    viewModel: ScheduledViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Scheduled") }) },
        floatingActionButton = {
            if (!uiState.isReadOnly) {
                FloatingActionButton(onClick = onAddClick) {
                    Icon(Icons.Default.Add, contentDescription = "Add Scheduled")
                }
            }
        }
    ) { padding ->
        when {
            uiState.isLoading -> LoadingIndicator(modifier = Modifier.padding(padding))
            uiState.items.isEmpty() -> EmptyState("No scheduled transactions", modifier = Modifier.padding(padding))
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.items, key = { it.id }) { item ->
                        ScheduledCard(
                            item = item,
                            onEdit = if (uiState.isReadOnly) null else {{ onEditClick(item.id) }},
                            onDelete = if (uiState.isReadOnly) null else {{ viewModel.delete(item.id) }}
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduledCard(item: ScheduledTransaction, onEdit: (() -> Unit)?, onDelete: (() -> Unit)?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.description ?: item.type.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleSmall
                    )
                    val (nextDate, nextTime) = if (item.nextOccurrence.contains("T")) {
                        val parts = item.nextOccurrence.split("T", limit = 2)
                        parts[0] to parts[1]
                    } else {
                        item.nextOccurrence to null
                    }
                    Text(
                        text = "Next: $nextDate" + (nextTime?.let { " at $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val rruleLabel = mapOf(
                        "FREQ=DAILY" to "Daily", "FREQ=WEEKLY" to "Weekly",
                        "FREQ=WEEKLY;INTERVAL=2" to "Biweekly", "FREQ=MONTHLY" to "Monthly",
                        "FREQ=MONTHLY;INTERVAL=3" to "Quarterly", "FREQ=YEARLY" to "Yearly"
                    )
                    Text(
                        text = rruleLabel[item.rrule] ?: item.rrule,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (item.maxOccurrences != null) {
                        Text(
                            text = "${item.occurrenceCount}/${item.maxOccurrences} occurrences",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!item.active) {
                        Text("Inactive", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    AmountText(amount = item.amount, type = item.type, currency = item.currency)
                    if (onEdit != null || onDelete != null) {
                        Row {
                            if (onEdit != null) {
                                IconButton(onClick = onEdit) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(20.dp))
                                }
                            }
                            if (onDelete != null) {
                                IconButton(onClick = onDelete) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
