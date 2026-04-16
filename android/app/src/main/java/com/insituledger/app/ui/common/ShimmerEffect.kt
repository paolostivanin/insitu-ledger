package com.insituledger.app.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.insituledger.app.ui.theme.AppSpacing

@Composable
fun ShimmerBox(modifier: Modifier = Modifier) {
	val shimmerColors = listOf(
		MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
		MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
		MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
	)
	val transition = rememberInfiniteTransition(label = "shimmer")
	val translateAnim by transition.animateFloat(
		initialValue = 0f,
		targetValue = 1000f,
		animationSpec = infiniteRepeatable(tween(durationMillis = 1200, easing = LinearEasing)),
		label = "shimmer"
	)
	val brush = Brush.linearGradient(
		colors = shimmerColors,
		start = Offset(translateAnim - 200f, 0f),
		end = Offset(translateAnim, 0f)
	)
	Box(modifier = modifier.background(brush, shape = MaterialTheme.shapes.small))
}

@Composable
fun DashboardSkeleton(modifier: Modifier = Modifier) {
	Column(
		modifier = modifier
			.fillMaxSize()
			.padding(AppSpacing.screenPadding),
		verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
	) {
		// Net worth card
		ShimmerBox(
			modifier = Modifier
				.fillMaxWidth()
				.height(88.dp)
		)
		// Income/Expense cards
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
		) {
			ShimmerBox(
				modifier = Modifier
					.weight(1f)
					.height(72.dp)
			)
			ShimmerBox(
				modifier = Modifier
					.weight(1f)
					.height(72.dp)
			)
		}
		// Monthly net
		ShimmerBox(
			modifier = Modifier
				.fillMaxWidth()
				.height(52.dp)
		)
		// Section header
		ShimmerBox(
			modifier = Modifier
				.width(160.dp)
				.height(20.dp)
		)
		// Transaction items
		repeat(5) {
			ShimmerBox(
				modifier = Modifier
					.fillMaxWidth()
					.height(56.dp)
			)
		}
	}
}

@Composable
fun TransactionListSkeleton(modifier: Modifier = Modifier) {
	Column(
		modifier = modifier
			.fillMaxSize()
			.padding(AppSpacing.screenPadding),
		verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
	) {
		// Sort bar
		ShimmerBox(
			modifier = Modifier
				.fillMaxWidth()
				.height(40.dp)
		)
		// Date header
		ShimmerBox(
			modifier = Modifier
				.width(120.dp)
				.height(16.dp)
		)
		// Transaction items
		repeat(8) {
			ShimmerBox(
				modifier = Modifier
					.fillMaxWidth()
					.height(60.dp)
			)
		}
	}
}
