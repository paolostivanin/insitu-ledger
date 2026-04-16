package com.insituledger.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Immutable
data class SemanticColors(
    val income: Color,
    val expense: Color
)

private val LightSemanticColors = SemanticColors(
    income = Color(0xFF2E7D32),
    expense = Color(0xFFC62828)
)

private val DarkSemanticColors = SemanticColors(
    income = Color(0xFF66BB6A),
    expense = Color(0xFFEF5350)
)

val LocalSemanticColors = compositionLocalOf { LightSemanticColors }

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF00796B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8E2),
    onSecondaryContainer = Color(0xFF06201B),
    tertiary = Color(0xFF436278),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC8E6FF),
    onTertiaryContainer = Color(0xFF001E30),
    error = Color(0xFFBA1A1A),
    background = Color(0xFFFAFDFB),
    surface = Color(0xFFFAFDFB),
    surfaceVariant = Color(0xFFDAE5E1),
    onSurfaceVariant = Color(0xFF3F4946)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF80CBC4),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFFB2DFDB),
    secondary = Color(0xFFB1CCC6),
    onSecondary = Color(0xFF1C3530),
    secondaryContainer = Color(0xFF334B46),
    onSecondaryContainer = Color(0xFFCCE8E2),
    tertiary = Color(0xFFABCAE4),
    onTertiary = Color(0xFF0F3448),
    tertiaryContainer = Color(0xFF2B4A5F),
    onTertiaryContainer = Color(0xFFC8E6FF),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF191C1B),
    surface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBEC9C5)
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

    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

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
