package com.insituledger.app.ui.common

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AppCard(
	modifier: Modifier = Modifier,
	onClick: (() -> Unit)? = null,
	level: Int = 1,
	content: @Composable ColumnScope.() -> Unit
) {
	val elev = when (level) {
		0 -> 0.dp
		1 -> 1.dp
		2 -> 3.dp
		3 -> 6.dp
		else -> 1.dp
	}
	val container = when (level) {
		0 -> MaterialTheme.colorScheme.surfaceContainerLow
		1 -> MaterialTheme.colorScheme.surfaceContainerLow
		2 -> MaterialTheme.colorScheme.surfaceContainer
		else -> MaterialTheme.colorScheme.surfaceContainerHigh
	}
	if (onClick != null) {
		ElevatedCard(
			onClick = onClick,
			modifier = modifier,
			elevation = CardDefaults.elevatedCardElevation(defaultElevation = elev),
			colors = CardDefaults.elevatedCardColors(containerColor = container),
			content = content
		)
	} else {
		ElevatedCard(
			modifier = modifier,
			elevation = CardDefaults.elevatedCardElevation(defaultElevation = elev),
			colors = CardDefaults.elevatedCardColors(containerColor = container),
			content = content
		)
	}
}
