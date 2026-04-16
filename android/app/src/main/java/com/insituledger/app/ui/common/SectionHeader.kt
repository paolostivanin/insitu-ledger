package com.insituledger.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.insituledger.app.ui.theme.AppSpacing

@Composable
fun SectionHeader(
	title: String,
	modifier: Modifier = Modifier,
	action: (@Composable () -> Unit)? = null
) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.padding(top = AppSpacing.sectionGap, bottom = AppSpacing.sm),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically
	) {
		Text(
			text = title,
			style = MaterialTheme.typography.titleMedium
		)
		action?.invoke()
	}
}
