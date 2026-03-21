package com.insituledger.app.ui.common

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

object CurrencyFormatter {
    private val cache = mutableMapOf<String, NumberFormat>()

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
