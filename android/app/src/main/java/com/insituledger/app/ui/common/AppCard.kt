package com.insituledger.app.ui.common

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AppCard(
	modifier: Modifier = Modifier,
	onClick: (() -> Unit)? = null,
	content: @Composable ColumnScope.() -> Unit
) {
	if (onClick != null) {
		ElevatedCard(
			onClick = onClick,
			modifier = modifier,
			elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
			content = content
		)
	} else {
		ElevatedCard(
			modifier = modifier,
			elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
			content = content
		)
	}
}
