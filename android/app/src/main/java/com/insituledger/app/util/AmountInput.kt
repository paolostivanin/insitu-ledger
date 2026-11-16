package com.insituledger.app.util

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A stored amount as an editable field value: 85.0 → "85", 12.5 → "12.5",
 * 1234567.89 → "1234567.89" (no grouping, no exponent, at most 2 decimals).
 */
fun formatAmountForInput(amount: Double): String =
    BigDecimal.valueOf(amount)
        .setScale(2, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
