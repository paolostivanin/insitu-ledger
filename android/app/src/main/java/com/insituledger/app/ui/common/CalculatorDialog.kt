package com.insituledger.app.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
        } else {
            display = if (display == "0") n else display + n
        }
    }

    fun onDecimal() {
        if (awaitingSecondOperand) {
            display = "0."
            awaitingSecondOperand = false
        } else if (!display.contains(".")) {
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calculator") },
        text = {
            Column {
                Text(
                    text = display,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                )

                val buttons = listOf(
                    listOf("7", "8", "9", "÷"),
                    listOf("4", "5", "6", "×"),
                    listOf("1", "2", "3", "−"),
                    listOf("C", "0", ".", "+"),
                    listOf("⌫", "=")
                )

                buttons.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        row.forEach { label ->
                            val weight = if (row.size == 2) 2f else 1f
                            Button(
                                onClick = {
                                    when (label) {
                                        in "0".."9" -> onNumber(label)
                                        "." -> onDecimal()
                                        "+", "−", "×", "÷" -> onOperator(label)
                                        "=" -> onEquals()
                                        "C" -> onClear()
                                        "⌫" -> onBackspace()
                                    }
                                },
                                modifier = Modifier.weight(weight).height(48.dp),
                                colors = when (label) {
                                    "+", "−", "×", "÷" -> ButtonDefaults.filledTonalButtonColors()
                                    "C", "⌫" -> ButtonDefaults.outlinedButtonColors()
                                    "=" -> ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                    else -> ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(label, fontSize = 18.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(display) }) { Text("Use") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun formatResult(value: Double): String {
    return if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        "%.2f".format(value).trimEnd('0').trimEnd('.')
    }
}
