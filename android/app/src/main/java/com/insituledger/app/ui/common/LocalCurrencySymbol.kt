package com.insituledger.app.ui.common

import androidx.compose.runtime.compositionLocalOf

// User's preferred display symbol for amounts. Provided once at the app root
// from UserPreferences and consumed by every composable that renders money.
val LocalCurrencySymbol = compositionLocalOf { "€" }
