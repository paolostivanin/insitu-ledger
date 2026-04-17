package com.insituledger.app.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.domain.model.Category
import com.insituledger.app.ui.common.AppCard
import com.insituledger.app.ui.common.ColorUtils
import com.insituledger.app.ui.common.EmptyState
import com.insituledger.app.ui.common.LoadingIndicator
import com.insituledger.app.ui.common.LocalSnackbarHostState
import com.insituledger.app.ui.common.SectionHeader
import com.insituledger.app.ui.theme.AppSpacing
import com.insituledger.app.ui.theme.BrandGradients
import kotlinx.coroutines.launch

private data class CategoryNode(
    val category: Category,
    val children: List<Category>
)

private enum class CategoryFilter { ALL, EXPENSE, INCOME }

private fun buildTree(categories: List<Category>): List<CategoryNode> {
    val parents = categories.filter { it.parentId == null }
    val childMap = categories.filter { it.parentId != null }.groupBy { it.parentId }
    return parents.map { parent ->
        CategoryNode(parent, childMap[parent.id] ?: emptyList())
    }
}

private fun List<CategoryNode>.totalCount(): Int = sumOf { 1 + it.children.size }

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
    val haptics = LocalHapticFeedback.current
    val onDelete: (Long) -> Unit = { id ->
        viewModel.delete(id)
        scope.launch { snackbarHostState.showSnackbar("Category deleted") }
    }
    var filter by remember { mutableStateOf(CategoryFilter.ALL) }

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
                BrandFab(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onAddClick()
                    },
                    contentDescription = "Add Category"
                )
            }
        }
    ) { padding ->
        when {
            uiState.isLoading -> LoadingIndicator(modifier = Modifier.padding(padding))
            uiState.incomeCategories.isEmpty() && uiState.expenseCategories.isEmpty() -> EmptyState(
                icon = Icons.Default.Category,
                title = "No categories yet",
                message = "Create categories to organize your transactions.",
                actionLabel = if (!uiState.isReadOnly) "Add category" else null,
                onAction = if (!uiState.isReadOnly) onAddClick else null,
                modifier = Modifier.padding(padding)
            )
            else -> {
                val expenseTree = remember(uiState.expenseCategories) { buildTree(uiState.expenseCategories) }
                val incomeTree = remember(uiState.incomeCategories) { buildTree(uiState.incomeCategories) }
                val showExpense = filter == CategoryFilter.ALL || filter == CategoryFilter.EXPENSE
                val showIncome = filter == CategoryFilter.ALL || filter == CategoryFilter.INCOME

                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(
                        start = AppSpacing.screenPadding,
                        end = AppSpacing.screenPadding,
                        top = AppSpacing.sm,
                        bottom = AppSpacing.xxl
                    ),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)
                ) {
                    item(key = "filter") {
                        FilterRow(
                            selected = filter,
                            onSelect = { filter = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = AppSpacing.sm)
                        )
                    }

                    if (showExpense && expenseTree.isNotEmpty()) {
                        item(key = "expense_header") {
                            SectionHeader(
                                title = "EXPENSE · ${expenseTree.totalCount()}",
                                modifier = Modifier.animateItem()
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
                    if (showIncome && incomeTree.isNotEmpty()) {
                        item(key = "income_header") {
                            SectionHeader(
                                title = "INCOME · ${incomeTree.totalCount()}",
                                modifier = Modifier.animateItem()
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
private fun FilterRow(
    selected: CategoryFilter,
    onSelect: (CategoryFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
    ) {
        FilterChipItem(label = "All", selected = selected == CategoryFilter.ALL) {
            onSelect(CategoryFilter.ALL)
        }
        FilterChipItem(label = "Expense", selected = selected == CategoryFilter.EXPENSE) {
            onSelect(CategoryFilter.EXPENSE)
        }
        FilterChipItem(label = "Income", selected = selected == CategoryFilter.INCOME) {
            onSelect(CategoryFilter.INCOME)
        }
    }
}

@Composable
private fun FilterChipItem(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) }
    )
}

@Composable
private fun CategoryParentRow(
    category: Category,
    isReadOnly: Boolean,
    onEdit: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = if (category.color != null) ColorUtils.parseHex(category.color) else MaterialTheme.colorScheme.primary
    AppCard(modifier = modifier.fillMaxWidth(), level = 1) {
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accent)
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CategoryDot(category.color, size = 20.dp, dotSize = 8.dp)
                    Text(
                        category.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (!isReadOnly) {
                    OverflowMenu(
                        onEdit = { onEdit(category.id) },
                        onDelete = { onDelete(category.id) }
                    )
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.lg)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
        Spacer(modifier = Modifier.width(AppSpacing.sm))
        AppCard(modifier = Modifier.weight(1f), level = 0) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.md, vertical = AppSpacing.xxs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CategoryDot(child.color, size = 14.dp, dotSize = 6.dp)
                    Text(
                        child.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (!isReadOnly) {
                    OverflowMenu(
                        onEdit = { onEdit(child.id) },
                        onDelete = { onDelete(child.id) },
                        compact = true
                    )
                }
            }
        }
    }
}

@Composable
private fun OverflowMenu(
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    compact: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = if (compact) Modifier.size(32.dp) else Modifier
        ) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "More",
                modifier = Modifier.size(if (compact) 18.dp else 20.dp)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Edit") },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = {
                    expanded = false
                    onEdit()
                }
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                onClick = {
                    expanded = false
                    onDelete()
                }
            )
        }
    }
}

@Composable
private fun CategoryDot(hexColor: String?, size: androidx.compose.ui.unit.Dp, dotSize: androidx.compose.ui.unit.Dp) {
    val color = if (hexColor != null) ColorUtils.parseHex(hexColor) else MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(dotSize)
                .clip(CircleShape)
                .background(color)
        )
    }
}

@Composable
private fun BrandFab(onClick: () -> Unit, contentDescription: String) {
    val gradient = BrandGradients.hero()
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        shadowElevation = 8.dp,
        modifier = Modifier.size(64.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
