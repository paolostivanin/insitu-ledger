<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
	import { goto } from '$app/navigation';
	import { reports, type CategoryReport, type MonthReport, type SearchSummary, type TrendReport } from '$lib/api/client';
	import { rollUpByParent } from '$lib/categoryRollup';
	import { PERIOD_LABELS, resolvePeriod, type PeriodPreset } from '$lib/reportPeriod';
	import { formatMoney } from '$lib/format';
	import { theme } from '$lib/stores/theme';
	import { sharedOwnerUserId } from '$lib/stores/shared';
	import type * as EChartsType from 'echarts';

	let echarts: typeof EChartsType;

	let categoryData = $state<CategoryReport[]>([]);
	let monthData = $state<MonthReport[]>([]);
	let trendData = $state<TrendReport[]>([]);

	let year = $state(new Date().getFullYear().toString());
	let trendFrom = $state('');
	let trendTo = $state('');
	let trendGroupBy = $state('month');
	let reportType = $state('expense');
	let categoryGroupBy = $state<'category' | 'parent'>('category');
	let categoryPeriod = $state<PeriodPreset>('this_month');
	let categoryFrom = $state('');
	let categoryTo = $state('');

	// Rolling up is a pure re-shape of data we already have, so switching the
	// group-by is instant — only period/type changes need a refetch.
	const displayedCategoryData = $derived(
		categoryGroupBy === 'parent' ? rollUpByParent(categoryData) : categoryData
	);

	const periodPresets: PeriodPreset[] = [
		'this_week',
		'this_month',
		'this_year',
		'last_month',
		'last_year',
		'all',
		'custom'
	];

	// Search summary: total up everything whose description matches a term —
	// a trip, a project, a person. Deliberately all-time and independent of the
	// category period above: you look this up months after the trip ended.
	const SEARCH_DEBOUNCE_MS = 300;
	const MAX_SEARCH_LEN = 200; // mirrors maxSearchLen in the backend

	let searchQuery = $state('');
	let searchSummary = $state<SearchSummary[]>([]);
	let searchLoading = $state(false);
	let searchError = $state('');
	let searchTimer: ReturnType<typeof setTimeout>;
	// Bumped per request so a slow early response can't overwrite a later one.
	let searchSeq = 0;

	let pieChartEl: HTMLDivElement;
	let barChartEl: HTMLDivElement;
	let trendChartEl: HTMLDivElement;

	let pieChart: EChartsType.ECharts;
	let barChart: EChartsType.ECharts;
	let trendChart: EChartsType.ECharts;
	let themeTimer: ReturnType<typeof setTimeout>;

	function getCssVar(name: string): string {
		return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
	}

	function getEchartsTheme(): string {
		return getCssVar('--bg') === '#0f1117' ? 'dark' : undefined as any;
	}

	function reinitCharts() {
		if (!echarts) return;
		const echartsTheme = getEchartsTheme();
		if (pieChart) pieChart.dispose();
		if (barChart) barChart.dispose();
		if (trendChart) trendChart.dispose();
		pieChart = echarts.init(pieChartEl, echartsTheme);
		pieChart.on('click', handlePieClick);
		barChart = echarts.init(barChartEl, echartsTheme);
		trendChart = echarts.init(trendChartEl, echartsTheme);
		renderPie();
		renderBar();
		renderTrend();
	}

	function handlePieClick(params: any) {
		// Index the displayed array, not the raw one — in parent mode the two
		// differ and dataIndex refers to what's actually plotted. The backend
		// transaction filter already expands a parent to its children, so the
		// deep-link works for a rolled-up slice as-is.
		const cat = displayedCategoryData[params.dataIndex];
		if (!cat) return;
		void goto(`/transactions?category_id=${cat.category_id}`);
	}

	const unsubTheme = theme.subscribe(() => {
		if (pieChart) {
			// Defer to allow CSS vars to update
			clearTimeout(themeTimer);
			themeTimer = setTimeout(reinitCharts, 50);
		}
	});

	function handleResize() {
		if (pieChart) pieChart.resize();
		if (barChart) barChart.resize();
		if (trendChart) trendChart.resize();
	}

	onDestroy(() => {
		clearTimeout(themeTimer);
		clearTimeout(searchTimer);
		unsubTheme();
		window.removeEventListener('resize', handleResize);
		if (pieChart) pieChart.dispose();
		if (barChart) barChart.dispose();
		if (trendChart) trendChart.dispose();
	});

	let mounted = false;
	let prevOwnerId: string | null = null;

	$effect(() => {
		const oid = $sharedOwnerUserId;
		if (mounted && oid !== prevOwnerId) {
			prevOwnerId = oid;
			void loadAll();
		}
	});

	onMount(async () => {
		echarts = await import('echarts');

		const echartsTheme = getEchartsTheme();
		pieChart = echarts.init(pieChartEl, echartsTheme);
		pieChart.on('click', handlePieClick);
		barChart = echarts.init(barChartEl, echartsTheme);
		trendChart = echarts.init(trendChartEl, echartsTheme);

		window.addEventListener('resize', handleResize);

		prevOwnerId = $sharedOwnerUserId;
		mounted = true;
		await loadAll();
	});

	async function loadAll() {
		await Promise.all([loadCategory(), loadMonth(), loadTrend(), loadSearch()]);
	}

	async function loadCategory() {
		const oid = $sharedOwnerUserId || undefined;
		const { from, to } = resolvePeriod(categoryPeriod, categoryFrom, categoryTo);
		categoryData = await reports.byCategory({ type: reportType, from, to, owner_id: oid });
		renderPie();
	}

	async function loadMonth() {
		const oid = $sharedOwnerUserId || undefined;
		monthData = await reports.byMonth({ year, owner_id: oid });
		renderBar();
	}

	async function loadTrend() {
		const oid = $sharedOwnerUserId || undefined;
		trendData = await reports.trend({ from: trendFrom, to: trendTo, group_by: trendGroupBy, owner_id: oid });
		renderTrend();
	}

	function onSearchInput() {
		clearTimeout(searchTimer);
		if (searchQuery.trim() === '') {
			// Clear straight away rather than after the debounce — a stale total
			// under an empty box reads as a result for "everything".
			searchSeq++;
			searchSummary = [];
			searchLoading = false;
			searchError = '';
			return;
		}
		searchLoading = true;
		searchError = '';
		searchTimer = setTimeout(() => void loadSearch(), SEARCH_DEBOUNCE_MS);
	}

	async function loadSearch() {
		const q = searchQuery.trim();
		if (q === '') return;
		const seq = ++searchSeq;
		searchLoading = true;
		try {
			const rows = await reports.summary({ q, owner_id: $sharedOwnerUserId || undefined });
			if (seq !== searchSeq) return;
			searchSummary = rows;
			searchError = '';
		} catch (e) {
			if (seq !== searchSeq) return;
			searchSummary = [];
			searchError = e instanceof Error ? e.message : 'Search failed';
		} finally {
			if (seq === searchSeq) searchLoading = false;
		}
	}

	// No symbol: each row states its own currency code, and pinning the user's
	// global symbol onto a USD row would be a lie.
	function plain(n: number): string {
		return formatMoney(n, '');
	}

	function renderPie() {
		if (!pieChart) return;
		const what = reportType === 'expense' ? 'Expenses' : 'Income';
		const by = categoryGroupBy === 'parent' ? 'Parent Category' : 'Category';
		pieChart.setOption({
			backgroundColor: 'transparent',
			title: {
				text: `${what} by ${by}`,
				subtext: PERIOD_LABELS[categoryPeriod],
				left: 'center',
				textStyle: { color: getCssVar('--text') },
				subtextStyle: { color: getCssVar('--text-muted') }
			},
			tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
			series: [{
				type: 'pie',
				radius: ['40%', '70%'],
				top: 30,
				cursor: 'pointer',
				itemStyle: { borderRadius: 6, borderColor: getCssVar('--bg-card'), borderWidth: 2 },
				label: { color: getCssVar('--text') },
				data: displayedCategoryData.map(c => ({
					value: c.total,
					name: c.category_name,
					itemStyle: c.category_color ? { color: c.category_color } : undefined
				}))
			}]
		});
	}

	function renderBar() {
		if (!barChart) return;
		const months = [...new Set(monthData.map(m => m.month))].sort();
		const incomeData = months.map(m => monthData.find(d => d.month === m && d.type === 'income')?.total || 0);
		const expenseData = months.map(m => monthData.find(d => d.month === m && d.type === 'expense')?.total || 0);

		barChart.setOption({
			backgroundColor: 'transparent',
			title: { text: `${year} Monthly Overview`, left: 'center', textStyle: { color: getCssVar('--text') } },
			tooltip: { trigger: 'axis' },
			legend: { data: ['Income', 'Expenses'], bottom: 0, textStyle: { color: getCssVar('--text-muted') } },
			xAxis: { type: 'category', data: months, axisLabel: { color: getCssVar('--text-muted') } },
			yAxis: { type: 'value', axisLabel: { color: getCssVar('--text-muted') }, splitLine: { lineStyle: { color: getCssVar('--border') } } },
			series: [
				{ name: 'Income', type: 'bar', data: incomeData, color: getCssVar('--income'), barMaxWidth: 30 },
				{ name: 'Expenses', type: 'bar', data: expenseData, color: getCssVar('--expense'), barMaxWidth: 30 }
			]
		});
	}

	function renderTrend() {
		if (!trendChart) return;
		const periods = [...new Set(trendData.map(t => t.period))].sort();
		const incomeData = periods.map(p => trendData.find(d => d.period === p && d.type === 'income')?.total || 0);
		const expenseData = periods.map(p => trendData.find(d => d.period === p && d.type === 'expense')?.total || 0);
		const netData = periods.map((_, i) => incomeData[i] - expenseData[i]);

		trendChart.setOption({
			backgroundColor: 'transparent',
			title: { text: 'Income vs Expenses Trend', left: 'center', textStyle: { color: getCssVar('--text') } },
			tooltip: { trigger: 'axis' },
			legend: { data: ['Income', 'Expenses', 'Net'], bottom: 0, textStyle: { color: getCssVar('--text-muted') } },
			xAxis: { type: 'category', data: periods, axisLabel: { color: getCssVar('--text-muted') } },
			yAxis: { type: 'value', axisLabel: { color: getCssVar('--text-muted') }, splitLine: { lineStyle: { color: getCssVar('--border') } } },
			series: [
				{ name: 'Income', type: 'line', data: incomeData, color: getCssVar('--income'), smooth: true },
				{ name: 'Expenses', type: 'line', data: expenseData, color: getCssVar('--expense'), smooth: true },
				{ name: 'Net', type: 'line', data: netData, color: getCssVar('--primary'), smooth: true, lineStyle: { type: 'dashed' } }
			]
		});
	}
</script>

<div class="page">
	<h1>Reports</h1>

	<div class="controls card">
		<div class="form-row">
			<div class="form-group">
				<label for="rtype">Category Report Type</label>
				<select id="rtype" bind:value={reportType} onchange={() => loadCategory()}>
					<option value="expense">Expenses</option>
					<option value="income">Income</option>
				</select>
			</div>
			<div class="form-group">
				<label for="cgroup">Group By</label>
				<select id="cgroup" bind:value={categoryGroupBy} onchange={() => renderPie()}>
					<option value="category">Category</option>
					<option value="parent">Parent category</option>
				</select>
			</div>
			<div class="form-group">
				<label for="cperiod">Category Period</label>
				<select id="cperiod" bind:value={categoryPeriod} onchange={() => loadCategory()}>
					{#each periodPresets as preset}
						<option value={preset}>{PERIOD_LABELS[preset]}</option>
					{/each}
				</select>
			</div>
			{#if categoryPeriod === 'custom'}
				<div class="form-group">
					<label for="cfrom">Category From</label>
					<input id="cfrom" type="date" bind:value={categoryFrom} onchange={() => loadCategory()} />
				</div>
				<div class="form-group">
					<label for="cto">Category To</label>
					<input id="cto" type="date" bind:value={categoryTo} onchange={() => loadCategory()} />
				</div>
			{/if}
			<div class="form-group">
				<label for="year">Year</label>
				<input id="year" type="number" bind:value={year} onchange={() => loadMonth()} />
			</div>
			<div class="form-group">
				<label for="tgb">Trend Group By</label>
				<select id="tgb" bind:value={trendGroupBy} onchange={() => loadTrend()}>
					<option value="day">Day</option>
					<option value="week">Week</option>
					<option value="month">Month</option>
				</select>
			</div>
			<div class="form-group">
				<label for="tf">Trend From</label>
				<input id="tf" type="date" bind:value={trendFrom} onchange={() => loadTrend()} />
			</div>
			<div class="form-group">
				<label for="tt">Trend To</label>
				<input id="tt" type="date" bind:value={trendTo} onchange={() => loadTrend()} />
			</div>
		</div>
	</div>

	<div class="card search-card">
		<div class="search-head">
			<div>
				<h2>Search Summary</h2>
				<p class="search-sub">Totals every transaction whose description matches, across all dates.</p>
			</div>
			<input
				type="search"
				class="search-input"
				placeholder="e.g. valencia"
				maxlength={MAX_SEARCH_LEN}
				bind:value={searchQuery}
				oninput={onSearchInput}
			/>
		</div>

		{#if searchQuery.trim() === ''}
			<p class="search-hint">
				Tag a trip or project in the description, then search it here to see what went
				out, what came back, and the net.
			</p>
		{:else if searchError}
			<p class="error-msg">{searchError}</p>
		{:else if searchLoading}
			<p class="search-hint">Searching…</p>
		{:else if searchSummary.length === 0}
			<p class="search-hint">No transactions match “{searchQuery.trim()}”.</p>
		{:else}
			<table>
				<thead>
					<tr>
						<th>Currency</th>
						<th class="num">In</th>
						<th class="num">Out</th>
						<th class="num">Net</th>
						<th class="num">Count</th>
					</tr>
				</thead>
				<tbody>
					{#each searchSummary as row (row.currency)}
						<tr>
							<td class="ccy">{row.currency}</td>
							<td class="num amount-income">+{plain(row.income)}</td>
							<td class="num amount-expense">−{plain(row.expense)}</td>
							<td
								class="num net"
								class:amount-income={row.net > 0}
								class:amount-expense={row.net < 0}
							>{plain(row.net)}</td>
							<td class="num count">{row.count}</td>
						</tr>
					{/each}
				</tbody>
			</table>
		{/if}
	</div>

	<div class="charts-grid">
		<div class="card chart-card">
			<div bind:this={pieChartEl} class="chart"></div>
		</div>
		<div class="card chart-card">
			<div bind:this={barChartEl} class="chart"></div>
		</div>
	</div>

	<div class="card chart-card" style="margin-top: 1rem">
		<div bind:this={trendChartEl} class="chart-wide"></div>
	</div>
</div>

<style>
	.controls {
		margin-bottom: 1.5rem;
	}
	.search-card {
		margin-bottom: 1rem;
	}
	.search-head {
		display: flex;
		align-items: flex-start;
		justify-content: space-between;
		gap: 1rem;
		flex-wrap: wrap;
		margin-bottom: 0.75rem;
	}
	.search-head h2 {
		margin: 0;
		font-size: 1.1rem;
	}
	.search-sub {
		margin: 0.25rem 0 0;
		font-size: 0.85rem;
		color: var(--text-muted);
	}
	.search-input {
		width: 16rem;
		max-width: 100%;
	}
	.search-hint {
		margin: 0;
		color: var(--text-muted);
		font-size: 0.9rem;
	}
	.num {
		text-align: right;
		font-variant-numeric: tabular-nums;
	}
	.ccy {
		font-weight: 600;
	}
	.net {
		font-weight: 600;
	}
	.count {
		color: var(--text-muted);
	}
	.charts-grid {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 1rem;
	}
	.chart {
		width: 100%;
		height: 350px;
	}
	.chart-wide {
		width: 100%;
		height: 400px;
	}
	@media (max-width: 768px) {
		.charts-grid {
			grid-template-columns: 1fr;
		}
	}
</style>
