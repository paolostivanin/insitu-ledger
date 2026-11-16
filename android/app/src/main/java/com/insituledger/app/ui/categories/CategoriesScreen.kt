package com.insituledger.app.ui.categories

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.data.repository.CategoryDeleteResult
import com.insituledger.app.domain.model.Category
import com.insituledger.app.ui.common.AddFab
import com.insituledger.app.ui.common.CategoryDot
import com.insituledger.app.ui.common.ColorUtils
import com.insituledger.app.ui.common.ConfirmDialog
import com.insituledger.app.ui.common.EmptyState
import com.insituledger.app.ui.common.ListRow
import com.insituledger.app.ui.common.LoadingIndicator
import com.insituledger.app.ui.common.LocalContentPadding
import com.insituledger.app.ui.common.LocalSnackbarController
import com.insituledger.app.ui.common.RowAction
import com.insituledger.app.ui.common.SectionHeader
import com.insituledger.app.ui.common.displayName
import com.insituledger.app.ui.theme.AppSpacing

/**
 * A top-level category and everything filed beneath it. [descendants] is
 * depth-first, so a grandchild follows its own parent.
 */
internal data class CategoryNode(
    val category: Category,
    val descendants: List<Category>
)

/**
 * Two-level tree for the list. Every category appears exactly once:
 * - a category whose parent is missing from [categories] (deleted, or of the
 *   other type) is shown as top-level instead of vanishing;
 * - deeper descendants (legacy grandchildren) are listed under their nearest
 *   top-level ancestor, still one level in;
 * - categories caught in a parent cycle are promoted to top-level.
 *
 * Pure and top-level so it can be tested without Hilt.
 */
internal fun buildCategoryTree(categories: List<Category>): List<CategoryNode> {
    val ids = categories.mapTo(HashSet()) { it.id }
    fun isRoot(c: Category) = c.parentId == null || c.parentId == c.id || c.parentId !in ids
    val childrenOf = categories.filterNot(::isRoot).groupBy { it.parentId }
    val placed = HashSet<Long>()

    fun collect(parentId: Long, into: MutableList<Category>) {
        childrenOf[parentId].orEmpty().forEach { child ->
            if (placed.add(child.id)) {
                into += child
                collect(child.id, into)
            }
        }
    }

    fun node(root: Category): CategoryNode {
        val descendants = mutableListOf<Category>()
        collect(root.id, descendants)
        return CategoryNode(root, descendants)
    }

    val nodes = mutableListOf<CategoryNode>()
    categories.filter(::isRoot).forEach { if (placed.add(it.id)) nodes += node(it) }
    // Whatever is left only reaches itself through its parents: a cycle.
    categories.forEach { if (placed.add(it.id)) nodes += node(it) }
    return nodes
}

private enum class CategoryFilter(val label: String) { ALL("All"), EXPENSE("Expense"), INCOME("Income") }

/** Extra start inset for subcategory rows: lines their dot up with the parent's name. */
private val ChildIndent = 28.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    onBack: () -> Unit,
    onAddClick: () -> Unit,
    onEditClick: (Long) -> Unit,
    viewModel: CategoriesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarController.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val pad = LocalContentPadding.current
    var filter by rememberSaveable { mutableStateOf(CategoryFilter.ALL) }
    var deleteTarget by remember { mutableStateOf<Category?>(null) }

    // Show the snackbar AFTER the repository reports back so a delete that
    // can't proceed (live transactions / scheduled txns) surfaces the real
    // reason instead of a lying "Category deleted".
    LaunchedEffect(viewModel) {
        viewModel.deleteEvents.collect { result ->
            val msg = when (result) {
                CategoryDeleteResult.Success -> "Category deleted"
                CategoryDeleteResult.NotFound -> "Category not found"
                CategoryDeleteResult.HasTransactions -> "Can't delete: the category has transactions"
                CategoryDeleteResult.HasScheduled -> "Can't delete: the category has schedules"
            }
            snackbar.show(msg)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Categories") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = { AddFab(onClick = onAddClick, contentDescription = "Add category") }
    ) { padding ->
        when {
            uiState.isLoading -> LoadingIndicator(modifier = Modifier.padding(padding))
            uiState.incomeCategories.isEmpty() && uiState.expenseCategories.isEmpty() -> EmptyState(
                icon = Icons.Outlined.Category,
                title = "No categories yet",
                message = "Create categories to organize your transactions.",
                actionLabel = "Add category",
                onAction = onAddClick,
                modifier = Modifier.padding(padding)
            )
            else -> {
                val expenseTree = remember(uiState.expenseCategories) { buildCategoryTree(uiState.expenseCategories) }
                val incomeTree = remember(uiState.incomeCategories) { buildCategoryTree(uiState.incomeCategories) }
                val allById = remember(uiState.expenseCategories, uiState.incomeCategories) {
                    (uiState.expenseCategories + uiState.incomeCategories).associateBy { it.id }
                }
                val rowContext = RowContext(
                    allById = allById,
                    pad = pad,
                    onEdit = onEditClick,
                    onDelete = { deleteTarget = it }
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(bottom = AppSpacing.fabClearance)
                ) {
                    item(key = "filter", contentType = "filter") {
                        FilterRow(
                            selected = filter,
                            onSelect = { filter = it },
                            modifier = Modifier.padding(start = pad, end = pad, top = AppSpacing.sm, bottom = AppSpacing.xs)
                        )
                    }
                    when (filter) {
                        CategoryFilter.ALL -> {
                            categorySection("expense", "Expense", expenseTree, rowContext, showHeader = true)
                            categorySection("income", "Income", incomeTree, rowContext, showHeader = true)
                        }
                        CategoryFilter.EXPENSE ->
                            categorySection("expense", "Expense", expenseTree, rowContext, showHeader = false)
                        CategoryFilter.INCOME ->
                            categorySection("income", "Income", incomeTree, rowContext, showHeader = false)
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "Delete ${target.name}?",
            text = "This can't be undone. Categories used by transactions or schedules can't be deleted.",
            confirmLabel = "Delete",
            onConfirm = {
                deleteTarget = null
                viewModel.delete(target.id)
            },
            onDismiss = { deleteTarget = null }
        )
    }
}

private class RowContext(
    val allById: Map<Long, Category>,
    val pad: Dp,
    val onEdit: (Long) -> Unit,
    val onDelete: (Category) -> Unit
)

private fun LazyListScope.categorySection(
    keyPrefix: String,
    title: String,
    tree: List<CategoryNode>,
    ctx: RowContext,
    showHeader: Boolean
) {
    if (showHeader) {
        item(key = "${keyPrefix}_header", contentType = "header") {
            SectionHeader(title = title, horizontalPadding = ctx.pad, topPadding = AppSpacing.lg)
        }
    }
    if (tree.isEmpty()) {
        item(key = "${keyPrefix}_empty", contentType = "empty") {
            Text(
                "No ${title.lowercase()} categories yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = if (showHeader) TextAlign.Start else TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ctx.pad, vertical = if (showHeader) AppSpacing.sm else AppSpacing.xxl)
            )
        }
        return
    }
    tree.forEach { node ->
        item(key = "cat_${node.category.id}", contentType = "parent") {
            ParentRow(node, ctx, Modifier.animateItem())
        }
        node.descendants.forEach { child ->
            item(key = "cat_${child.id}", contentType = "child") {
                ChildRow(child, node.category, ctx, Modifier.animateItem())
            }
        }
    }
}

private fun Category.dotColor() = color?.takeIf { it.isNotBlank() }?.let { ColorUtils.parseHex(it) }

private fun rowActions(category: Category, ctx: RowContext) = listOf(
    RowAction("Edit") { ctx.onEdit(category.id) },
    RowAction("Delete", destructive = true) { ctx.onDelete(category) }
)

@Composable
private fun ParentRow(node: CategoryNode, ctx: RowContext, modifier: Modifier = Modifier) {
    val count = node.descendants.size
    ListRow(
        headline = node.category.displayName(),
        supporting = when (count) {
            0 -> null
            1 -> "1 subcategory"
            else -> "$count subcategories"
        },
        leading = { CategoryDot(node.category.dotColor(), size = 12.dp) },
        onClick = { ctx.onEdit(node.category.id) },
        actions = rowActions(node.category, ctx),
        horizontalPadding = ctx.pad,
        modifier = modifier
    )
}

@Composable
private fun ChildRow(child: Category, root: Category, ctx: RowContext, modifier: Modifier = Modifier) {
    // Legacy grandchildren sit at the same indent; name their direct parent.
    val directParent = child.parentId?.let { ctx.allById[it] }
    val supporting = if (directParent != null && directParent.id != root.id) "In ${directParent.name}" else null
    ListRow(
        headline = child.displayName(),
        supporting = supporting,
        leading = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(ChildIndent))
                CategoryDot(child.dotColor() ?: root.dotColor(), size = 8.dp)
            }
        },
        onClick = { ctx.onEdit(child.id) },
        actions = rowActions(child, ctx),
        horizontalPadding = ctx.pad,
        modifier = modifier
    )
}

@Composable
private fun FilterRow(
    selected: CategoryFilter,
    onSelect: (CategoryFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = CategoryFilter.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selected == option,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(option.label, maxLines = 1)
            }
        }
    }
}
