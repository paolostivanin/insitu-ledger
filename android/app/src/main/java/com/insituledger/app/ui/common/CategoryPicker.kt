@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.insituledger.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.insituledger.app.domain.model.Category
import com.insituledger.app.ui.theme.AppSpacing
import kotlinx.coroutines.launch

/** "🛒 Groceries" when the category carries an emoji icon. */
fun Category.displayName(): String =
    icon?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it $name" } ?: name

/** "Food › Groceries" for subcategories, plain name for top-level ones. */
fun categoryPath(categories: List<Category>, id: Long?): String? {
    val cat = categories.find { it.id == id } ?: return null
    val parent = cat.parentId?.let { pid -> categories.find { it.id == pid } }
    return if (parent != null) "${parent.name} › ${cat.displayName()}" else cat.displayName()
}

/** The colour a category is drawn with (a subcategory inherits its parent's). */
fun categoryColor(categories: List<Category>, id: Long?): Color? {
    val cat = categories.find { it.id == id } ?: return null
    val parent = cat.parentId?.let { pid -> categories.find { it.id == pid } }
    val hex = cat.color?.takeIf { it.isNotBlank() } ?: parent?.color
    return hex?.takeIf { it.isNotBlank() }?.let { ColorUtils.parseHex(it) }
}

@Composable
fun CategoryDot(color: Color?, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 8.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color ?: MaterialTheme.colorScheme.outline)
    )
}

/** Form row showing the selected category; opens [CategoryPickerSheet]. */
@Composable
fun CategorySelectorRow(
    categories: List<Category>,
    selectedId: Long?,
    type: String,
    onSelect: (Long) -> Unit,
    onCreateCategory: (name: String, type: String) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supporting: String? = null
) {
    var showSheet by remember { mutableStateOf(false) }
    SelectorRow(
        icon = Icons.Outlined.Category,
        label = "Category",
        value = categoryPath(categories, selectedId),
        valueLeading = { CategoryDot(categoryColor(categories, selectedId), size = 10.dp) },
        onClick = { showSheet = true },
        isError = isError,
        supporting = supporting,
        modifier = modifier
    )
    if (showSheet) {
        CategoryPickerSheet(
            categories = categories,
            selectedId = selectedId,
            type = type,
            onSelect = { id -> id?.let(onSelect) },
            onDismiss = { showSheet = false },
            onCreateCategory = onCreateCategory
        )
    }
}

/**
 * Category picker. Top-level categories without children are chips at the
 * top; categories with children become headed groups of chips (the parent is
 * a label, not selectable — unchanged from before). [type] null shows both
 * types (used by filters), and [allowAll] adds an "All categories" choice that
 * reports null.
 */
@Composable
fun CategoryPickerSheet(
    categories: List<Category>,
    selectedId: Long?,
    type: String?,
    onSelect: (Long?) -> Unit,
    onDismiss: () -> Unit,
    onCreateCategory: ((name: String, type: String) -> Unit)? = null,
    allowAll: Boolean = false
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }

    fun close(then: () -> Unit = {}) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            then()
            onDismiss()
        }
    }

    // Keep list flings from dragging the sheet down.
    val absorb = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = available
            override suspend fun onPostFling(consumed: Velocity, available: Velocity) = available
        }
    }

    val sections: List<Pair<String?, List<Category>>> = remember(categories, type) {
        if (type != null) listOf(null to categories.filter { it.type == type })
        else listOf(
            "Expense" to categories.filter { it.type == "expense" },
            "Income" to categories.filter { it.type == "income" }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = AppSpacing.screenH, end = AppSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Category",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() }
                )
                if (onCreateCategory != null && type != null) {
                    TextButton(onClick = { showCreate = true }) { Text("New category") }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.sm)
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .nestedScroll(absorb),
                contentPadding = PaddingValues(
                    start = AppSpacing.screenH,
                    end = AppSpacing.screenH,
                    top = AppSpacing.xs,
                    bottom = AppSpacing.xl
                )
            ) {
                if (allowAll && query.isBlank()) {
                    item(key = "all") {
                        ChipFlow {
                            PickerChip(
                                label = "All categories",
                                color = null,
                                selected = selectedId == null,
                                onClick = { close { onSelect(null) } }
                            )
                        }
                    }
                }
                var anyMatch = false
                sections.forEach { (sectionTitle, cats) ->
                    val groups = buildGroups(cats, query)
                    if (groups.standalone.isEmpty() && groups.parents.isEmpty()) return@forEach
                    anyMatch = true
                    if (sectionTitle != null) {
                        item(key = "section-$sectionTitle") {
                            GroupLabel(sectionTitle, topPadding = AppSpacing.md)
                        }
                    }
                    if (groups.standalone.isNotEmpty()) {
                        item(key = "standalone-${sectionTitle ?: ""}") {
                            ChipFlow {
                                groups.standalone.forEach { cat ->
                                    PickerChip(
                                        label = cat.displayName(),
                                        color = cat.color?.let { ColorUtils.parseHex(it) },
                                        selected = cat.id == selectedId,
                                        onClick = { close { onSelect(cat.id) } }
                                    )
                                }
                            }
                        }
                    }
                    items(groups.parents, key = { "group-${it.first.id}" }) { (parent, children) ->
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = AppSpacing.md, bottom = AppSpacing.xs)
                            ) {
                                CategoryDot(parent.color?.let { ColorUtils.parseHex(it) })
                                Spacer(Modifier.size(AppSpacing.sm))
                                Text(
                                    parent.displayName(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            ChipFlow {
                                children.forEach { child ->
                                    PickerChip(
                                        label = child.displayName(),
                                        color = null,
                                        selected = child.id == selectedId,
                                        onClick = { close { onSelect(child.id) } }
                                    )
                                }
                            }
                        }
                    }
                }
                if (!anyMatch) {
                    item(key = "empty") {
                        Text(
                            if (query.isBlank()) "No categories yet" else "No matching categories",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.xl)
                        )
                    }
                }
            }
        }
    }

    if (showCreate && onCreateCategory != null && type != null) {
        NewCategoryDialog(
            type = type,
            onDismiss = { showCreate = false },
            onCreate = { name ->
                showCreate = false
                close { onCreateCategory(name, type) }
            }
        )
    }
}

private data class CategoryGroups(
    val standalone: List<Category>,
    val parents: List<Pair<Category, List<Category>>>
)

private fun buildGroups(cats: List<Category>, query: String): CategoryGroups {
    fun matches(c: Category) = query.isBlank() || c.name.contains(query.trim(), ignoreCase = true)
    val topLevel = cats.filter { it.parentId == null }
    val standalone = mutableListOf<Category>()
    val parents = mutableListOf<Pair<Category, List<Category>>>()
    topLevel.forEach { parent ->
        val children = cats.filter { it.parentId == parent.id }
        if (children.isEmpty()) {
            if (matches(parent)) standalone += parent
        } else {
            // A matching parent shows all its children; otherwise only matches.
            val shown = if (matches(parent)) children else children.filter(::matches)
            if (shown.isNotEmpty()) parents += parent to shown
        }
    }
    return CategoryGroups(standalone, parents)
}

@Composable
private fun GroupLabel(text: String, topPadding: androidx.compose.ui.unit.Dp) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(top = topPadding, bottom = AppSpacing.xs)
            .semantics { heading() }
    )
}

@Composable
private fun ChipFlow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        modifier = Modifier.fillMaxWidth()
    ) { content() }
}

@Composable
private fun PickerChip(label: String, color: Color?, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        leadingIcon = if (color != null) {
            { CategoryDot(color) }
        } else null,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    )
}

@Composable
fun NewCategoryDialog(type: String, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (type == "income") "New income category" else "New expense category") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
