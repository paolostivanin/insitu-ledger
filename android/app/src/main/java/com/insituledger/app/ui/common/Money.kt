package com.insituledger.app.ui.common

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.insituledger.app.domain.model.Account
import com.insituledger.app.ui.theme.LocalSemanticColors
import com.insituledger.app.ui.theme.amountMedium
import java.util.Currency
import java.util.Locale
import kotlin.math.abs

enum class MoneySign { AUTO, PLUS, MINUS, NONE }

/**
 * Single source of money formatting. Uses a true minus sign (U+2212), locale
 * digit grouping, and attaches a symbol without a space unless the symbol
 * ends in a letter ("CHF 12.00", but "€12.00").
 */
object MoneyFormat {
    const val MINUS = "−"

    fun number(value: Double): String = "%,.2f".format(abs(value))

    fun withSymbol(absText: String, symbol: String): String = when {
        symbol.isEmpty() -> absText
        symbol.last().isLetter() -> "$symbol $absText"
        else -> "$symbol$absText"
    }

    fun format(value: Double, symbol: String, sign: MoneySign = MoneySign.AUTO): String {
        val body = withSymbol(number(value), symbol)
        val prefix = when (sign) {
            MoneySign.PLUS -> "+"
            MoneySign.MINUS -> MINUS
            MoneySign.NONE -> ""
            MoneySign.AUTO -> if (value < 0 && number(value) != "0.00") MINUS else ""
        }
        return prefix + body
    }

    /** Signed amount of a transaction: + for income, − for expense. */
    fun transaction(amount: Double, type: String, symbol: String): String =
        format(amount, symbol, if (type == "income") MoneySign.PLUS else MoneySign.MINUS)

    /** Net figure: + when above zero, − when below, bare when zero. */
    fun net(value: Double, symbol: String): String =
        format(value, symbol, if (value > 0.004) MoneySign.PLUS else MoneySign.AUTO)

    /** Screen-reader phrasing, e.g. "Expense €42.50". */
    fun spokenTransaction(amount: Double, type: String, symbol: String): String {
        val label = if (type == "income") "Income" else "Expense"
        return "$label ${withSymbol(number(amount), symbol)}"
    }
}

/**
 * The currency all accounts are most commonly held in; amounts in that
 * currency use the user's preferred display symbol.
 */
fun primaryCurrency(accounts: List<Account>): String? =
    accounts.groupingBy { it.currency }.eachCount().maxByOrNull { it.value }?.key

/**
 * Display symbol for an amount in [code]. The user's preferred symbol applies
 * to the primary currency; other currencies get their own symbol so a USD
 * account never reads "€120.00".
 */
fun currencySymbolFor(code: String?, preferred: String, primaryCode: String?): String {
    if (code == null || primaryCode == null || code == primaryCode) return preferred
    return try {
        Currency.getInstance(code).getSymbol(Locale.getDefault())
    } catch (_: Exception) {
        code
    }
}

/** One-line money text that never wraps or ellipsizes. */
@Composable
fun MoneyText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.amountMedium,
    color: Color = Color.Unspecified,
    spoken: String? = null
) {
    Text(
        text = text,
        style = style,
        color = if (color == Color.Unspecified) LocalContentColor.current else color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
        modifier = if (spoken != null) modifier.semantics { contentDescription = spoken } else modifier
    )
}

/** Income in the positive colour with "+"; expense in onSurface with "−". */
@Composable
fun TransactionAmount(
    amount: Double,
    type: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.amountMedium,
    symbol: String = LocalCurrencySymbol.current
) {
    val color = if (type == "income") LocalSemanticColors.current.positive
    else MaterialTheme.colorScheme.onSurface
    MoneyText(
        text = MoneyFormat.transaction(amount, type, symbol),
        style = style,
        color = color,
        spoken = MoneyFormat.spokenTransaction(amount, type, symbol),
        modifier = modifier
    )
}

/** A balance: sign only when negative, negative colour only below zero. */
@Composable
fun BalanceText(
    value: Double,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.amountMedium,
    symbol: String = LocalCurrencySymbol.current,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    val text = MoneyFormat.format(value, symbol)
    MoneyText(
        text = text,
        style = style,
        color = if (value < -0.004) LocalSemanticColors.current.negative else color,
        modifier = modifier
    )
}

/** A net figure: "+" and positive colour above zero, "−" and negative below. */
@Composable
fun NetText(
    value: Double,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.amountMedium,
    symbol: String = LocalCurrencySymbol.current,
    neutralColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val semantic = LocalSemanticColors.current
    val color = when {
        value > 0.004 -> semantic.positive
        value < -0.004 -> semantic.negative
        else -> neutralColor
    }
    MoneyText(text = MoneyFormat.net(value, symbol), style = style, color = color, modifier = modifier)
}

/**
 * A large amount that steps its font size down (e.g. 32 → 28 → 24sp) until it
 * fits the available width, so a balance is never clipped. Compose 1.7 has no
 * built-in autosize. Implemented as a plain Layout (measured with
 * TextMeasurer, drawn with drawText) so it also works inside intrinsic-size
 * parents, where SubcomposeLayout-based BoxWithConstraints would crash.
 */
@Composable
fun AutoSizeMoneyText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    steps: List<TextUnit> = listOf(style.fontSize, 28.sp, 24.sp, 20.sp),
    spoken: String? = null
) {
    val measurer = rememberTextMeasurer()
    val holder = remember { arrayOfNulls<TextLayoutResult>(1) }
    val resolvedColor = if (color == Color.Unspecified) LocalContentColor.current else color
    Layout(
        modifier = modifier
            .semantics {
                this.text = AnnotatedString(text)
                if (spoken != null) contentDescription = spoken
            }
            .drawBehind { holder[0]?.let { drawText(it, color = resolvedColor) } },
        measurePolicy = { _, constraints ->
            val unbounded = Constraints(maxWidth = Constraints.Infinity)
            val candidates = steps.map { size ->
                style.copy(
                    fontSize = size,
                    lineHeight = if (style.lineHeight.isSp && style.fontSize.isSp) {
                        (style.lineHeight.value * size.value / style.fontSize.value).sp
                    } else style.lineHeight
                )
            }
            val fits = { r: TextLayoutResult -> constraints.maxWidth == Constraints.Infinity || r.size.width <= constraints.maxWidth }
            val result = candidates.asSequence()
                .map { measurer.measure(text, it, maxLines = 1, softWrap = false, constraints = unbounded) }
                .firstOrNull(fits)
                ?: run {
                    // Still too wide (huge amounts at large font scales): scale the
                    // smallest step down proportionally so the figure stays whole.
                    val last = candidates.last()
                    val wide = measurer.measure(text, last, maxLines = 1, softWrap = false, constraints = unbounded)
                    val ratio = (constraints.maxWidth.toFloat() / wide.size.width).coerceAtMost(1f) * 0.98f
                    val scaled = last.copy(
                        fontSize = (last.fontSize.value * ratio).sp,
                        lineHeight = if (last.lineHeight.isSp) (last.lineHeight.value * ratio).sp else last.lineHeight
                    )
                    measurer.measure(text, scaled, maxLines = 1, softWrap = false, constraints = unbounded)
                }
            holder[0] = result
            val width = result.size.width.coerceIn(constraints.minWidth, constraints.maxWidth)
            val height = result.size.height.coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(width, height) {}
        }
    )
}
