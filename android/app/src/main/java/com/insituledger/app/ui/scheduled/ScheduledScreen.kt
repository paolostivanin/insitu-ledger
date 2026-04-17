package com.insituledger.app.ui.scheduled

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.domain.model.ScheduledTransaction
import com.insituledger.app.ui.common.AmountText
import com.insituledger.app.ui.common.AppCard
import com.insituledger.app.ui.common.EmptyState
import com.insituledger.app.ui.common.LoadingIndicator
import com.insituledger.app.ui.theme.AppSpacing
import com.insituledger.app.ui.theme.BrandGradients
import com.insituledger.app.ui.theme.LocalSemanticColors

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
                BrandFab(onClick = onAddClick, contentDescription = "Add Scheduled")
            }
        }
    ) { padding ->
        when {
            uiState.isLoading -> LoadingIndicator(modifier = Modifier.padding(padding))
            uiState.items.isEmpty() -> EmptyState(
                icon = Icons.Default.EventRepeat,
                title = "No scheduled transactions",
                message = "Set up recurring transactions for rent, salary, subscriptions and more.",
                actionLabel = if (!uiState.isReadOnly) "Add scheduled" else null,
                onAction = if (!uiState.isReadOnly) onAddClick else null,
                modifier = Modifier.padding(padding)
            )
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(
                        start = AppSpacing.screenPadding,
                        end = AppSpacing.screenPadding,
                        top = AppSpacing.sm,
                        bottom = AppSpacing.xxl
                    ),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    items(uiState.items, key = { it.id }) { item ->
                        ScheduledRow(
                            item = item,
                            onEdit = if (uiState.isReadOnly) null else {{ onEditClick(item.id) }},
                            onDelete = if (uiState.isReadOnly) null else {{ viewModel.delete(item.id) }},
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduledRow(
    item: ScheduledTransaction,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current
    val isIncome = item.type == "income"
    val accent = if (isIncome) semantic.income else semantic.expense
    val container = if (isIncome) semantic.incomeContainer else semantic.expenseContainer

    AppCard(modifier = modifier.fillMaxWidth(), level = 1) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(container),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.EventRepeat,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(AppSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    Text(
                        text = item.description ?: item.type.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!item.active) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Text(
                                "Inactive",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                val (nextDate, nextTime) = if (item.nextOccurrence.contains("T")) {
                    val parts = item.nextOccurrence.split("T", limit = 2)
                    parts[0] to parts[1]
                } else {
                    item.nextOccurrence to null
                }
                val rruleLabel = mapOf(
                    "FREQ=DAILY" to "Daily",
                    "FREQ=WEEKLY" to "Weekly",
                    "FREQ=WEEKLY;INTERVAL=2" to "Biweekly",
                    "FREQ=MONTHLY" to "Monthly",
                    "FREQ=MONTHLY;INTERVAL=3" to "Quarterly",
                    "FREQ=YEARLY" to "Yearly"
                )[item.rrule] ?: item.rrule
                val nextLabel = "Next: $nextDate" + (nextTime?.let { " · $it" } ?: "")
                Text(
                    text = "$rruleLabel  ·  $nextLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
                if (item.maxOccurrences != null) {
                    Text(
                        text = "${item.occurrenceCount}/${item.maxOccurrences} occurrences",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                AmountText(amount = item.amount, type = item.type, currency = item.currency)
                if (onEdit != null || onDelete != null) {
                    Row {
                        if (onEdit != null) {
                            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(18.dp))
                            }
                        }
                        if (onDelete != null) {
                            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrandFab(onClick: () -> Unit, contentDescription: String) {
    val gradient = BrandGradients.hero()
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        shadowElevation = 8.dp,
        modifier = Modifier.size(64.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
