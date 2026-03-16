<script lang="ts">
	import { onMount } from 'svelte';
	import { transactions, categories, accounts, type Transaction, type Category, type Account } from '$lib/api/client';
	import CategoryPicker from '$lib/components/CategoryPicker.svelte';

	let txns = $state<Transaction[]>([]);
	let cats = $state<Category[]>([]);
	let accts = $state<Account[]>([]);
	let loading = $state(true);
	let showForm = $state(false);
	let editId = $state<number | null>(null);
	let error = $state('');

	// Filters
	let filterFrom = $state('');
	let filterTo = $state('');
	let filterCat = $state('');

	// Form fields
	let fType = $state<'income' | 'expense'>('expense');
	let fAccountId = $state(0);
	let fCategoryId = $state(0);
	let fAmount = $state(0);
	let fDescription = $state('');
	let fDate = $state(new Date().toISOString().slice(0, 10));
	let fCurrency = $state('EUR');

	onMount(load);

	async function load() {
		loading = true;
		try {
			const [t, c, a] = await Promise.all([
				transactions.list({ from: filterFrom, to: filterTo, category_id: filterCat }),
				categories.list(),
				accounts.list()
			]);
			txns = t;
			cats = c;
			accts = a;
			if (accts.length && !fAccountId) fAccountId = accts[0].id;
			if (cats.length && !fCategoryId) fCategoryId = cats[0].id;
		} catch (e: any) {
			error = e.message;
		}
		loading = false;
	}

	function catName(id: number): string {
		return cats.find(c => c.id === id)?.name || '—';
	}

	function acctName(id: number): string {
		return accts.find(a => a.id === id)?.name || '—';
	}

	function fmt(n: number): string {
		return n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
	}

	function fmtTime(dt: string): string {
		const d = new Date(dt);
		if (isNaN(d.getTime())) return '';
		return d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
	}

	function resetForm() {
		editId = null;
		fType = 'expense';
		fAmount = 0;
		fDescription = '';
		fDate = new Date().toISOString().slice(0, 10);
		fCurrency = 'EUR';
		if (accts.length) fAccountId = accts[0].id;
		if (cats.length) fCategoryId = cats[0].id;
	}

	function startEdit(txn: Transaction) {
		editId = txn.id;
		fType = txn.type;
		fAccountId = txn.account_id;
		fCategoryId = txn.category_id;
		fAmount = txn.amount;
		fDescription = txn.description || '';
		fDate = txn.date;
		fCurrency = txn.currency;
		showForm = true;
	}

	async function submit(e: Event) {
		e.preventDefault();
		error = '';
		const data = {
			account_id: fAccountId,
			category_id: fCategoryId,
			type: fType,
			amount: fAmount,
			currency: fCurrency,
			description: fDescription || undefined,
			date: fDate
		};
		try {
			if (editId) {
				await transactions.update(editId, data);
			} else {
				await transactions.create(data);
			}
			showForm = false;
			resetForm();
			await load();
		} catch (e: any) {
			error = e.message;
		}
	}

	async function remove(id: number) {
		if (!confirm('Delete this transaction?')) return;
		await transactions.delete(id);
		await load();
	}

	function filteredCats(): Category[] {
		return cats.filter(c => c.type === fType);
	}

	function handleCategoryCreated(cat: Category) {
		cats = [...cats, cat];
	}
</script>

<div class="page">
	<div class="page-header">
		<h1>Transactions</h1>
		<button class="btn-primary" onclick={() => { resetForm(); showForm = !showForm; }}>
			{showForm ? 'Cancel' : '+ New Transaction'}
		</button>
	</div>

	{#if error}
		<p class="error-msg">{error}</p>
	{/if}

	{#if showForm}
		<div class="card" style="margin-bottom: 1.5rem">
			<h2>{editId ? 'Edit' : 'New'} Transaction</h2>
			<form onsubmit={submit}>
				<div class="form-row">
					<div class="form-group">
						<label for="type">Type</label>
						<select id="type" bind:value={fType}>
							<option value="expense">Expense</option>
							<option value="income">Income</option>
						</select>
					</div>
					<div class="form-group">
						<label for="amount">Amount</label>
						<input id="amount" type="number" step="0.01" min="0.01" bind:value={fAmount} required />
					</div>
					<div class="form-group">
						<label for="currency">Currency</label>
						<input id="currency" type="text" bind:value={fCurrency} />
					</div>
				</div>
				<div class="form-row">
					<div class="form-group">
						<label for="account">Account</label>
						<select id="account" bind:value={fAccountId}>
							{#each accts as a}
								<option value={a.id}>{a.name}</option>
							{/each}
						</select>
					</div>
					<div class="form-group">
						<label>Category</label>
						<CategoryPicker
							cats={cats}
							type={fType}
							value={fCategoryId}
							onchange={(id) => fCategoryId = id}
							onCreated={handleCategoryCreated}
						/>
					</div>
					<div class="form-group">
						<label for="date">Date</label>
						<input id="date" type="date" bind:value={fDate} required />
					</div>
				</div>
				<div class="form-group">
					<label for="desc">Description</label>
					<input id="desc" type="text" bind:value={fDescription} placeholder="Optional" />
				</div>
				<button class="btn-primary" type="submit">{editId ? 'Update' : 'Create'}</button>
			</form>
		</div>
	{/if}

	<div class="card filters">
		<div class="form-row">
			<div class="form-group">
				<label for="ff">From</label>
				<input id="ff" type="date" bind:value={filterFrom} onchange={() => load()} />
			</div>
			<div class="form-group">
				<label for="ft">To</label>
				<input id="ft" type="date" bind:value={filterTo} onchange={() => load()} />
			</div>
			<div class="form-group">
				<label for="fc">Category</label>
				<select id="fc" bind:value={filterCat} onchange={() => load()}>
					<option value="">All</option>
					{#each cats as c}
						<option value={c.id.toString()}>{c.name}</option>
					{/each}
				</select>
			</div>
		</div>
	</div>

	{#if loading}
		<p>Loading...</p>
	{:else if txns.length === 0}
		<p class="empty-state">No transactions found</p>
	{:else}
		<div class="card table-wrap">
			<table>
				<thead>
					<tr>
						<th>Date</th>
						<th>Time</th>
						<th>Type</th>
						<th>Category</th>
						<th>Account</th>
						<th>Description</th>
						<th>Amount</th>
						<th></th>
					</tr>
				</thead>
				<tbody>
					{#each txns as txn}
						<tr>
							<td>{txn.date}</td>
							<td class="time-cell">{fmtTime(txn.created_at)}</td>
							<td><span class="badge {txn.type === 'income' ? 'badge-income' : 'badge-expense'}">{txn.type}</span></td>
							<td>{catName(txn.category_id)}</td>
							<td>{acctName(txn.account_id)}</td>
							<td>{txn.description || '—'}</td>
							<td class={txn.type === 'income' ? 'amount-income' : 'amount-expense'}>
								{txn.type === 'income' ? '+' : '-'}{fmt(txn.amount)} {txn.currency}
							</td>
							<td class="actions">
								<button class="btn-ghost" onclick={() => startEdit(txn)}>Edit</button>
								<button class="btn-danger" onclick={() => remove(txn.id)}>Del</button>
							</td>
						</tr>
					{/each}
				</tbody>
			</table>
		</div>
	{/if}
</div>

<style>
	.page-header {
		display: flex;
		justify-content: space-between;
		align-items: center;
		margin-bottom: 1rem;
	}
	h2 {
		font-size: 1rem;
		margin-bottom: 1rem;
	}
	.filters {
		margin-bottom: 1rem;
	}
	.time-cell {
		color: var(--text-muted);
		font-size: 0.85rem;
	}
</style>
