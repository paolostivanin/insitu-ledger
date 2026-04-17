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

val AppTypography = Typography(
	displayLarge = TextStyle(
		fontFamily = InterFontFamily,
		fontWeight = FontWeight.Bold,
		fontSize = 40.sp,
		lineHeight = 48.sp,
		letterSpacing = (-0.75).sp
	),
	displayMedium = TextStyle(
		fontFamily = InterFontFamily,
		fontWeight = FontWeight.Bold,
		fontSize = 32.sp,
		lineHeight = 40.sp,
		letterSpacing = (-0.5).sp
	),
	displaySmall = TextStyle(
		fontFamily = InterFontFamily,
		fontWeight = FontWeight.SemiBold,
		fontSize = 28.sp,
		lineHeight = 36.sp,
		letterSpacing = (-0.25).sp
	),
	headlineLarge = TextStyle(
		fontFamily = InterFontFamily,
		fontWeight = FontWeight.Bold,
		fontSize = 26.sp,
		lineHeight = 34.sp,
		letterSpacing = (-0.25).sp
	),
	headlineMedium = TextStyle(
		fontFamily = InterFontFamily,
		fontWeight = FontWeight.SemiBold,
		fontSize = 22.sp,
		lineHeight = 30.sp
	),
	headlineSmall = TextStyle(
		fontFamily = InterFontFamily,
		fontWeight = FontWeight.SemiBold,
		fontSize = 18.sp,
		lineHeight = 26.sp
	),
	titleLarge = TextStyle(
		fontFamily = InterFontFamily,
		fontWeight = FontWeight.SemiBold,
		fontSize = 20.sp,
		lineHeight = 28.sp
	),
	titleMedium = TextStyle(
		fontFamily = InterFontFamily,
		fontWeight = FontWeight.SemiBold,
		fontSize = 16.sp,
		lineHeight = 24.sp,
		letterSpacing = 0.1.sp
	),
	titleSmall = TextStyle(
		fontFamily = InterFontFamily,
		fontWeight = FontWeight.Medium,
		fontSize = 14.sp,
		lineHeight = 20.sp,
		letterSpacing = 0.1.sp
	),
	bodyLarge = TextStyle(
		fontFamily = InterFontFamily,
		fontWeight = FontWeight.Normal,
		fontSize = 16.sp,
		lineHeight = 24.sp,
		letterSpacing = 0.15.sp
	),
	bodyMedium = TextStyle(
		fontFamily = InterFontFamily,
		fontWeight = FontWeight.Normal,
		fontSize = 14.sp,
		lineHeight = 20.sp,
		letterSpacing = 0.2.sp
	),
	bodySmall = TextStyle(
		fontFamily = InterFontFamily,
		fontWeight = FontWeight.Normal,
		fontSize = 12.sp,
		lineHeight = 16.sp,
		letterSpacing = 0.3.sp
	),
	labelLarge = TextStyle(
		fontFamily = InterFontFamily,
		fontWeight = FontWeight.SemiBold,
		fontSize = 14.sp,
		lineHeight = 20.sp,
		letterSpacing = 0.1.sp
	),
	labelMedium = TextStyle(
		fontFamily = InterFontFamily,
		fontWeight = FontWeight.Medium,
		fontSize = 12.sp,
		lineHeight = 16.sp,
		letterSpacing = 0.4.sp
	),
	labelSmall = TextStyle(
		fontFamily = InterFontFamily,
		fontWeight = FontWeight.Medium,
		fontSize = 11.sp,
		lineHeight = 14.sp,
		letterSpacing = 0.5.sp
	)
)

// Tabular numerals for amounts/balances - keeps digits aligned in lists.
val TabularNumStyle = TextStyle(
	fontFeatureSettings = "tnum"
)
