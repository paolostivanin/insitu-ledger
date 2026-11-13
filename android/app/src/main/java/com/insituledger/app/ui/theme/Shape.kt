package com.insituledger.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * extraSmall: text fields, menus. small: chips, swatches. medium: grouped
 * blocks. large: dialogs. extraLarge: bottom-sheet top corners.
 */
val AppShapes = Shapes(
	extraSmall = RoundedCornerShape(6.dp),
	small = RoundedCornerShape(8.dp),
	medium = RoundedCornerShape(12.dp),
	large = RoundedCornerShape(16.dp),
	extraLarge = RoundedCornerShape(28.dp)
)
