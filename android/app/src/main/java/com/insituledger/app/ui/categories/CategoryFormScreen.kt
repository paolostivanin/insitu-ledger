package com.insituledger.app.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.ui.common.IncomeExpenseToggle
import com.insituledger.app.ui.common.LoadingIndicator

@OptIn(ExperimentalMaterial3Api::class)
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPickerSection(selectedColor: String, onColorChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Color", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presetColors.forEach { hex ->
                val color = try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { Color.Gray }
                val isSelected = selectedColor.equals(hex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(color)
                        .then(
                            if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        )
                        .clickable { onColorChange(hex) },
                    contentAlignment = Alignment.Center
                ) {}
            }
        }
        OutlinedTextField(
            value = selectedColor,
            onValueChange = onColorChange,
            label = { Text("Custom hex") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
