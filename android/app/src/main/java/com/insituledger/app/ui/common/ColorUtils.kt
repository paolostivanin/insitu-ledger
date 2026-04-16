package com.insituledger.app.ui.common

import androidx.compose.ui.graphics.Color

object ColorUtils {
	private val cache = HashMap<String, Color>(32)

	fun parseHex(hex: String?, fallback: Color = Color.Gray): Color {
		if (hex == null) return fallback
		val normalized = if (hex.startsWith("#")) hex else "#$hex"
		return cache.getOrPut(normalized) {
			try {
				Color(android.graphics.Color.parseColor(normalized))
			} catch (_: Exception) {
				fallback
			}
		}
	}
}
