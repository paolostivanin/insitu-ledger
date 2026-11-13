package com.insituledger.app.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.insituledger.app.ui.theme.AppSpacing

/**
 * Quiet section label (titleSmall, onSurfaceVariant) with an optional
 * trailing text action such as "See all".
 */
@Composable
fun SectionHeader(
	title: String,
	modifier: Modifier = Modifier,
	horizontalPadding: Dp = AppSpacing.screenH,
	topPadding: Dp = AppSpacing.xl,
	actionLabel: String? = null,
	onAction: (() -> Unit)? = null
) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.heightIn(min = 40.dp)
			.padding(
				start = horizontalPadding,
				end = if (actionLabel != null) horizontalPadding - AppSpacing.md else horizontalPadding,
				top = topPadding,
				bottom = AppSpacing.xs
			),
		verticalAlignment = Alignment.CenterVertically
	) {
		Text(
			text = title,
			style = MaterialTheme.typography.titleSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f).semantics { heading() }
		)
		if (actionLabel != null && onAction != null) {
			Spacer(Modifier.padding(start = AppSpacing.sm))
			TextButton(onClick = onAction) { Text(actionLabel) }
		}
	}
}
