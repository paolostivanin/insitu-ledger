package com.insituledger.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.insituledger.app.ui.theme.AppSpacing
import androidx.compose.foundation.clickable

/** Thin top progress bar for first loads; data comes from Room in milliseconds. */
@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = "Loading" }
    ) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
    }
}

/** Restrained empty state: small icon, short title, one line, optional action. */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = BiasAlignment(0f, -0.35f)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 360.dp)
                .padding(horizontal = AppSpacing.xl)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.size(AppSpacing.md))
            }
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.size(AppSpacing.xs))
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.size(AppSpacing.lg))
                FilledTonalButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/** Expense / Income selector. Neutral colours: the type is not a warning. */
@Composable
fun TypeToggle(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = listOf("expense" to "Expense", "income" to "Income")
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = selected == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(label, maxLines = 1)
            }
        }
    }
}

/** The single primary FAB used on list screens. */
@Composable
fun AddFab(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = 2.dp,
            pressedElevation = 4.dp,
            focusedElevation = 2.dp,
            hoveredElevation = 3.dp
        )
    ) {
        Icon(Icons.Default.Add, contentDescription = contentDescription)
    }
}

/** Confirmation dialog; the confirm action is error-coloured when destructive. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
    dismissLabel: String = "Cancel",
    working: Boolean = false
) {
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !working,
                colors = if (destructive) androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ) else androidx.compose.material3.ButtonDefaults.textButtonColors()
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) { Text(dismissLabel) }
        }
    )
}

/**
 * A form row that opens a picker: leading icon, value (or placeholder), and a
 * trailing chevron. 56dp tall, announced as a button with its label.
 */
@Composable
fun SelectorRow(
    icon: ImageVector,
    label: String,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = label,
    valueLeading: (@Composable () -> Unit)? = null,
    supporting: String? = null,
    isError: Boolean = false,
    trailing: (@Composable () -> Unit)? = {
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AppSpacing.rowMinHeight)
            .clickable(role = Role.Button, onClickLabel = "Change $label", onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "$label: ${value ?: "not set"}"
            }
            .padding(horizontal = AppSpacing.screenH),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(AppSpacing.lg))
        Column(modifier = Modifier.weight(1f).padding(vertical = AppSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (valueLeading != null && value != null) {
                    valueLeading()
                    Spacer(Modifier.size(AppSpacing.sm))
                }
                Text(
                    text = value ?: placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = when {
                        isError -> MaterialTheme.colorScheme.error
                        value == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.size(AppSpacing.sm))
            trailing()
        }
    }
}

/**
 * The primary form action, pinned to the bottom and lifted above the keyboard.
 * Handles navigation-bar and IME insets itself.
 */
@Composable
fun FormActionBar(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    loading: Boolean = false,
    enabled: Boolean = true
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                    .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.md)
            ) {
                Button(
                    onClick = onClick,
                    enabled = enabled && !loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.size(AppSpacing.sm))
                    } else if (icon != null) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(AppSpacing.sm))
                    }
                    Text(label, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/**
 * Lays out [content] with [trailing] (an amount) at the end. The trailing slot
 * is measured first at its natural width so money is never truncated; when it
 * would take more than [maxTrailingFraction] of the width, it drops below the
 * content instead, aligned to the end.
 */
@Composable
fun AdaptiveTrailingRow(
    modifier: Modifier = Modifier,
    gap: androidx.compose.ui.unit.Dp = AppSpacing.md,
    maxTrailingFraction: Float = 0.45f,
    content: @Composable () -> Unit,
    trailing: @Composable () -> Unit
) {
    Layout(
        contents = listOf(content, trailing),
        modifier = modifier
    ) { (contentMeasurables, trailingMeasurables), constraints ->
        val maxWidth = constraints.maxWidth
        val gapPx = gap.roundToPx()
        val trailingPlaceables = trailingMeasurables.map {
            it.measure(Constraints(maxWidth = Constraints.Infinity))
        }
        val trailingWidth = trailingPlaceables.maxOfOrNull { it.width } ?: 0
        val trailingHeight = trailingPlaceables.sumOf { it.height }
        val stacked = maxWidth != Constraints.Infinity &&
            trailingWidth > (maxWidth * maxTrailingFraction).toInt()

        if (!stacked) {
            val contentMax = (maxWidth - trailingWidth - gapPx).coerceAtLeast(0)
            val contentPlaceables = contentMeasurables.map {
                it.measure(constraints.copy(minWidth = 0, maxWidth = contentMax))
            }
            val contentHeight = contentPlaceables.sumOf { it.height }
            val height = maxOf(contentHeight, trailingHeight).coerceAtLeast(constraints.minHeight)
            layout(maxWidth, height) {
                var y = (height - contentHeight) / 2
                contentPlaceables.forEach { it.placeRelative(0, y); y += it.height }
                var ty = (height - trailingHeight) / 2
                trailingPlaceables.forEach { it.placeRelative(maxWidth - it.width, ty); ty += it.height }
            }
        } else {
            val contentPlaceables = contentMeasurables.map {
                it.measure(constraints.copy(minWidth = 0, minHeight = 0))
            }
            val contentHeight = contentPlaceables.sumOf { it.height }
            val height = (contentHeight + trailingHeight).coerceAtLeast(constraints.minHeight)
            layout(maxWidth, height) {
                var y = 0
                contentPlaceables.forEach { it.placeRelative(0, y); y += it.height }
                trailingPlaceables.forEach {
                    it.placeRelative((maxWidth - it.width).coerceAtLeast(0), y); y += it.height
                }
            }
        }
    }
}
