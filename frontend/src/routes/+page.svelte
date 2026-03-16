<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { isAuthenticated, accounts, transactions, reports, type Account, type Transaction, type CategoryReport, type MonthReport } from '$lib/api/client';

	let accts = $state<Account[]>([]);
	let recentTxns = $state<Transaction[]>([]);
	let categoryData = $state<CategoryReport[]>([]);
	let monthData = $state<MonthReport[]>([]);
	let loading = $state(true);

	onMount(async () => {
		if (!isAuthenticated()) {
			goto('/login');
			return;
		}
		try {
			const [a, t, c, m] = await Promise.all([
				accounts.list(),
				transactions.list({ limit: '10' }),
				reports.byCategory({ type: 'expense' }),
				reports.byMonth({ year: new Date().getFullYear().toString() })
			]);
			accts = a;
			recentTxns = t;
			categoryData = c;
			monthData = m;
		} catch {
			// handled by api client redirect
		}
		loading = false;
	});

	function totalBalance(): number {
		return accts.reduce((sum, a) => sum + a.balance, 0);
	}

	function thisMonthExpenses(): number {
		const key = new Date().toISOString().slice(0, 7);
		return monthData.filter(m => m.month === key && m.type === 'expense').reduce((s, m) => s + m.total, 0);
	}

	function thisMonthIncome(): number {
		const key = new Date().toISOString().slice(0, 7);
		return monthData.filter(m => m.month === key && m.type === 'income').reduce((s, m) => s + m.total, 0);
	}

	function fmt(n: number): string {
		return n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
	}
</script>

<div class="page">
	{#if loading}
		<p class="text-muted">Loading...</p>
	{:else}
		<h1>Dashboard</h1>

		<div class="stats-grid">
			<div class="stat-card card">
				<span class="stat-label">Total Balance</span>
				<span class="stat-value">{fmt(totalBalance())}</span>
			</div>
			<div class="stat-card card">
				<span class="stat-label">This Month Income</span>
				<span class="stat-value amount-income">+{fmt(thisMonthIncome())}</span>
			</div>
			<div class="stat-card card">
				<span class="stat-label">This Month Expenses</span>
				<span class="stat-value amount-expense">-{fmt(thisMonthExpenses())}</span>
			</div>
			<div class="stat-card card">
				<span class="stat-label">Accounts</span>
				<span class="stat-value">{accts.length}</span>
			</div>
		</div>

		<div class="grid-2" style="margin-top: 1.5rem">
			<div class="card">
				<h2>Recent Transactions</h2>
				{#if recentTxns.length === 0}
					<p class="empty-state">No transactions yet</p>
				{:else}
					<div class="table-wrap">
						<table>
							<thead>
								<tr><th>Date</th><th>Description</th><th>Amount</th></tr>
							</thead>
							<tbody>
								{#each recentTxns as txn}
									<tr>
										<td>{txn.date}</td>
										<td>{txn.description || '—'}</td>
										<td class={txn.type === 'income' ? 'amount-income' : 'amount-expense'}>
											{txn.type === 'income' ? '+' : '-'}{fmt(txn.amount)}
										</td>
									</tr>
								{/each}
							</tbody>
						</table>
					</div>
				{/if}
			</div>

			<div class="card">
				<h2>Top Expense Categories</h2>
				{#if categoryData.length === 0}
					<p class="empty-state">No data yet</p>
				{:else}
					<div class="category-list">
						{#each categoryData.slice(0, 8) as cat}
							<div class="cat-row">
								<span class="cat-dot" style="background: {cat.category_color || '#6366f1'}"></span>
								<span class="cat-name">{cat.category_name}</span>
								<span class="cat-amount amount-expense">{fmt(cat.total)}</span>
							</div>
						{/each}
					</div>
				{/if}
			</div>
		</div>
	{/if}
</div>

<style>
	.stats-grid {
		display: grid;
		grid-template-columns: repeat(4, 1fr);
		gap: 1rem;
	}
	.stat-card {
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
	}
	.stat-label {
		font-size: 0.8rem;
		color: var(--text-muted);
		text-transform: uppercase;
		letter-spacing: 0.05em;
	}
	.stat-value {
		font-size: 1.5rem;
		font-weight: 700;
	}
	h2 {
		font-size: 1rem;
		margin-bottom: 1rem;
	}
	.category-list {
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
	}
	.cat-row {
		display: flex;
		align-items: center;
		gap: 0.75rem;
	}
	.cat-dot {
		width: 10px;
		height: 10px;
		border-radius: 50%;
		flex-shrink: 0;
	}
	.cat-name {
		flex: 1;
	}
	.cat-amount {
		font-weight: 600;
	}
	@media (max-width: 768px) {
		.stats-grid {
			grid-template-columns: repeat(2, 1fr);
		}
	}
</style>
