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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.insituledger.app.ui.theme.AppSpacing
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.domain.model.Category
import com.insituledger.app.ui.common.ColorUtils
import com.insituledger.app.ui.common.EmptyState
import com.insituledger.app.ui.common.LocalSnackbarHostState
import com.insituledger.app.ui.theme.LocalSemanticColors
import com.insituledger.app.ui.common.LoadingIndicator


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
    val snackbarHostState = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val onDelete: (Long) -> Unit = { id ->
        viewModel.delete(id)
        scope.launch { snackbarHostState.showSnackbar("Category deleted") }
    }

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
                val expenseTree = remember(uiState.expenseCategories) {
                    buildTree(uiState.expenseCategories)
                }
                val incomeTree = remember(uiState.incomeCategories) {
                    buildTree(uiState.incomeCategories)
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(AppSpacing.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                ) {
                    if (expenseTree.isNotEmpty()) {
                        item {
                            Text(
                                "Expense",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(vertical = AppSpacing.sm).animateItem()
                            )
                        }
                        expenseTree.forEach { node ->
                            item(key = "expense_${node.category.id}") {
                                CategoryParentRow(
                                    category = node.category,
                                    isReadOnly = uiState.isReadOnly,
                                    onEdit = onEditClick,
                                    onDelete = onDelete,
                                    modifier = Modifier.animateItem()
                                )
                            }
                            items(node.children, key = { "expense_child_${it.id}" }) { child ->
                                CategoryChildRow(
                                    child = child,
                                    isReadOnly = uiState.isReadOnly,
                                    onEdit = onEditClick,
                                    onDelete = onDelete,
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }
                    }
                    if (incomeTree.isNotEmpty()) {
                        item {
                            Text(
                                "Income",
                                style = MaterialTheme.typography.titleSmall,
                                color = LocalSemanticColors.current.income,
                                modifier = Modifier.padding(vertical = AppSpacing.sm).animateItem()
                            )
                        }
                        incomeTree.forEach { node ->
                            item(key = "income_${node.category.id}") {
                                CategoryParentRow(
                                    category = node.category,
                                    isReadOnly = uiState.isReadOnly,
                                    onEdit = onEditClick,
                                    onDelete = onDelete,
                                    modifier = Modifier.animateItem()
                                )
                            }
                            items(node.children, key = { "income_child_${it.id}" }) { child ->
                                CategoryChildRow(
                                    child = child,
                                    isReadOnly = uiState.isReadOnly,
                                    onEdit = onEditClick,
                                    onDelete = onDelete,
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryParentRow(
    category: Category,
    isReadOnly: Boolean,
    onEdit: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (category.color != null) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(ColorUtils.parseHex(category.color))
                    )
                }
                Text(
                    category.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (!isReadOnly) {
                Row {
                    IconButton(onClick = { onEdit(category.id) }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { onDelete(category.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryChildRow(
    child: Category,
    isReadOnly: Boolean,
    onEdit: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(start = AppSpacing.xxl, top = AppSpacing.xxs, bottom = AppSpacing.xxs),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (child.color != null) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(ColorUtils.parseHex(child.color))
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
