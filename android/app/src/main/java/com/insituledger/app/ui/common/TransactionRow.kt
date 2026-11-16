package com.insituledger.app.ui.common

import androidx.compose.ui.semantics.contentDescription

import com.insituledger.app.ui.theme.LocalSemanticColors

import androidx.compose.material.icons.automirrored.filled.TrendingUp

import androidx.compose.material.icons.automirrored.filled.TrendingDown

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.insituledger.app.domain.model.Category
import com.insituledger.app.ui.theme.AppSpacing
import com.insituledger.app.ui.theme.LocalIsDarkTheme

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
    val symbol: String,
    /** Emoji for the leading category avatar; see [categoryEmoji]. */
    val categoryIcon: String? = null,
    /** Category name; its initial fills the avatar when there is no emoji. */
    val categoryName: String? = null,
    /**
     * Lead the row with a [CategoryAvatar] instead of the inline category dot.
     * On whenever the caller supplies category data, so a list never mixes
     * the two layouts while categories load.
     */
    val showAvatar: Boolean = categoryIcon != null || categoryName != null,
    /**
     * Position in the schedule that auto-added the entry ("Monthly · 2 of 3",
     * see [seriesLabel]); drawn ahead of [supporting]. Null for ordinary entries.
     */
    val seriesLabel: String? = null,
    /** Show the repeat glyph after the title: the entry came from a schedule. */
    val recurring: Boolean = seriesLabel != null
)

/** The emoji a category is drawn with: its own icon, else its parent's. */
fun categoryEmoji(categories: List<Category>, id: Long?): String? {
    val cat = categories.find { it.id == id } ?: return null
    cat.icon?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val parent = cat.parentId?.let { pid -> categories.find { it.id == pid } }
    return parent?.icon?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * The single ledger row used by Dashboard, Transactions and Reports.
 * A category avatar (when the model carries category data), the description
 * (ellipsized) over a supporting line, and the signed amount, which is
 * measured first so it is never truncated and drops below the text when
 * space is tight. In selection mode the avatar turns into a check mark.
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
    horizontalPadding: Dp = AppSpacing.screenH
) {
    // A light primary tint (not secondaryContainer): income green and expense
    // red both keep AA contrast on it in the light theme.
    val background = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent
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
        if (model.showAvatar) {
            if (selectionMode && selected) {
                SelectedMark()
            } else {
                // Emoji when the category has one; otherwise the direction of
                // money (↗ income green, ↘ expense red), like the original rows.
                if (model.categoryIcon.isNullOrBlank()) {
                    DirectionAvatar(type = model.type)
                } else {
                    CategoryAvatar(color = model.categoryColor, icon = model.categoryIcon, label = model.categoryName ?: model.title)
                }
            }
            Spacer(Modifier.size(AppSpacing.lg))
        } else if (selectionMode) {
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
                        if (!model.showAvatar && model.categoryColor != null) {
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
                    // The series gets its own line in the accent colour, so
                    // "Monthly · 2 of 3" never blends into the category line.
                    if (model.recurring) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Icon(
                                Icons.Default.Repeat,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.size(AppSpacing.xs))
                            Text(
                                text = model.seriesLabel ?: "Recurring",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.semantics {
                                    contentDescription = "Recurring, ${model.seriesLabel ?: ""}".trimEnd(',', ' ')
                                }
                            )
                        }
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

/**
 * Category identity at a glance: a circle tinted with the category colour
 * (low alpha over surface) holding the category emoji, or the initial of
 * [label] when there is none. The initial uses the category hue, darkened
 * (light theme) or lightened (dark theme) just enough to clear WCAG AA on
 * the tint. Decorative for screen readers: the row's text names the category.
 * Sized in dp, like an icon, so it stays round at large font scales.
 */
@Composable
fun CategoryAvatar(
    color: Color?,
    icon: String?,
    label: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    val scheme = MaterialTheme.colorScheme
    val dark = LocalIsDarkTheme.current
    val (container, content) = remember(color, dark, scheme) {
        categoryAvatarColors(
            base = color,
            dark = dark,
            surface = scheme.surface,
            onSurface = scheme.onSurface,
            neutralContainer = scheme.surfaceContainerHighest,
            neutralContent = scheme.onSurfaceVariant
        )
    }
    val emoji = icon?.trim()?.takeIf { it.isNotEmpty() }
    val glyph = emoji ?: label?.let(::initialOf)
    val density = LocalDensity.current
    val glyphStyle = remember(size, density, emoji != null) {
        val px = with(density) { (size * if (emoji != null) 0.5f else 0.42f).toSp() }
        TextStyle(
            fontSize = px,
            lineHeight = px,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
        )
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center
    ) {
        if (glyph != null) {
            Text(
                text = glyph,
                style = MaterialTheme.typography.titleMedium.merge(glyphStyle),
                color = content,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
        }
    }
}

/** The avatar's selected state: a primary check circle of the same size. */
@Composable
private fun SelectedMark(size: Dp = 40.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** First letter or digit of [name], upper-cased ("🍕 Pizza" → "P"). */
private fun initialOf(name: String): String? {
    val cp = name.codePoints().filter { Character.isLetterOrDigit(it) }.findFirst()
    return if (cp.isPresent) String(Character.toChars(cp.asInt)).uppercase() else null
}

/**
 * Container and content colours for a category avatar. The container is the
 * category colour at 16% (light) / 24% (dark) over surface; the content
 * starts at the category colour and moves toward onSurface in 10% steps until
 * it reaches 4.5:1 on the container (onSurface itself always does).
 */
internal fun categoryAvatarColors(
    base: Color?,
    dark: Boolean,
    surface: Color,
    onSurface: Color,
    neutralContainer: Color,
    neutralContent: Color
): Pair<Color, Color> {
    if (base == null) return neutralContainer to neutralContent
    val solid = base.copy(alpha = 1f)
    val container = solid.copy(alpha = if (dark) 0.24f else 0.16f).compositeOver(surface)
    var content = solid
    var step = 0
    while (contrastRatio(content, container) < 4.5f && step < 10) {
        step++
        content = lerp(solid, onSurface, step / 10f)
    }
    return container to content
}

private fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return if (la > lb) la / lb else lb / la
}

/**
 * Fallback avatar for categories without an emoji: a trend arrow on the
 * money-direction tonal container (income ↗ green, expense ↘ red). Silent for
 * screen readers — the signed, labelled amount already says it.
 */
@Composable
fun DirectionAvatar(type: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val semantic = LocalSemanticColors.current
    val income = type == "income"
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (income) semantic.positiveContainer else semantic.negativeContainer)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (income) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
            contentDescription = null,
            tint = if (income) semantic.onPositiveContainer else semantic.onNegativeContainer,
            modifier = Modifier.size(size * 0.5f)
        )
    }
}
