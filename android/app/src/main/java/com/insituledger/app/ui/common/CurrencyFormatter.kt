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
}
