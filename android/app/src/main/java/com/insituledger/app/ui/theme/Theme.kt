package com.insituledger.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Money semantics. `positive` marks income; `negative` is reserved for values
 * that are actually below zero (overdrawn balance, negative net). Expense rows
 * render in onSurface with a true minus sign — red is not the default for
 * spending. Every pair here clears WCAG AA on surface and surfaceContainer.
 */
@Immutable
data class SemanticColors(
	val positive: Color,
	val negative: Color,
	val positiveContainer: Color,
	val negativeContainer: Color
) {
	// Aliases kept for readability at income/expense call sites.
	val income: Color get() = positive
	val expense: Color get() = negative
}

private val LightSemanticColors = SemanticColors(
	positive = Color(0xFF1E7B45),
	negative = Color(0xFFB4372B),
	positiveContainer = Color(0xFFD9F0E1),
	negativeContainer = Color(0xFFF9DEDA)
)

private val DarkSemanticColors = SemanticColors(
	positive = Color(0xFF7DD3A0),
	negative = Color(0xFFFF9C8F),
	positiveContainer = Color(0xFF1D3828),
	negativeContainer = Color(0xFF47221D)
)

val LocalSemanticColors = compositionLocalOf { LightSemanticColors }

/** The resolved in-app theme (honours the Light/Dark/System setting). */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

private val LightColorScheme = lightColorScheme(
	primary = Color(0xFF0B6E63),
	onPrimary = Color.White,
	primaryContainer = Color(0xFFCDEBE5),
	onPrimaryContainer = Color(0xFF00201C),
	inversePrimary = Color(0xFF7FD3C6),

	secondary = Color(0xFF4D5F5C),
	onSecondary = Color.White,
	secondaryContainer = Color(0xFFDDE6E3),
	onSecondaryContainer = Color(0xFF17201E),

	tertiary = Color(0xFF7C5800),
	onTertiary = Color.White,
	tertiaryContainer = Color(0xFFFFDEA6),
	onTertiaryContainer = Color(0xFF271900),

	error = Color(0xFFBA1A1A),
	onError = Color.White,
	errorContainer = Color(0xFFFFDAD6),
	onErrorContainer = Color(0xFF410002),

	background = Color(0xFFFBFBFA),
	onBackground = Color(0xFF1A1C1B),
	surface = Color(0xFFFBFBFA),
	onSurface = Color(0xFF1A1C1B),

	surfaceVariant = Color(0xFFE3E5E4),
	onSurfaceVariant = Color(0xFF5B6260),
	surfaceTint = Color(0xFF0B6E63),

	surfaceContainerLowest = Color(0xFFFFFFFF),
	surfaceContainerLow = Color(0xFFF5F6F5),
	surfaceContainer = Color(0xFFEFF1F0),
	surfaceContainerHigh = Color(0xFFE9EBEA),
	surfaceContainerHighest = Color(0xFFE3E5E4),
	surfaceBright = Color(0xFFFBFBFA),
	surfaceDim = Color(0xFFDBDDDC),

	inverseSurface = Color(0xFF2E3130),
	inverseOnSurface = Color(0xFFEFF1F0),

	outline = Color(0xFF79807E),
	outlineVariant = Color(0xFFDADEDC),
	scrim = Color(0xFF000000)
)

private val DarkColorScheme = darkColorScheme(
	primary = Color(0xFF7FD3C6),
	onPrimary = Color(0xFF003731),
	primaryContainer = Color(0xFF0F4F47),
	onPrimaryContainer = Color(0xFFCDEBE5),
	inversePrimary = Color(0xFF0B6E63),

	secondary = Color(0xFFB7C9C5),
	onSecondary = Color(0xFF223330),
	secondaryContainer = Color(0xFF34413F),
	onSecondaryContainer = Color(0xFFD5E2DF),

	tertiary = Color(0xFFF1C06A),
	onTertiary = Color(0xFF412D00),
	tertiaryContainer = Color(0xFF5E4200),
	onTertiaryContainer = Color(0xFFFFDEA6),

	error = Color(0xFFFFB4AB),
	onError = Color(0xFF690005),
	errorContainer = Color(0xFF93000A),
	onErrorContainer = Color(0xFFFFDAD6),

	background = Color(0xFF121413),
	onBackground = Color(0xFFE3E5E4),
	surface = Color(0xFF121413),
	onSurface = Color(0xFFE3E5E4),

	surfaceVariant = Color(0xFF3A3F3D),
	onSurfaceVariant = Color(0xFFBFC6C4),
	surfaceTint = Color(0xFF7FD3C6),

	surfaceContainerLowest = Color(0xFF0D0F0E),
	surfaceContainerLow = Color(0xFF1A1C1B),
	surfaceContainer = Color(0xFF1E2120),
	surfaceContainerHigh = Color(0xFF282B2A),
	surfaceContainerHighest = Color(0xFF333635),
	surfaceBright = Color(0xFF383A39),
	surfaceDim = Color(0xFF121413),

	inverseSurface = Color(0xFFE3E5E4),
	inverseOnSurface = Color(0xFF2E3130),

	outline = Color(0xFF899290),
	outlineVariant = Color(0xFF3A3F3D),
	scrim = Color(0xFF000000)
)

/** Resolves the stored theme preference ("light" / "dark" / anything = system). */
@Composable
fun resolveDarkTheme(themeMode: String?): Boolean = when (themeMode) {
	"dark" -> true
	"light" -> false
	else -> isSystemInDarkTheme()
}

@Composable
fun InSituLedgerTheme(
	darkTheme: Boolean,
	content: @Composable () -> Unit
) {
	val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
	val semanticColors = if (darkTheme) DarkSemanticColors else LightSemanticColors

	CompositionLocalProvider(
		LocalSemanticColors provides semanticColors,
		LocalIsDarkTheme provides darkTheme
	) {
		MaterialTheme(
			colorScheme = colorScheme,
			typography = AppTypography,
			shapes = AppShapes,
			content = content
		)
	}
}
