package com.insituledger.app.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.insituledger.app.ui.theme.AppSpacing

/** A secondary action offered from a row's long-press menu (and to TalkBack). */
data class RowAction(val label: String, val destructive: Boolean = false, val onClick: () -> Unit)

/**
 * Flat list row: optional leading slot, headline + supporting lines, optional
 * trailing slot (e.g. an amount, measured first so it never truncates). Tap
 * runs [onClick]; long-press opens a menu of [actions], which are also exposed
 * as accessibility custom actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListRow(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    supportingColor: Color = Color.Unspecified,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    actions: List<RowAction> = emptyList(),
    horizontalPadding: Dp = AppSpacing.screenH,
    headlineMaxLines: Int = 1
) {
    var menuOpen by remember { mutableStateOf(false) }
    val interactive = onClick != null || actions.isNotEmpty()
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (interactive) Modifier.combinedClickable(
                        onClick = { onClick?.invoke() ?: run { menuOpen = true } },
                        onLongClick = if (actions.isNotEmpty()) ({ menuOpen = true }) else null,
                        onLongClickLabel = if (actions.isNotEmpty()) "More actions" else null
                    ) else Modifier
                )
                .semantics(mergeDescendants = true) {
                    if (actions.isNotEmpty()) {
                        customActions = actions.map { a -> CustomAccessibilityAction(a.label) { a.onClick(); true } }
                    }
                }
                .heightIn(min = AppSpacing.rowMinHeight)
                .padding(horizontal = horizontalPadding, vertical = AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.size(AppSpacing.lg))
            }
            AdaptiveTrailingRow(
                modifier = Modifier.weight(1f),
                content = {
                    Column {
                        Text(
                            headline,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = headlineMaxLines,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (supporting != null) {
                            Text(
                                supporting,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (supportingColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else supportingColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                trailing = { trailing?.invoke() }
            )
        }
        if (actions.isNotEmpty()) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                actions.forEach { action ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                action.label,
                                color = if (action.destructive) MaterialTheme.colorScheme.error else Color.Unspecified
                            )
                        },
                        onClick = { menuOpen = false; action.onClick() }
                    )
                }
            }
        }
    }
}
