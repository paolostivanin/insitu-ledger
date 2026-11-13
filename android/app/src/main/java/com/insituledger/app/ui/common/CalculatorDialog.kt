package com.insituledger.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.insituledger.app.ui.theme.AppSpacing
import com.insituledger.app.ui.theme.amountEntry
import com.insituledger.app.ui.theme.amountLarge
import com.insituledger.app.ui.theme.amountMedium
import java.util.Locale
import kotlin.math.abs

/** Longest operand the keypad accepts; keeps the display readable at the smallest step. */
private const val MAX_INPUT_LENGTH = 15

private val KEY_HEIGHT = 56.dp

private enum class KeyStyle { DIGIT, OPERATOR, EQUALS }

@Composable
fun CalculatorDialog(
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var display by remember { mutableStateOf(initialValue.ifBlank { "0" }) }
    var firstOperand by remember { mutableStateOf<Double?>(null) }
    var operator by remember { mutableStateOf<String?>(null) }
    var awaitingSecondOperand by remember { mutableStateOf(false) }

    fun calculate(a: Double, op: String, b: Double): Double = when (op) {
        "+" -> a + b
        "−" -> a - b
        "×" -> a * b
        "÷" -> if (b != 0.0) a / b else 0.0
        else -> b
    }

    fun onNumber(n: String) {
        if (awaitingSecondOperand) {
            display = n
            awaitingSecondOperand = false
        } else if (display.length < MAX_INPUT_LENGTH) {
            display = if (display == "0") n else display + n
        }
    }

    fun onDecimal() {
        if (awaitingSecondOperand) {
            display = "0."
            awaitingSecondOperand = false
        } else if (!display.contains(".") && display.length < MAX_INPUT_LENGTH) {
            display += "."
        }
    }

    fun onOperator(op: String) {
        val current = display.toDoubleOrNull() ?: return
        if (firstOperand != null && operator != null && !awaitingSecondOperand) {
            val result = calculate(firstOperand!!, operator!!, current)
            display = formatResult(result)
            firstOperand = result
        } else {
            firstOperand = current
        }
        operator = op
        awaitingSecondOperand = true
    }

    fun onEquals() {
        val current = display.toDoubleOrNull() ?: return
        if (firstOperand != null && operator != null) {
            val result = calculate(firstOperand!!, operator!!, current)
            display = formatResult(result)
            firstOperand = null
            operator = null
            awaitingSecondOperand = false
        }
    }

    fun onClear() {
        display = "0"
        firstOperand = null
        operator = null
        awaitingSecondOperand = false
    }

    fun onBackspace() {
        display = if (display.length > 1) display.dropLast(1) else "0"
    }

    // "12 +" while an operation is pending; blank otherwise.
    val pending = if (firstOperand != null && operator != null) "${formatResult(firstOperand!!)} $operator" else ""

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calculator") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = AppSpacing.sm)
                ) {
                    Text(
                        text = pending,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Box(
                        contentAlignment = Alignment.CenterEnd,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    ) {
                        AutoSizeMoneyText(
                            text = display,
                            style = MaterialTheme.typography.amountLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            steps = with(MaterialTheme.typography) {
                                listOf(
                                    amountLarge.fontSize,
                                    headlineLarge.fontSize,
                                    amountEntry.fontSize,
                                    titleLarge.fontSize,
                                    amountMedium.fontSize
                                )
                            }
                        )
                    }
                }

                KeyRow {
                    CalcKey("C", KeyStyle.OPERATOR, "Clear", weight = 2f, onClick = ::onClear)
                    CalcKey("⌫", KeyStyle.OPERATOR, "Backspace", onClick = ::onBackspace, isBackspace = true)
                    CalcKey("÷", KeyStyle.OPERATOR, "Divide") { onOperator("÷") }
                }
                KeyRow {
                    CalcKey("7", KeyStyle.DIGIT) { onNumber("7") }
                    CalcKey("8", KeyStyle.DIGIT) { onNumber("8") }
                    CalcKey("9", KeyStyle.DIGIT) { onNumber("9") }
                    CalcKey("×", KeyStyle.OPERATOR, "Multiply") { onOperator("×") }
                }
                KeyRow {
                    CalcKey("4", KeyStyle.DIGIT) { onNumber("4") }
                    CalcKey("5", KeyStyle.DIGIT) { onNumber("5") }
                    CalcKey("6", KeyStyle.DIGIT) { onNumber("6") }
                    CalcKey("−", KeyStyle.OPERATOR, "Minus") { onOperator("−") }
                }
                KeyRow {
                    CalcKey("1", KeyStyle.DIGIT) { onNumber("1") }
                    CalcKey("2", KeyStyle.DIGIT) { onNumber("2") }
                    CalcKey("3", KeyStyle.DIGIT) { onNumber("3") }
                    CalcKey("+", KeyStyle.OPERATOR, "Plus") { onOperator("+") }
                }
                KeyRow {
                    CalcKey("0", KeyStyle.DIGIT, weight = 2f) { onNumber("0") }
                    CalcKey(".", KeyStyle.DIGIT, "Decimal point", onClick = ::onDecimal)
                    CalcKey("=", KeyStyle.EQUALS, "Equals", onClick = ::onEquals)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // A pending "12 + 5" is applied rather than silently dropping the 12.
                if (operator != null && !awaitingSecondOperand) onEquals()
                onConfirm(display)
            }) { Text("Use") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        content = content
    )
}

/**
 * One keypad key. Digits are quiet text buttons, operators and editing keys
 * are tonal, and "=" is the single filled key.
 */
@Composable
private fun RowScope.CalcKey(
    label: String,
    style: KeyStyle,
    spoken: String? = null,
    weight: Float = 1f,
    isBackspace: Boolean = false,
    onClick: () -> Unit
) {
    val modifier = Modifier
        .weight(weight)
        .heightIn(min = KEY_HEIGHT)
    val shape = MaterialTheme.shapes.medium
    val padding = PaddingValues(0.dp)
    val content: @Composable RowScope.() -> Unit = {
        if (isBackspace) {
            Icon(Icons.AutoMirrored.Outlined.Backspace, contentDescription = spoken)
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                modifier = if (spoken != null) Modifier.clearAndSetSemantics { contentDescription = spoken } else Modifier
            )
        }
    }
    when (style) {
        KeyStyle.DIGIT -> TextButton(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            contentPadding = padding,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            content = content
        )
        KeyStyle.OPERATOR -> FilledTonalButton(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            contentPadding = padding,
            content = content
        )
        KeyStyle.EQUALS -> Button(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            contentPadding = padding,
            content = content
        )
    }
}

private fun formatResult(value: Double): String {
    // Absurdly large results stay parseable and short instead of 40 digits wide.
    if (!value.isFinite() || abs(value) >= 1e15) return String.format(Locale.US, "%.6e", value)
    return if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        // Force Locale.US so the display always uses dot as decimal separator.
        // toDoubleOrNull() relies on Java's parser which only accepts dots,
        // so any comma here would break onOperator/onEquals on the next press.
        String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
    }
}
