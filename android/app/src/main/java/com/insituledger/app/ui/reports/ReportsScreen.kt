package com.insituledger.app.ui.reports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.data.local.db.dao.CurrencySummaryRow
import com.insituledger.app.domain.model.Category
import com.insituledger.app.domain.model.Transaction
import com.insituledger.app.ui.common.AdaptiveTrailingRow
import com.insituledger.app.ui.common.AutoSizeMoneyText
import com.insituledger.app.ui.common.CategoryDot
import com.insituledger.app.ui.common.DateLabels
import com.insituledger.app.ui.common.LocalContentPadding
import com.insituledger.app.ui.common.LocalCurrencySymbol
import com.insituledger.app.ui.common.MoneyFormat
import com.insituledger.app.ui.common.MoneySign
import com.insituledger.app.ui.common.MoneyText
import com.insituledger.app.ui.common.SectionHeader
import com.insituledger.app.ui.common.TransactionRow
import com.insituledger.app.ui.common.TransactionRowModel
import com.insituledger.app.ui.common.categoryColor
import com.insituledger.app.ui.common.categoryPath
import com.insituledger.app.ui.common.displayName
import com.insituledger.app.ui.common.label
import com.insituledger.app.ui.common.rangeLabel
import com.insituledger.app.ui.common.toUtcMillis
import com.insituledger.app.ui.common.utcMillisToLocalDate
import com.insituledger.app.ui.theme.AppSpacing
import com.insituledger.app.ui.theme.LocalSemanticColors
import com.insituledger.app.ui.theme.amountLarge
import com.insituledger.app.ui.theme.amountMedium
import com.insituledger.app.ui.theme.amountSmall
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Currency
import java.util.Locale
import kotlin.math.roundToInt

/** Every preset except CUSTOM, which has its own menu item. Enum order is menu order. */
private val PERIOD_PRESETS = DateRangePreset.entries.filter { it != DateRangePreset.CUSTOM }

/**
 * Reports. The drill-down replaces the summary in place (no navigation); the
 * summary's list state lives here so going back restores its scroll position.
 *
 * [onTransactionClick] opens a drill-down row for editing; when it is wired,
 * the report is re-read on return so edits show up.
 */
@Composable
fun ReportsScreen(
    onBack: () -> Unit,
    onTransactionClick: ((Long) -> Unit)? = null,
    viewModel: ReportsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val weekStart by viewModel.weekStartFlow.collectAsStateWithLifecycle()
    val summaryListState = rememberLazyListState()
    var openedTransaction by rememberSaveable { mutableStateOf(false) }

    // Back from editing a drill-down row: re-read the drill-down and totals.
    LaunchedEffect(Unit) {
        if (openedTransaction) {
            openedTransaction = false
            uiState.selectedCategory?.let(viewModel::selectCategory)
            viewModel.setDateRangePreset(uiState.dateRangePreset)
        }
    }

    if (uiState.selectedCategory != null) {
        BackHandler { viewModel.clearSelection() }
        DrillDownView(
            uiState = uiState,
            weekStart = weekStart,
            onBack = viewModel::clearSelection,
            onTransactionClick = onTransactionClick?.let { open ->
                { id: Long -> openedTransaction = true; open(id) }
            }
        )
    } else {
        SummaryView(
            uiState = uiState,
            weekStart = weekStart,
            listState = summaryListState,
            viewModel = viewModel,
            onBack = onBack
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SummaryView(
    uiState: ReportsUiState,
    weekStart: DayOfWeek,
    listState: LazyListState,
    viewModel: ReportsViewModel,
    onBack: () -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val pad = LocalContentPadding.current
    val symbol = LocalCurrencySymbol.current
    // Reloads after a period or grouping change take milliseconds; keep the
    // current figures on screen and only hold them back on the very first load.
    val firstLoad = uiState.isLoading && uiState.categoryBreakdown.isEmpty() &&
        uiState.totalIncome == 0.0 && uiState.totalExpense == 0.0

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Reports") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = AppSpacing.xl)
            ) {
                item(key = "controls", contentType = "controls") {
                    PeriodControls(
                        uiState = uiState,
                        weekStart = weekStart,
                        pad = pad,
                        onPreset = viewModel::setDateRangePreset,
                        onCustomRange = viewModel::setCustomDateRange,
                        onGrouping = viewModel::setGrouping
                    )
                }
                if (!firstLoad) {
                    item(key = "totals", contentType = "totals") {
                        TotalsRow(
                            income = uiState.totalIncome,
                            expense = uiState.totalExpense,
                            symbol = symbol,
                            modifier = Modifier.padding(start = pad, end = pad, top = AppSpacing.lg, bottom = AppSpacing.sm)
                        )
                    }
                    breakdownSection(
                        uiState = uiState,
                        symbol = symbol,
                        pad = pad,
                        onSelect = viewModel::selectCategory,
                        onShowAllTime = { viewModel.setDateRangePreset(DateRangePreset.ALL_TIME) }
                    )
                }
                searchSection(
                    uiState = uiState,
                    pad = pad,
                    onQuery = viewModel::setSearchQuery,
                    onSearchAllDates = { viewModel.setDateRangePreset(DateRangePreset.ALL_TIME) }
                )
            }
            if (firstLoad) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }
}

// ---- Period and grouping controls ----

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PeriodControls(
    uiState: ReportsUiState,
    weekStart: DayOfWeek,
    pad: Dp,
    onPreset: (DateRangePreset) -> Unit,
    onCustomRange: (String, String) -> Unit,
    onGrouping: (CategoryGrouping) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showRangePicker by rememberSaveable { mutableStateOf(false) }
    val preset = uiState.dateRangePreset
    val (from, to) = resolveRange(preset, uiState.customFrom, uiState.customTo, weekStart)
    val periodLabel = preset.label()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = pad, end = pad, top = AppSpacing.sm)
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
        ) {
            Box(Modifier.align(Alignment.CenterVertically)) {
                FilterChip(
                    selected = preset != DateRangePreset.ALL_TIME,
                    onClick = { menuOpen = true },
                    label = { Text(periodLabel, maxLines = 1) },
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                    modifier = Modifier.semantics { contentDescription = "Period: $periodLabel" }
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    PERIOD_PRESETS.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label()) },
                            leadingIcon = { RadioButton(selected = preset == option, onClick = null) },
                            onClick = { menuOpen = false; onPreset(option) }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Custom range…") },
                        leadingIcon = { RadioButton(selected = preset == DateRangePreset.CUSTOM, onClick = null) },
                        onClick = { menuOpen = false; showRangePicker = true }
                    )
                }
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.align(Alignment.CenterVertically)) {
                CategoryGrouping.entries.forEachIndexed { index, grouping ->
                    SegmentedButton(
                        selected = uiState.grouping == grouping,
                        onClick = { onGrouping(grouping) },
                        shape = SegmentedButtonDefaults.itemShape(index, CategoryGrouping.entries.size),
                        icon = {},
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(groupingLabel(grouping), maxLines = 1)
                    }
                }
            }
        }
        if (preset != DateRangePreset.ALL_TIME) {
            Text(
                text = rangeLabel(from, to),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = AppSpacing.xs)
            )
        }
    }

    if (showRangePicker) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = from?.let { DateLabels.parseDay(it) }?.toUtcMillis(),
            initialSelectedEndDateMillis = to?.let { DateLabels.parseDay(it) }?.toUtcMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showRangePicker = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        val start = state.selectedStartDateMillis?.let(::utcMillisToLocalDate)
                        val end = state.selectedEndDateMillis?.let(::utcMillisToLocalDate) ?: start
                        if (start != null && end != null) onCustomRange(start.toString(), end.toString())
                        showRangePicker = false
                    }
                ) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { showRangePicker = false }) { Text("Cancel") } }
        ) {
            DateRangePicker(
                state = state,
                title = { Text("Date range", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ---- In / Out / Net ----

/** One row of three figures; amounts step down in size rather than clip. */
@Composable
private fun TotalsRow(income: Double, expense: Double, symbol: String, modifier: Modifier = Modifier) {
    val semantic = LocalSemanticColors.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val net = income - expense
    val netText = MoneyFormat.net(net, symbol)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.lg)
    ) {
        val inText = MoneyFormat.format(income, symbol, MoneySign.NONE)
        val outText = MoneyFormat.format(expense, symbol, MoneySign.NONE)
        Figure(
            label = "In",
            text = inText,
            color = if (income > 0.004) semantic.positive else onSurface,
            spoken = "Income $inText",
            modifier = Modifier.weight(1f)
        )
        Figure(
            label = "Out",
            text = outText,
            color = onSurface,
            spoken = "Expenses $outText",
            modifier = Modifier.weight(1f)
        )
        // Same sign and colour rules as NetText, but able to shrink to fit a third of the row.
        Figure(
            label = "Net",
            text = netText,
            color = when {
                net > 0.004 -> semantic.positive
                net < -0.004 -> semantic.negative
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            spoken = "Net $netText",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun Figure(label: String, text: String, color: Color, spoken: String, modifier: Modifier = Modifier) {
    val type = MaterialTheme.typography
    Column(modifier.clearAndSetSemantics { contentDescription = spoken }) {
        Text(
            label,
            style = type.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        AutoSizeMoneyText(
            text = text,
            style = type.amountMedium,
            color = color,
            steps = listOf(type.amountMedium.fontSize, type.amountSmall.fontSize, type.labelMedium.fontSize)
        )
    }
}

// ---- Category breakdown ----

private fun LazyListScope.breakdownSection(
    uiState: ReportsUiState,
    symbol: String,
    pad: Dp,
    onSelect: (Category) -> Unit,
    onShowAllTime: () -> Unit
) {
    val expenses = uiState.categoryBreakdown.filter { it.category.type != "income" }.sortedByDescending { it.total }
    val income = uiState.categoryBreakdown.filter { it.category.type == "income" }.sortedByDescending { it.total }

    if (expenses.isEmpty() && income.isEmpty()) {
        if (uiState.isLoading) return
        item(key = "breakdown_empty", contentType = "message") {
            Column(Modifier.fillMaxWidth().padding(start = pad, end = pad, top = AppSpacing.xl)) {
                Text(
                    "No transactions in this period.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (uiState.dateRangePreset != DateRangePreset.ALL_TIME) {
                    TextButton(onClick = onShowAllTime, contentPadding = PaddingValues(0.dp)) { Text("Show all time") }
                }
            }
        }
        return
    }

    fun section(key: String, title: String, whole: String, rows: List<CategorySummary>, total: Double) {
        if (rows.isEmpty()) return
        item(key = "${key}_header", contentType = "header") {
            SectionHeader(title, horizontalPadding = pad)
        }
        // Indexed keys: a parent roll-up can legitimately repeat a category.
        itemsIndexed(rows, key = { index, row -> "${key}_${row.category.id}_$index" }, contentType = { _, _ -> "breakdown" }) { _, row ->
            BreakdownRow(
                name = categoryPath(uiState.categories, row.category.id) ?: row.category.displayName(),
                color = categoryColor(uiState.categories, row.category.id),
                amountText = MoneyFormat.format(row.total, symbol, MoneySign.NONE),
                share = if (total > 0.0) (row.total / total).toFloat() else 0f,
                whole = whole,
                pad = pad,
                onClick = { onSelect(row.category) }
            )
        }
    }

    section("expense", "Expenses", "expenses", expenses, uiState.totalExpense)
    section("income", "Income", "income", income, uiState.totalIncome)
}

/** "34%"; shares above zero but under half a percent read "<1%" rather than "0%". */
private fun percentLabel(share: Float): String = when {
    share <= 0f -> "0%"
    share < 0.005f -> "<1%"
    else -> "${(share * 100).roundToInt().coerceAtMost(100)}%"
}

/**
 * A category's figure: name and amount, its share of the period's income or
 * expenses, and a thin share bar. One accessible node reads it all out.
 */
@Composable
private fun BreakdownRow(
    name: String,
    color: Color?,
    amountText: String,
    share: Float,
    whole: String,
    pad: Dp,
    onClick: () -> Unit
) {
    val percent = percentLabel(share)
    val spokenPercent = if (percent == "<1%") "less than 1 percent" else percent.replace("%", " percent")
    val spoken = "$name, $spokenPercent of $whole, $amountText"
    val dotSize = 10.dp
    val barColor = color ?: MaterialTheme.colorScheme.outline
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Show transactions", role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                onClick(label = "Show transactions") { onClick(); true }
            }
            .heightIn(min = AppSpacing.rowMinHeight)
            .padding(horizontal = pad, vertical = AppSpacing.md)
    ) {
        AdaptiveTrailingRow(
            content = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CategoryDot(barColor, size = dotSize)
                        Spacer(Modifier.width(AppSpacing.md))
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        "$percent of $whole",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = dotSize + AppSpacing.md)
                    )
                }
            },
            trailing = {
                MoneyText(
                    text = amountText,
                    style = MaterialTheme.typography.amountMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        )
        ShareBar(
            fraction = share,
            color = barColor,
            modifier = Modifier.padding(start = dotSize + AppSpacing.md, top = AppSpacing.sm)
        )
    }
}

/** 4dp share bar: category colour on a neutral track, rounded ends. */
@Composable
private fun ShareBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.extraSmall
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(f)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(color)
            )
        }
    }
}

// ---- Search totals ----

private fun LazyListScope.searchSection(
    uiState: ReportsUiState,
    pad: Dp,
    onQuery: (String) -> Unit,
    onSearchAllDates: () -> Unit
) {
    // Scoped by the period above like everything else on the page. Looking a
    // trip up months after it ended is "All time", one tap away below.
    val phrase = periodPhrase(uiState)
    item(key = "search_header", contentType = "header") {
        SectionHeader("Search totals", horizontalPadding = pad, topPadding = AppSpacing.xxl)
    }
    item(key = "search_field", contentType = "search_field") {
        val keyboard = LocalSoftwareKeyboardController.current
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onQuery,
            label = { Text("Search descriptions") },
            placeholder = { Text("e.g. Valencia") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = if (uiState.searchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                    }
                }
            } else null,
            supportingText = { Text(if (phrase == null) "Totals across all dates" else "Totals $phrase") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = pad)
        )
    }

    if (uiState.searchQuery.isBlank()) return
    val term = uiState.searchQuery.trim()
    when {
        uiState.isSearching -> item(key = "search_status", contentType = "message") {
            Text(
                "Searching…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = pad, vertical = AppSpacing.sm)
            )
        }
        uiState.searchSummary.isEmpty() -> item(key = "search_empty", contentType = "message") {
            Column(Modifier.padding(horizontal = pad, vertical = AppSpacing.sm)) {
                Text(
                    if (phrase == null) "No transactions match “$term”." else "No transactions match “$term” $phrase.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // The term may well match something outside the range, which
                // is the whole point of looking a past trip up.
                if (phrase != null) {
                    TextButton(onClick = onSearchAllDates, contentPadding = PaddingValues(0.dp)) {
                        Text("Search all dates")
                    }
                }
            }
        }
        else -> itemsIndexed(uiState.searchSummary, key = { _, row -> "search_${row.currency}" }, contentType = { _, _ -> "search_row" }) { index, row ->
            if (index > 0) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = pad)
                )
            }
            SearchTotals(row, pad)
        }
    }
}

/**
 * One currency's totals for the search. Formatted with the row's own
 * currency rather than the user's display symbol: labelling a USD total with
 * a euro sign would be worse than useless.
 */
@Composable
private fun SearchTotals(row: CurrencySummaryRow, pad: Dp) {
    Column(Modifier.fillMaxWidth().padding(horizontal = pad, vertical = AppSpacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.currency,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (row.count == 1) "1 transaction" else "${row.count} transactions",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(AppSpacing.sm))
        TotalsRow(income = row.income, expense = row.expense, symbol = isoSymbol(row.currency))
    }
}

private fun isoSymbol(code: String): String = try {
    Currency.getInstance(code).getSymbol(Locale.getDefault())
} catch (_: Exception) {
    code
}

// ---- Drill-down ----

@Immutable
private data class DrillDownDay(val date: String, val label: String, val rows: List<TransactionRowModel>)

private fun drillDownDays(
    transactions: List<Transaction>,
    categories: List<Category>,
    symbol: String,
    today: LocalDate = LocalDate.now()
): List<DrillDownDay> {
    val byId = categories.associateBy { it.id }
    // Group by the date prefix: post-1.18 rows carry an offset suffix.
    return transactions.groupBy { it.date.take(10) }.map { (date, txns) ->
        DrillDownDay(
            date = date,
            label = DateLabels.parseDay(date)?.let { DateLabels.day(it, today) } ?: date,
            rows = txns.map { txn ->
                val categoryName = byId[txn.categoryId]?.name
                TransactionRowModel(
                    id = txn.id,
                    title = txn.description?.takeIf { it.isNotBlank() } ?: categoryName
                        ?: txn.type.replaceFirstChar { it.uppercase() },
                    supporting = listOfNotNull(categoryName, DateLabels.time(txn.date)).joinToString(" · "),
                    categoryColor = categoryColor(categories, txn.categoryId),
                    attribution = null,
                    amount = txn.amount,
                    type = txn.type,
                    symbol = symbol
                )
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun DrillDownView(
    uiState: ReportsUiState,
    weekStart: DayOfWeek,
    onBack: () -> Unit,
    onTransactionClick: ((Long) -> Unit)?
) {
    val category = uiState.selectedCategory ?: return
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val pad = LocalContentPadding.current
    val symbol = LocalCurrencySymbol.current
    val days = remember(uiState.selectedCategoryTransactions, uiState.categories, symbol) {
        drillDownDays(uiState.selectedCategoryTransactions, uiState.categories, symbol)
    }
    val (from, to) = resolveRange(uiState.dateRangePreset, uiState.customFrom, uiState.customTo, weekStart)
    val count = uiState.selectedCategoryTransactions.size
    val totalText = MoneyFormat.format(uiState.selectedCategoryTotal, symbol, MoneySign.NONE)
    val period = if (from == null && to == null) "All dates" else rangeLabel(from, to)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(category.displayName(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = AppSpacing.xl)
        ) {
            item(key = "total", contentType = "total") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = pad, end = pad, top = AppSpacing.sm, bottom = AppSpacing.sm)
                        .semantics(mergeDescendants = true) {}
                ) {
                    Text(
                        "Total for period",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(AppSpacing.xs))
                    AutoSizeMoneyText(
                        text = totalText,
                        style = MaterialTheme.typography.amountLarge,
                        color = if (category.type == "income") LocalSemanticColors.current.positive
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(AppSpacing.xs))
                    Text(
                        "$period · ${if (count == 1) "1 transaction" else "$count transactions"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (days.isEmpty()) {
                item(key = "empty", contentType = "message") {
                    Text(
                        "No transactions in this period.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = pad, vertical = AppSpacing.lg)
                    )
                }
            }
            days.forEach { day ->
                stickyHeader(key = "day_${day.date}", contentType = "day_header") {
                    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = day.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(start = pad, end = pad, top = AppSpacing.lg, bottom = AppSpacing.xs)
                                .semantics { heading() }
                        )
                    }
                }
                items(day.rows, key = { it.id }, contentType = { "transaction" }) { row ->
                    TransactionRow(
                        model = row,
                        onClick = { onTransactionClick?.invoke(row.id) },
                        horizontalPadding = pad
                    )
                }
            }
        }
    }
}

// ---- Labels ----

/**
 * The active range as a phrase that slots straight into a sentence ("Totals
 * this year", "No transactions match "x" between 1 Nov and 13 Nov"), or null
 * when it covers everything — an unbounded CUSTOM is all dates however it reads.
 */
private fun periodPhrase(state: ReportsUiState): String? {
    fun day(s: String) = DateLabels.parseDay(s)?.let { DateLabels.short(it) } ?: s
    return when (state.dateRangePreset) {
        DateRangePreset.ALL_TIME -> null
        DateRangePreset.CUSTOM -> when {
            state.customFrom.isBlank() && state.customTo.isBlank() -> null
            state.customFrom.isBlank() -> "on or before ${day(state.customTo)}"
            state.customTo.isBlank() -> "on or after ${day(state.customFrom)}"
            else -> "between ${day(state.customFrom)} and ${day(state.customTo)}"
        }
        else -> state.dateRangePreset.label().lowercase()
    }
}

private fun groupingLabel(grouping: CategoryGrouping): String = when (grouping) {
    CategoryGrouping.CATEGORY -> "By category"
    CategoryGrouping.PARENT -> "By parent"
}
