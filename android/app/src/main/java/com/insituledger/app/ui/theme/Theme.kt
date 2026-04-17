package com.insituledger.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class SemanticColors(
	val income: Color,
	val expense: Color,
	val incomeContainer: Color,
	val expenseContainer: Color
)

private val LightSemanticColors = SemanticColors(
	income = Color(0xFF1B7A3E),
	expense = Color(0xFFB3261E),
	incomeContainer = Color(0xFFD7F1DF),
	expenseContainer = Color(0xFFFADAD7)
)

private val DarkSemanticColors = SemanticColors(
	income = Color(0xFF7FD49C),
	expense = Color(0xFFFF8A80),
	incomeContainer = Color(0xFF1F3A28),
	expenseContainer = Color(0xFF4A1F1C)
)

val LocalSemanticColors = compositionLocalOf { LightSemanticColors }

// Brand: deep teal → emerald
private val BrandTealLight = Color(0xFF00897B)
private val BrandTealDark = Color(0xFF80CBC4)

private val LightColorScheme = lightColorScheme(
	primary = BrandTealLight,
	onPrimary = Color.White,
	primaryContainer = Color(0xFFA8E5DC),
	onPrimaryContainer = Color(0xFF002019),
	inversePrimary = Color(0xFF4FB3A1),

	secondary = Color(0xFF4A6360),
	onSecondary = Color.White,
	secondaryContainer = Color(0xFFCDE8E2),
	onSecondaryContainer = Color(0xFF052020),

	tertiary = Color(0xFFE57F45),
	onTertiary = Color.White,
	tertiaryContainer = Color(0xFFFFDBC8),
	onTertiaryContainer = Color(0xFF361100),

	error = Color(0xFFB3261E),
	onError = Color.White,
	errorContainer = Color(0xFFFADAD7),
	onErrorContainer = Color(0xFF410002),

	background = Color(0xFFF6FBF9),
	onBackground = Color(0xFF0E1614),
	surface = Color(0xFFF6FBF9),
	onSurface = Color(0xFF0E1614),

	surfaceVariant = Color(0xFFDCE5E2),
	onSurfaceVariant = Color(0xFF3F4946),

	surfaceContainerLowest = Color(0xFFFFFFFF),
	surfaceContainerLow = Color(0xFFF0F6F3),
	surfaceContainer = Color(0xFFEAF1EE),
	surfaceContainerHigh = Color(0xFFE3ECE8),
	surfaceContainerHighest = Color(0xFFDDE6E3),

	outline = Color(0xFF6F7976),
	outlineVariant = Color(0xFFBEC9C5),
	scrim = Color(0xFF000000)
)

private val DarkColorScheme = darkColorScheme(
	primary = BrandTealDark,
	onPrimary = Color(0xFF003731),
	primaryContainer = Color(0xFF005048),
	onPrimaryContainer = Color(0xFFA8E5DC),
	inversePrimary = BrandTealLight,

	secondary = Color(0xFFB1CCC6),
	onSecondary = Color(0xFF1C3530),
	secondaryContainer = Color(0xFF334B46),
	onSecondaryContainer = Color(0xFFCDE8E2),

	tertiary = Color(0xFFFFB68F),
	onTertiary = Color(0xFF552100),
	tertiaryContainer = Color(0xFF783200),
	onTertiaryContainer = Color(0xFFFFDBC8),

	error = Color(0xFFFFB4AB),
	onError = Color(0xFF690005),
	errorContainer = Color(0xFF93000A),
	onErrorContainer = Color(0xFFFADAD7),

	background = Color(0xFF0F1413),
	onBackground = Color(0xFFE2E4E2),
	surface = Color(0xFF0F1413),
	onSurface = Color(0xFFE2E4E2),

	surfaceVariant = Color(0xFF3F4946),
	onSurfaceVariant = Color(0xFFBEC9C5),

	surfaceContainerLowest = Color(0xFF0A0F0E),
	surfaceContainerLow = Color(0xFF161D1B),
	surfaceContainer = Color(0xFF1A2220),
	surfaceContainerHigh = Color(0xFF252D2B),
	surfaceContainerHighest = Color(0xFF303836),

	outline = Color(0xFF89938F),
	outlineVariant = Color(0xFF3F4946),
	scrim = Color(0xFF000000)
)

@Composable
fun InSituLedgerTheme(
	themeMode: String = "system",
	content: @Composable () -> Unit
) {
	val darkTheme = when (themeMode) {
		"dark" -> true
		"light" -> false
		else -> isSystemInDarkTheme()
	}

	val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
	val semanticColors = if (darkTheme) DarkSemanticColors else LightSemanticColors

	CompositionLocalProvider(LocalSemanticColors provides semanticColors) {
		MaterialTheme(
			colorScheme = colorScheme,
			typography = AppTypography,
			shapes = AppShapes,
			content = content
		)
	}
}
