package com.insituledger.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1B6B4A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA7F5C5),
    onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF4E6355),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD0E8D6),
    onSecondaryContainer = Color(0xFF0B1F14),
    tertiary = Color(0xFF3C6472),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC0E9F9),
    onTertiaryContainer = Color(0xFF001F28),
    error = Color(0xFFBA1A1A),
    background = Color(0xFFFBFDF8),
    surface = Color(0xFFFBFDF8),
    surfaceVariant = Color(0xFFDCE5DC),
    onSurfaceVariant = Color(0xFF404942)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8BD8AA),
    onPrimary = Color(0xFF003822),
    primaryContainer = Color(0xFF005234),
    onPrimaryContainer = Color(0xFFA7F5C5),
    secondary = Color(0xFFB5CCBB),
    onSecondary = Color(0xFF203529),
    secondaryContainer = Color(0xFF364B3E),
    onSecondaryContainer = Color(0xFFD0E8D6),
    tertiary = Color(0xFFA4CDDD),
    onTertiary = Color(0xFF053542),
    tertiaryContainer = Color(0xFF234C59),
    onTertiaryContainer = Color(0xFFC0E9F9),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF191C19),
    surface = Color(0xFF191C19),
    surfaceVariant = Color(0xFF404942),
    onSurfaceVariant = Color(0xFFC0C9C0)
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

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
