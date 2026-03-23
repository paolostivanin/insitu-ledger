package com.insituledger.app.ui.categories

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.ui.common.IncomeExpenseToggle
import com.insituledger.app.ui.common.LoadingIndicator

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CategoryFormScreen(
    onBack: () -> Unit,
    viewModel: CategoryFormViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.saved) { if (uiState.saved) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.id != null) "Edit Category" else "New Category") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            LoadingIndicator(modifier = Modifier.padding(padding))
            return@Scaffold
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            IncomeExpenseToggle(selected = uiState.type, onSelect = viewModel::updateType)

            OutlinedTextField(value = uiState.name, onValueChange = viewModel::updateName,
                label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            // Parent category selector
            var parentExpanded by remember { mutableStateOf(false) }
            val parentCats = uiState.allCategories.filter { it.type == uiState.type && it.id != uiState.id }
            ExposedDropdownMenuBox(expanded = parentExpanded, onExpandedChange = { parentExpanded = it }) {
                OutlinedTextField(
                    value = uiState.allCategories.find { it.id == uiState.parentId }?.name ?: "None (top-level)",
                    onValueChange = {}, readOnly = true, label = { Text("Parent Category") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = parentExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(expanded = parentExpanded, onDismissRequest = { parentExpanded = false }) {
                    DropdownMenuItem(text = { Text("None (top-level)") },
                        onClick = { viewModel.updateParentId(null); parentExpanded = false })
                    parentCats.forEach { cat ->
                        DropdownMenuItem(text = { Text(cat.name) },
                            onClick = { viewModel.updateParentId(cat.id); parentExpanded = false })
                    }
                }
            }

            OutlinedTextField(value = uiState.icon, onValueChange = viewModel::updateIcon,
                label = { Text("Icon (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            ColorPickerSection(selectedColor = uiState.color, onColorChange = viewModel::updateColor)

            uiState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth().height(48.dp), enabled = !uiState.isSaving) {
                if (uiState.isSaving) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                else Text(if (uiState.id != null) "Update" else "Create")
            }
        }
    }
}

private val presetColors = listOf(
    "#F44336", "#E91E63", "#9C27B0", "#673AB7",
    "#3F51B5", "#2196F3", "#03A9F4", "#00BCD4",
    "#009688", "#4CAF50", "#8BC34A", "#CDDC39",
    "#FFC107", "#FF9800", "#FF5722", "#795548"
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPickerSection(selectedColor: String, onColorChange: (String) -> Unit) {
    var showCustomPicker by remember { mutableStateOf(false) }
    val initialHsv = remember(selectedColor) { hexToHsv(selectedColor) }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Color", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Preset colors
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presetColors.forEach { hex ->
                val color = try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { Color.Gray }
                val isSelected = !showCustomPicker && selectedColor.equals(hex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(color)
                        .then(
                            if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        )
                        .clickable {
                            showCustomPicker = false
                            onColorChange(hex)
                        },
                    contentAlignment = Alignment.Center
                ) {}
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Tappable text to toggle color picker
        Text(
            "Custom color",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { showCustomPicker = !showCustomPicker }
        )

        if (!showCustomPicker) return@Column

        // Hue bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        hue = (offset.x / size.width).coerceIn(0f, 1f) * 360f
                        showCustomPicker = true
                        onColorChange(hsvToHex(hue, saturation, value))
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        hue = (change.position.x / size.width).coerceIn(0f, 1f) * 360f
                        showCustomPicker = true
                        onColorChange(hsvToHex(hue, saturation, value))
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val hueColors = listOf(
                    Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red
                )
                drawRect(brush = Brush.horizontalGradient(hueColors))
                // Indicator
                val indicatorX = (hue / 360f) * size.width
                drawCircle(
                    color = Color.White,
                    radius = 14f,
                    center = Offset(indicatorX, size.height / 2f),
                    style = Stroke(width = 3f)
                )
                drawCircle(
                    color = Color.Black,
                    radius = 14f,
                    center = Offset(indicatorX, size.height / 2f),
                    style = Stroke(width = 1f)
                )
            }
        }

        // Saturation-Value square
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        saturation = (offset.x / size.width).coerceIn(0f, 1f)
                        value = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                        showCustomPicker = true
                        onColorChange(hsvToHex(hue, saturation, value))
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        saturation = (change.position.x / size.width).coerceIn(0f, 1f)
                        value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        showCustomPicker = true
                        onColorChange(hsvToHex(hue, saturation, value))
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val hueColor = Color.hsv(hue, 1f, 1f)
                // White to hue color (horizontal = saturation)
                drawRect(brush = Brush.horizontalGradient(listOf(Color.White, hueColor)))
                // Transparent to black (vertical = value)
                drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                // Indicator
                val ix = saturation * size.width
                val iy = (1f - value) * size.height
                drawCircle(
                    color = Color.White,
                    radius = 12f,
                    center = Offset(ix, iy),
                    style = Stroke(width = 3f)
                )
                drawCircle(
                    color = Color.Black,
                    radius = 12f,
                    center = Offset(ix, iy),
                    style = Stroke(width = 1f)
                )
            }
        }

        // Preview of custom color
        if (showCustomPicker) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val customColor = try { Color(android.graphics.Color.parseColor(selectedColor)) } catch (_: Exception) { Color.Gray }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(customColor)
                        .border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                )
                Text(
                    selectedColor.uppercase(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
