package com.insituledger.app.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.domain.model.Category
import com.insituledger.app.ui.common.EmptyState
import com.insituledger.app.ui.common.LoadingIndicator

private fun parseColor(hex: String?): Color? {
    if (hex == null) return null
    return try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { null }
}

private data class CategoryNode(
    val category: Category,
    val children: List<Category>
)

private fun buildTree(categories: List<Category>): List<CategoryNode> {
    val parents = categories.filter { it.parentId == null }
    val childMap = categories.filter { it.parentId != null }.groupBy { it.parentId }
    return parents.map { parent ->
        CategoryNode(parent, childMap[parent.id] ?: emptyList())
    }
}

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
            if (!uiState.isReadOnly) {
                FloatingActionButton(onClick = onAddClick) {
                    Icon(Icons.Default.Add, contentDescription = "Add Category")
                }
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
                            Text(
                                "Expense",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        val expenseTree = buildTree(uiState.expenseCategories)
                        items(expenseTree, key = { "expense_${it.category.id}" }) { node ->
                            CategoryTreeNode(
                                node = node,
                                isReadOnly = uiState.isReadOnly,
                                onEdit = onEditClick,
                                onDelete = viewModel::delete
                            )
                        }
                    }
                    if (uiState.incomeCategories.isNotEmpty()) {
                        item {
                            Text(
                                "Income",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color(0xFF2E7D32),
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        val incomeTree = buildTree(uiState.incomeCategories)
                        items(incomeTree, key = { "income_${it.category.id}" }) { node ->
                            CategoryTreeNode(
                                node = node,
                                isReadOnly = uiState.isReadOnly,
                                onEdit = onEditClick,
                                onDelete = viewModel::delete
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryTreeNode(
    node: CategoryNode,
    isReadOnly: Boolean,
    onEdit: (Long) -> Unit,
    onDelete: (Long) -> Unit
) {
    // Parent row
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                parseColor(node.category.color)?.let { color ->
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                }
                Text(
                    node.category.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (!isReadOnly) {
                Row {
                    IconButton(onClick = { onEdit(node.category.id) }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { onDelete(node.category.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }

    // Children rows (indented, lighter style)
    if (node.children.isNotEmpty()) {
        Column(modifier = Modifier.padding(start = 32.dp, top = 2.dp, bottom = 4.dp)) {
            node.children.forEach { child ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            parseColor(child.color)?.let { color ->
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                )
                            }
                            Text(
                                child.name,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (!isReadOnly) {
                            Row {
                                IconButton(onClick = { onEdit(child.id) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp))
                                }
                                IconButton(onClick = { onDelete(child.id) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
