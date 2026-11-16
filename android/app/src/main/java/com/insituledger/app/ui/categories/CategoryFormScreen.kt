package com.insituledger.app.ui.categories

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.ui.common.CategoryDot
import com.insituledger.app.ui.common.ColorUtils
import com.insituledger.app.ui.common.FormActionBar
import com.insituledger.app.ui.common.LoadingIndicator
import com.insituledger.app.ui.common.LocalContentPadding
import com.insituledger.app.ui.common.SectionHeader
import com.insituledger.app.ui.common.TypeToggle
import com.insituledger.app.ui.common.displayName
import com.insituledger.app.ui.theme.AppSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryFormScreen(
    onBack: () -> Unit,
    onSaved: (message: String) -> Unit = { onBack() },
    viewModel: CategoryFormViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val focusManager = LocalFocusManager.current
    val pad = LocalContentPadding.current
    val isEdit = uiState.id != null

    LaunchedEffect(uiState.saved) { if (uiState.saved) onSaved(if (isEdit) "Category updated" else "Category created") }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) "Edit category" else "New category") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            if (!uiState.isLoading) {
                FormActionBar(
                    label = if (isEdit) "Save changes" else "Create category",
                    loading = uiState.isSaving,
                    onClick = viewModel::save
                )
            }
        }
    ) { padding ->
        if (uiState.isLoading) {
            LoadingIndicator(modifier = Modifier.padding(padding))
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = pad, vertical = AppSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            TypeToggle(selected = uiState.type, onSelect = viewModel::updateType)

            OutlinedTextField(
                value = uiState.name,
                onValueChange = viewModel::updateName,
                label = { Text("Name") },
                singleLine = true,
                isError = uiState.nameError != null,
                supportingText = uiState.nameError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth()
            )

            ParentSelector(uiState = uiState, onSelect = viewModel::updateParentId)

            OutlinedTextField(
                value = uiState.icon,
                onValueChange = viewModel::updateIcon,
                label = { Text("Icon (emoji)") },
                supportingText = { Text("Shown before the name, e.g. 🛒") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth()
            )

            ColorPickerSection(selectedColor = uiState.color, onColorChange = viewModel::updateColor)

            uiState.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParentSelector(uiState: CategoryFormUiState, onSelect: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val hasChildren = uiState.hasSubcategories
    // A category with subcategories stays top-level. Legacy data can still
    // have one filed under a parent; then the only move offered is to the top.
    val enabled = !hasChildren || uiState.parentId != null
    val options = if (hasChildren) emptyList() else uiState.parentOptions
    val parent = uiState.allCategories.find { it.id == uiState.parentId }
    val supporting = when {
        hasChildren && uiState.parentId == null -> "Has subcategories, so it stays top-level"
        hasChildren -> "Has subcategories, so it can only be moved to the top level"
        else -> null
    }

    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it }
    ) {
        OutlinedTextField(
            value = parent?.displayName() ?: "None (top-level)",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Parent category") },
            leadingIcon = parent?.let { p -> { CategoryDot(p.color?.let { ColorUtils.parseHex(it) }, size = 12.dp) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            supportingText = supporting?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled)
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            ParentOption(
                label = "None (top-level)",
                color = null,
                selected = uiState.parentId == null,
                onClick = { onSelect(null); expanded = false }
            )
            options.forEach { cat ->
                ParentOption(
                    label = cat.displayName(),
                    color = cat.color?.let { ColorUtils.parseHex(it) },
                    selected = cat.id == uiState.parentId,
                    onClick = { onSelect(cat.id); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun ParentOption(label: String, color: Color?, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = if (color != null) {
            { CategoryDot(color, size = 12.dp) }
        } else null,
        trailingIcon = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = "Selected") }
        } else null,
        onClick = onClick
    )
}

/** Preset swatches with the names TalkBack reads out. */
private val presetColors = listOf(
    "#F44336" to "Red",
    "#E91E63" to "Pink",
    "#9C27B0" to "Purple",
    "#673AB7" to "Deep purple",
    "#3F51B5" to "Indigo",
    "#2196F3" to "Blue",
    "#03A9F4" to "Light blue",
    "#00BCD4" to "Cyan",
    "#009688" to "Teal",
    "#4CAF50" to "Green",
    "#8BC34A" to "Light green",
    "#CDDC39" to "Lime",
    "#FFC107" to "Amber",
    "#FF9800" to "Orange",
    "#FF5722" to "Deep orange",
    "#795548" to "Brown"
)

private fun hexToHsv(hex: String): FloatArray {
    val hsv = FloatArray(3)
    try {
        android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(hex), hsv)
    } catch (_: Exception) {
        hsv[0] = 0f; hsv[1] = 1f; hsv[2] = 1f
    }
    return hsv
}

private fun hsvToHex(h: Float, s: Float, v: Float): String {
    val color = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
    return "#%06X".format(0xFFFFFF and color)
}

/**
 * Check-mark colour on a swatch: whichever of white or black contrasts more
 * with the fill (WCAG relative luminance; the crossover is L ≈ 0.18).
 */
private fun checkColorOn(fill: Color): Color = if (fill.luminance() > 0.179f) Color.Black else Color.White

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPickerSection(selectedColor: String, onColorChange: (String) -> Unit) {
    val isPreset = presetColors.any { it.first.equals(selectedColor, ignoreCase = true) }
    val isCustom = selectedColor.isNotBlank() && !isPreset
    var showCustomPicker by rememberSaveable { mutableStateOf(isCustom) }
    val initialHsv = remember { hexToHsv(selectedColor.ifBlank { "#FF0000" }) }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }

    val density = LocalDensity.current
    val hueIndicatorRadius = with(density) { 10.dp.toPx() }
    val svIndicatorRadius = with(density) { 9.dp.toPx() }
    val outerStroke = with(density) { 2.dp.toPx() }
    val innerStroke = with(density) { 1.dp.toPx() }

    fun pickCustom() = onColorChange(hsvToHex(hue, saturation, value))

    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        SectionHeader("Color", horizontalPadding = 0.dp, topPadding = AppSpacing.sm)

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
            modifier = Modifier.selectableGroup()
        ) {
            presetColors.forEach { (hex, name) ->
                ColorSwatch(
                    color = ColorUtils.parseHex(hex),
                    name = name,
                    selected = selectedColor.equals(hex, ignoreCase = true),
                    onClick = { onColorChange(hex) }
                )
            }
            if (isCustom) {
                ColorSwatch(
                    color = ColorUtils.parseHex(selectedColor),
                    name = "Custom color ${selectedColor.uppercase()}",
                    selected = true,
                    onClick = { showCustomPicker = true }
                )
            }
        }

        TextButton(onClick = { showCustomPicker = !showCustomPicker }) {
            Text(if (showCustomPicker) "Hide custom color" else "Custom color")
        }

        if (!showCustomPicker) return@Column

        // Hue bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(MaterialTheme.shapes.small)
                .semantics {
                    contentDescription = "Hue"
                    stateDescription = "${hue.toInt()} degrees"
                    progressBarRangeInfo = ProgressBarRangeInfo(hue, 0f..360f)
                    setProgress { target ->
                        hue = target.coerceIn(0f, 360f)
                        pickCustom()
                        true
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        hue = (offset.x / size.width).coerceIn(0f, 1f) * 360f
                        pickCustom()
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        hue = (change.position.x / size.width).coerceIn(0f, 1f) * 360f
                        pickCustom()
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val hueColors = listOf(
                    Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red
                )
                drawRect(brush = Brush.horizontalGradient(hueColors))
                val center = Offset((hue / 360f) * size.width, size.height / 2f)
                drawCircle(Color.White, radius = hueIndicatorRadius, center = center, style = Stroke(width = outerStroke))
                drawCircle(Color.Black, radius = hueIndicatorRadius + outerStroke / 2f, center = center, style = Stroke(width = innerStroke))
            }
        }

        // Saturation / brightness square
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(MaterialTheme.shapes.small)
                .semantics { contentDescription = "Saturation and brightness" }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        saturation = (offset.x / size.width).coerceIn(0f, 1f)
                        value = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                        pickCustom()
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        saturation = (change.position.x / size.width).coerceIn(0f, 1f)
                        value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        pickCustom()
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val hueColor = Color.hsv(hue, 1f, 1f)
                drawRect(brush = Brush.horizontalGradient(listOf(Color.White, hueColor)))
                drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                val center = Offset(saturation * size.width, (1f - value) * size.height)
                drawCircle(Color.White, radius = svIndicatorRadius, center = center, style = Stroke(width = outerStroke))
                drawCircle(Color.Black, radius = svIndicatorRadius + outerStroke / 2f, center = center, style = Stroke(width = innerStroke))
            }
        }

        if (selectedColor.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
                modifier = Modifier.semantics(mergeDescendants = true) {}
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(ColorUtils.parseHex(selectedColor))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                )
                Text(
                    selectedColor.uppercase(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** A 40dp colour circle in a 48dp touch target, announced as a radio button. */
@Composable
private fun ColorSwatch(color: Color, name: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape
                )
        ) {
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = null, tint = checkColorOn(color))
            }
        }
    }
}
