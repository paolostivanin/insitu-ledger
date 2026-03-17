package com.insituledger.app.ui.categories

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.domain.model.Category
import com.insituledger.app.ui.common.EmptyState
import com.insituledger.app.ui.common.LoadingIndicator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    onBack: () -> Unit,
    onAddClick: () -> Unit,
    onEditClick: (Long) -> Unit,
    viewModel: CategoriesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Categories") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddClick) {
                Icon(Icons.Default.Add, contentDescription = "Add Category")
            }
        }
    ) { padding ->
        when {
            uiState.isLoading -> LoadingIndicator(modifier = Modifier.padding(padding))
            uiState.incomeCategories.isEmpty() && uiState.expenseCategories.isEmpty() ->
                EmptyState("No categories", modifier = Modifier.padding(padding))
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (uiState.expenseCategories.isNotEmpty()) {
                        item {
                            Text("Expense", style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
                        }
                        items(uiState.expenseCategories, key = { it.id }) { cat ->
                            CategoryRow(cat, onEdit = { onEditClick(cat.id) }, onDelete = { viewModel.delete(cat.id) })
                        }
                    }
                    if (uiState.incomeCategories.isNotEmpty()) {
                        item {
                            Text("Income", style = MaterialTheme.typography.titleSmall,
                                color = Color(0xFF2E7D32), modifier = Modifier.padding(vertical = 8.dp))
                        }
                        items(uiState.incomeCategories, key = { it.id }) { cat ->
                            CategoryRow(cat, onEdit = { onEditClick(cat.id) }, onDelete = { viewModel.delete(cat.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(category: Category, onEdit: () -> Unit, onDelete: () -> Unit) {
    val indent = if (category.parentId != null) 24.dp else 0.dp
    Card(modifier = Modifier.fillMaxWidth().padding(start = indent)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                category.color?.let { colorStr ->
                    val parsedColor = try { Color(android.graphics.Color.parseColor(colorStr)) } catch (_: Exception) { null }
                    if (parsedColor != null) {
                        Surface(modifier = Modifier.size(12.dp), shape = MaterialTheme.shapes.extraSmall, color = parsedColor) {}
                    }
                }
                Text(category.name, style = MaterialTheme.typography.bodyMedium)
            }
            Row {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
