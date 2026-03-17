package com.insituledger.app.ui.categories

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = uiState.type == "expense", onClick = { viewModel.updateType("expense") },
                    label = { Text("Expense") }, modifier = Modifier.weight(1f))
                FilterChip(selected = uiState.type == "income", onClick = { viewModel.updateType("income") },
                    label = { Text("Income") }, modifier = Modifier.weight(1f))
            }

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
                    modifier = Modifier.fillMaxWidth().menuAnchor()
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

            OutlinedTextField(value = uiState.color, onValueChange = viewModel::updateColor,
                label = { Text("Color (e.g. #FF5733)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

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
