<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
	import { reports, type CategoryReport, type MonthReport, type TrendReport } from '$lib/api/client';
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
		barChart = echarts.init(barChartEl, echartsTheme);
		trendChart = echarts.init(trendChartEl, echartsTheme);
		renderPie();
		renderBar();
		renderTrend();
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
		barChart = echarts.init(barChartEl, echartsTheme);
		trendChart = echarts.init(trendChartEl, echartsTheme);

		window.addEventListener('resize', handleResize);

		prevOwnerId = $sharedOwnerUserId;
		mounted = true;
		await loadAll();
	});

	async function loadAll() {
		await Promise.all([loadCategory(), loadMonth(), loadTrend()]);
	}

	async function loadCategory() {
		const oid = $sharedOwnerUserId || undefined;
		categoryData = await reports.byCategory({ type: reportType, owner_id: oid });
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

	function renderPie() {
		if (!pieChart) return;
		pieChart.setOption({
			backgroundColor: 'transparent',
			title: { text: `${reportType === 'expense' ? 'Expenses' : 'Income'} by Category`, left: 'center', textStyle: { color: getCssVar('--text') } },
			tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
			series: [{
				type: 'pie',
				radius: ['40%', '70%'],
				itemStyle: { borderRadius: 6, borderColor: getCssVar('--bg-card'), borderWidth: 2 },
				label: { color: getCssVar('--text') },
				data: categoryData.map(c => ({
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
