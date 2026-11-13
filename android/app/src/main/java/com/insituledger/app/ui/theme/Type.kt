package com.insituledger.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.insituledger.app.R

val InterFontFamily = FontFamily(
	Font(R.font.inter_regular, FontWeight.Normal),
	Font(R.font.inter_medium, FontWeight.Medium),
	Font(R.font.inter_semibold, FontWeight.SemiBold),
	Font(R.font.inter_bold, FontWeight.Bold)
)

private fun inter(
	size: Int,
	lineHeight: Int,
	weight: FontWeight,
	tracking: Double = 0.0
) = TextStyle(
	fontFamily = InterFontFamily,
	fontWeight = weight,
	fontSize = size.sp,
	lineHeight = lineHeight.sp,
	letterSpacing = tracking.sp
)

/**
 * One consistent scale: Medium for titles and labels, Normal for body text.
 * Screen titles are 20sp; only the balance (32sp) and amount entry (24sp)
 * are larger. Call sites should not override fontWeight.
 */
val AppTypography = Typography(
	displayLarge = inter(40, 48, FontWeight.Medium, -0.5),
	displayMedium = inter(36, 44, FontWeight.Medium, -0.5),
	displaySmall = inter(32, 40, FontWeight.Medium, -0.25),
	headlineLarge = inter(28, 36, FontWeight.Medium, -0.25),
	headlineMedium = inter(24, 32, FontWeight.Medium),
	headlineSmall = inter(22, 28, FontWeight.Medium),
	titleLarge = inter(20, 28, FontWeight.Medium),
	titleMedium = inter(16, 24, FontWeight.Medium, 0.1),
	titleSmall = inter(14, 20, FontWeight.Medium, 0.1),
	bodyLarge = inter(16, 24, FontWeight.Normal, 0.15),
	bodyMedium = inter(14, 20, FontWeight.Normal, 0.15),
	bodySmall = inter(12, 16, FontWeight.Normal, 0.3),
	labelLarge = inter(14, 20, FontWeight.Medium, 0.1),
	labelMedium = inter(12, 16, FontWeight.Medium, 0.4),
	labelSmall = inter(11, 16, FontWeight.Medium, 0.5)
)

private const val TABULAR = "tnum"

/** Balance headline: 32/40 Medium, tabular figures. */
val Typography.amountLarge: TextStyle
	get() = displaySmall.copy(fontFeatureSettings = TABULAR)

/** Amount entry field: 24/32 Medium, tabular figures. */
val Typography.amountEntry: TextStyle
	get() = headlineMedium.copy(fontFeatureSettings = TABULAR)

/** List-row amounts: 16/24 Medium, tabular figures. */
val Typography.amountMedium: TextStyle
	get() = titleMedium.copy(fontFeatureSettings = TABULAR)

/** Secondary amounts (day totals, supporting figures): 14/20 Medium, tabular. */
val Typography.amountSmall: TextStyle
	get() = labelLarge.copy(fontFeatureSettings = TABULAR)
