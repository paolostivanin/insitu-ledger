package com.insituledger.app.ui.common

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

object CurrencyFormatter {
    private const val MAX_CACHE_SIZE = 32

    private val cache = object : LinkedHashMap<String, NumberFormat>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NumberFormat>): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    fun format(amount: Double, currency: String): String {
        return try {
            val fmt = cache.getOrPut(currency) {
                NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
                    this.currency = Currency.getInstance(currency)
                }
            }
            fmt.format(amount)
        } catch (_: Exception) {
            "$currency %.2f".format(amount)
        }
    }

    // Renders with the user's preferred display symbol (per-user setting).
    // Symbol may be empty — in that case the amount is returned bare.
    fun formatWithSymbol(amount: Double, symbol: String): String {
        val n = "%,.2f".format(amount)
        return if (symbol.isEmpty()) n else "$symbol $n"
    }
}
