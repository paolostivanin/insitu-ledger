<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
	import { transactions, categories, accounts, batch, csv, type Transaction, type Category, type Account, type AutocompleteSuggestion } from '$lib/api/client';
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

	// Batch selection
	let selectedIds = $state<Set<number>>(new Set());
	let showBatchCategoryPicker = $state(false);
	let batchCategoryId = $state(0);

	// Autocomplete
	let suggestions = $state<AutocompleteSuggestion[]>([]);
	let showSuggestions = $state(false);
	let debounceTimer: ReturnType<typeof setTimeout>;

	async function onDescriptionInput(value: string) {
		fDescription = value;
		clearTimeout(debounceTimer);
		if (value.length < 2) {
			suggestions = [];
			showSuggestions = false;
			return;
		}
		debounceTimer = setTimeout(async () => {
			try {
				suggestions = await transactions.autocomplete(value);
				showSuggestions = suggestions.length > 0;
			} catch {
				suggestions = [];
				showSuggestions = false;
			}
		}, 200);
	}

	function selectSuggestion(s: AutocompleteSuggestion) {
		fDescription = s.description;
		fCategoryId = s.category_id;
		suggestions = [];
		showSuggestions = false;
	}

	// Import
	let importInput: HTMLInputElement;

	// Pagination
	const PAGE_SIZE = 50;
	let hasMore = $state(true);
	let loadingMore = $state(false);

	onMount(() => {
		load();
		window.addEventListener('shortcut-new', onShortcutNew);
		window.addEventListener('shortcut-close', onShortcutClose);
	});

	onDestroy(() => {
		if (typeof window !== 'undefined') {
			window.removeEventListener('shortcut-new', onShortcutNew);
			window.removeEventListener('shortcut-close', onShortcutClose);
		}
	});

	function onShortcutNew() { resetForm(); showForm = true; }
	function onShortcutClose() { showForm = false; showBatchCategoryPicker = false; }

	async function load() {
		loading = true;
		try {
			const [t, c, a] = await Promise.all([
				transactions.list({ from: filterFrom, to: filterTo, category_id: filterCat, limit: PAGE_SIZE.toString() }),
				categories.list(),
				accounts.list()
			]);
			txns = t;
			cats = c;
			accts = a;
			hasMore = t.length === PAGE_SIZE;
			if (accts.length && !fAccountId) fAccountId = accts[0].id;
			if (cats.length && !fCategoryId) fCategoryId = cats[0].id;
		} catch (e: any) {
			error = e.message;
		}
		loading = false;
		selectedIds = new Set();
	}

	async function loadMore() {
		loadingMore = true;
		try {
			const more = await transactions.list({
				from: filterFrom, to: filterTo, category_id: filterCat,
				limit: PAGE_SIZE.toString(), offset: txns.length.toString()
			});
			txns = [...txns, ...more];
			hasMore = more.length === PAGE_SIZE;
		} catch (e: any) {
			error = e.message;
		}
		loadingMore = false;
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

	// Batch operations
	function toggleSelect(id: number) {
		const next = new Set(selectedIds);
		if (next.has(id)) next.delete(id);
		else next.add(id);
		selectedIds = next;
	}

	function toggleSelectAll() {
		if (selectedIds.size === txns.length) {
			selectedIds = new Set();
		} else {
			selectedIds = new Set(txns.map(t => t.id));
		}
	}

	async function batchDelete() {
		if (!confirm(`Delete ${selectedIds.size} transaction(s)?`)) return;
		error = '';
		try {
			await batch.deleteTransactions([...selectedIds]);
			await load();
		} catch (e: any) {
			error = e.message;
		}
	}

	async function batchChangeCategory() {
		if (!batchCategoryId) return;
		error = '';
		try {
			await batch.updateCategory([...selectedIds], batchCategoryId);
			showBatchCategoryPicker = false;
			await load();
		} catch (e: any) {
			error = e.message;
		}
	}

	// CSV export/import
	async function exportCsv() {
		error = '';
		try {
			await csv.exportTransactions({ from: filterFrom, to: filterTo });
		} catch (e: any) {
			error = e.message;
		}
	}

	async function importCsv(e: Event) {
		const input = e.target as HTMLInputElement;
		const file = input.files?.[0];
		if (!file) return;
		error = '';
		try {
			const result = await csv.importTransactions(file);
			alert(`Successfully imported ${result.imported} transaction(s).`);
			await load();
		} catch (e: any) {
			error = e.message;
		}
		input.value = '';
	}
</script>

<div class="page">
	<div class="page-header">
		<h1>Transactions</h1>
		<div class="header-actions">
			<button class="btn-ghost" onclick={exportCsv}>Export CSV</button>
			<button class="btn-ghost" onclick={() => importInput.click()}>Import CSV</button>
			<input type="file" accept=".csv" bind:this={importInput} onchange={importCsv} style="display:none" />
			<button class="btn-primary" onclick={() => { resetForm(); showForm = !showForm; }}>
				{showForm ? 'Cancel' : '+ New Transaction'}
			</button>
		</div>
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
				<div class="form-group autocomplete-wrap">
					<label for="desc">Description</label>
					<input id="desc" type="text" value={fDescription}
						oninput={(e) => onDescriptionInput((e.target as HTMLInputElement).value)}
						onfocusout={() => setTimeout(() => { showSuggestions = false; }, 150)}
						placeholder="Optional" autocomplete="off" />
					{#if showSuggestions}
						<ul class="autocomplete-list">
							{#each suggestions as s}
								<li>
									<button type="button" onmousedown={() => selectSuggestion(s)}>
										{s.description}
										<span class="autocomplete-cat">{catName(s.category_id)}</span>
									</button>
								</li>
							{/each}
						</ul>
					{/if}
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

	{#if selectedIds.size > 0}
		<div class="batch-bar card">
			<span>{selectedIds.size} selected</span>
			<button class="btn-danger" onclick={batchDelete}>Delete Selected</button>
			<button class="btn-ghost" onclick={() => { showBatchCategoryPicker = !showBatchCategoryPicker; batchCategoryId = cats[0]?.id || 0; }}>
				Change Category
			</button>
			{#if showBatchCategoryPicker}
				<select bind:value={batchCategoryId}>
					{#each cats as c}
						<option value={c.id}>{c.name}</option>
					{/each}
				</select>
				<button class="btn-primary" onclick={batchChangeCategory}>Apply</button>
			{/if}
			<button class="btn-ghost" onclick={() => { selectedIds = new Set(); }}>Clear</button>
		</div>
	{/if}

	{#if loading}
		<div class="card table-wrap">
			<table>
				<thead>
					<tr>
						<th class="check-col"></th>
						<th>Date</th><th>Time</th><th>Type</th><th>Category</th><th>Account</th><th>Description</th><th>Amount</th><th></th>
					</tr>
				</thead>
				<tbody>
					{#each Array(5) as _}
						<tr class="skeleton-row">
							<td><div class="skeleton" style="width:16px;height:16px"></div></td>
							<td><div class="skeleton" style="width:80px"></div></td>
							<td><div class="skeleton" style="width:50px"></div></td>
							<td><div class="skeleton" style="width:60px"></div></td>
							<td><div class="skeleton" style="width:80px"></div></td>
							<td><div class="skeleton" style="width:70px"></div></td>
							<td><div class="skeleton" style="width:120px"></div></td>
							<td><div class="skeleton" style="width:80px"></div></td>
							<td></td>
						</tr>
					{/each}
				</tbody>
			</table>
		</div>
	{:else if txns.length === 0}
		<p class="empty-state">No transactions found</p>
	{:else}
		<div class="card table-wrap">
			<table>
				<thead>
					<tr>
						<th class="check-col">
							<input type="checkbox" checked={selectedIds.size === txns.length && txns.length > 0} onchange={toggleSelectAll} />
						</th>
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
							<td class="check-col">
								<input type="checkbox" checked={selectedIds.has(txn.id)} onchange={() => toggleSelect(txn.id)} />
							</td>
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

		{#if hasMore}
			<div class="load-more">
				<button class="btn-ghost" onclick={loadMore} disabled={loadingMore}>
					{loadingMore ? 'Loading...' : 'Load More'}
				</button>
			</div>
		{/if}
	{/if}
</div>

<style>
	.page-header {
		display: flex;
		justify-content: space-between;
		align-items: center;
		margin-bottom: 1rem;
	}
	.header-actions {
		display: flex;
		gap: 0.5rem;
		align-items: center;
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
	.check-col {
		width: 40px;
		text-align: center;
	}
	.batch-bar {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		margin-bottom: 1rem;
		padding: 0.75rem 1.5rem;
	}
	.batch-bar span {
		font-weight: 500;
		font-size: 0.9rem;
	}
	.skeleton {
		height: 14px;
		background: var(--bg-hover);
		border-radius: 4px;
		animation: pulse 1.5s ease-in-out infinite;
	}
	@keyframes pulse {
		0%, 100% { opacity: 0.4; }
		50% { opacity: 0.8; }
	}
	.load-more {
		text-align: center;
		margin-top: 1rem;
	}
	.autocomplete-wrap {
		position: relative;
	}
	.autocomplete-list {
		position: absolute;
		top: 100%;
		left: 0;
		right: 0;
		background: var(--bg-card);
		border: 1px solid var(--border);
		border-radius: 6px;
		margin: 2px 0 0;
		padding: 0;
		list-style: none;
		z-index: 10;
		max-height: 200px;
		overflow-y: auto;
		box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
	}
	.autocomplete-list li button {
		display: flex;
		justify-content: space-between;
		align-items: center;
		width: 100%;
		padding: 0.5rem 0.75rem;
		background: none;
		border: none;
		cursor: pointer;
		font-size: 0.9rem;
		color: var(--text);
		text-align: left;
	}
	.autocomplete-list li button:hover {
		background: var(--bg-hover);
	}
	.autocomplete-cat {
		font-size: 0.8rem;
		color: var(--text-muted);
		margin-left: 1rem;
	}
</style>
