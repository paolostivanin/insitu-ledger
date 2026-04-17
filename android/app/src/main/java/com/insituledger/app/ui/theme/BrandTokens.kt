package com.insituledger.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

object BrandGradients {

	@Composable
	@ReadOnlyComposable
	fun hero(): Brush = if (isSystemInDarkTheme()) {
		Brush.linearGradient(
			colors = listOf(
				Color(0xFF00574E),
				Color(0xFF1F8F7F),
				Color(0xFF2DAA8E)
			)
		)
	} else {
		Brush.linearGradient(
			colors = listOf(
				Color(0xFF00897B),
				Color(0xFF12A38C),
				Color(0xFF26C6A1)
			)
		)
	}

	@Composable
	@ReadOnlyComposable
	fun heroSubtle(): Brush = if (isSystemInDarkTheme()) {
		Brush.linearGradient(
			colors = listOf(
				Color(0xFF1A2220),
				Color(0xFF1F2D2A)
			)
		)
	} else {
		Brush.linearGradient(
			colors = listOf(
				Color(0xFFEAF5F2),
				Color(0xFFD7EDE7)
			)
		)
	}

	@Composable
	@ReadOnlyComposable
	fun income(): Brush = if (isSystemInDarkTheme()) {
		Brush.linearGradient(
			colors = listOf(Color(0xFF1F3A28), Color(0xFF2A4F36))
		)
	} else {
		Brush.linearGradient(
			colors = listOf(Color(0xFFE7F6EC), Color(0xFFD7F1DF))
		)
	}

	@Composable
	@ReadOnlyComposable
	fun expense(): Brush = if (isSystemInDarkTheme()) {
		Brush.linearGradient(
			colors = listOf(Color(0xFF4A1F1C), Color(0xFF5C2622))
		)
	} else {
		Brush.linearGradient(
			colors = listOf(Color(0xFFFCE7E5), Color(0xFFFADAD7))
		)
	}
}

object BrandElevation {

	private val ShadowColor = Color(0xFF003B33)

	@Composable
	fun card(shape: Shape, level: Int = 1): Modifier {
		val elev = when (level) {
			0 -> 0.dp
			1 -> 2.dp
			2 -> 6.dp
			3 -> 12.dp
			else -> 2.dp
		}
		return Modifier.shadow(
			elevation = elev,
			shape = shape,
			ambientColor = ShadowColor,
			spotColor = ShadowColor,
			clip = false
		)
	}
}
