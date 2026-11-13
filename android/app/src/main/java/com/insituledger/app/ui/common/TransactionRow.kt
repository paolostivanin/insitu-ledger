package com.insituledger.app.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.insituledger.app.ui.theme.AppSpacing

/** Everything a ledger row needs, precomputed by the caller. */
@Immutable
data class TransactionRowModel(
    val id: Long,
    val title: String,
    /** "Groceries · Checking · 14:05" */
    val supporting: String,
    val categoryColor: Color?,
    val attribution: String?,
    val amount: Double,
    val type: String,
    /** Display symbol for this row's currency. */
    val symbol: String
)

/**
 * The single ledger row used by Dashboard, Transactions and Reports.
 * Description (ellipsized) over a category-dot supporting line; the signed
 * amount is measured first so it is never truncated, and drops below the text
 * when space is tight.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionRow(
    model: TransactionRowModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    horizontalPadding: androidx.compose.ui.unit.Dp = AppSpacing.screenH
) {
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onClickLabel = if (selectionMode) "Toggle selection" else "Edit",
                onLongClickLabel = if (onLongClick != null) "Select" else null
            )
            .semantics(mergeDescendants = true) { if (selectionMode) this.selected = selected }
            .heightIn(min = 64.dp)
            .padding(horizontal = horizontalPadding, vertical = AppSpacing.sm + 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = null)
            Spacer(Modifier.size(AppSpacing.md))
        }
        AdaptiveTrailingRow(
            modifier = Modifier.weight(1f),
            content = {
                Column {
                    Text(
                        text = model.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (model.categoryColor != null) {
                            CategoryDot(model.categoryColor)
                            Spacer(Modifier.size(6.dp))
                        }
                        Text(
                            text = model.supporting,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (model.attribution != null) {
                        Text(
                            text = model.attribution,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            trailing = {
                TransactionAmount(amount = model.amount, type = model.type, symbol = model.symbol)
            }
        )
    }
}
