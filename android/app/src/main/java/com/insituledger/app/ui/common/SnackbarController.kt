package com.insituledger.app.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp

/**
 * Shows snackbars from the navigation-level scope, so a confirmation keeps
 * showing after the screen that triggered it has been popped.
 */
class SnackbarController(
    private val hostState: SnackbarHostState,
    private val scope: CoroutineScope
) {
    fun show(
        message: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        scope.launch {
            hostState.currentSnackbarData?.dismiss()
            val result = hostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }
}

val LocalSnackbarController = staticCompositionLocalOf<SnackbarController> {
    error("No SnackbarController provided")
}

/**
 * Horizontal padding for screen content. 16dp on phones; wider on large
 * screens so content stays within AppSpacing.maxContentWidth.
 */
val LocalContentPadding = staticCompositionLocalOf { 16.dp }
