package com.insituledger.app.ui.theme

import androidx.compose.ui.unit.dp

/** 4-pt spacing scale plus a few layout constants. */
object AppSpacing {
	val xs = 4.dp
	val sm = 8.dp
	val md = 12.dp
	val lg = 16.dp
	val xl = 24.dp
	val xxl = 32.dp

	/** Horizontal screen margin on phones. */
	val screenH = 16.dp

	/** Minimum height of a single-line interactive row. */
	val rowMinHeight = 56.dp

	/** Bottom content padding so the last row clears a 56dp FAB + margin. */
	val fabClearance = 88.dp

	/** Content is capped at this width on large screens. */
	val maxContentWidth = 640.dp

}
