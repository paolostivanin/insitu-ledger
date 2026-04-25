<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
	import { transactions, categories, accounts, batch, csv, scheduled, type Transaction, type Category, type Account, type AutocompleteSuggestion } from '$lib/api/client';
	import CategoryPicker from '$lib/components/CategoryPicker.svelte';
	import ConfirmDialog from '$lib/components/ConfirmDialog.svelte';
	import AccountFilterPill from '$lib/components/AccountFilterPill.svelte';
	import { addToast } from '$lib/stores/toast';
	import { sharedOwnerUserId } from '$lib/stores/shared';
	import { currentAccountId } from '$lib/stores/accountFilter';
	import { currencySymbol } from '$lib/stores/auth';
	import { formatMoney } from '$lib/format';

	let txns = $state<Transaction[]>([]);
	let cats = $state<Category[]>([]);
	let accts = $state<Account[]>([]);
	let loading = $state(true);
	let showForm = $state(false);
	let editId = $state<number | null>(null);
	let error = $state('');
	let submitting = $state(false);

	// Confirm dialog
	let confirmOpen = $state(false);
	let confirmMessage = $state('');
	let confirmAction = $state(() => {});

	// Filters
	let filterFrom = $state('');
	let filterTo = $state('');
	let filterCat = $state('');

	// Sort
	let sortBy = $state('date');
	let sortDir = $state('desc');

	// Form fields
	let fType = $state<'income' | 'expense'>('expense');
	let fAccountId = $state(0);
	let fCategoryId = $state(0);
	let fAmount = $state(0);
	let fDescription = $state('');
	let fNote = $state('');
	let fDate = $state(new Date().toISOString().slice(0, 10));
	let fTime = $state(new Date().toTimeString().slice(0, 5));
	let fCurrency = $state('EUR');

	// Batch selection
	let selectedIds = $state<Set<number>>(new Set());
	let showBatchCategoryPicker = $state(false);
	let batchCategoryId = $state(0);

	// Autocomplete
	let suggestions = $state<AutocompleteSuggestion[]>([]);
	let showSuggestions = $state(false);
	let selectedSuggestionIndex = $state(-1);
	let debounceTimer: ReturnType<typeof setTimeout>;

	function onDescriptionInput() {
		clearTimeout(debounceTimer);
		if (fDescription.length < 2) {
			suggestions = [];
			showSuggestions = false;
			selectedSuggestionIndex = -1;
			return;
		}
		debounceTimer = setTimeout(async () => {
			try {
				suggestions = await transactions.autocomplete(fDescription, $sharedOwnerUserId || undefined);
				showSuggestions = suggestions.length > 0;
				selectedSuggestionIndex = -1;
			} catch {
				suggestions = [];
				showSuggestions = false;
				selectedSuggestionIndex = -1;
			}
		}, 200);
	}

	function onDescriptionKeydown(e: KeyboardEvent) {
		if (!showSuggestions || suggestions.length === 0) return;
		if (e.key === 'ArrowDown') {
			e.preventDefault();
			selectedSuggestionIndex = (selectedSuggestionIndex + 1) % suggestions.length;
		} else if (e.key === 'ArrowUp') {
			e.preventDefault();
			selectedSuggestionIndex = selectedSuggestionIndex <= 0 ? suggestions.length - 1 : selectedSuggestionIndex - 1;
		} else if (e.key === 'Enter' && selectedSuggestionIndex >= 0) {
			e.preventDefault();
			selectSuggestion(suggestions[selectedSuggestionIndex]);
		} else if (e.key === 'Escape') {
			showSuggestions = false;
			selectedSuggestionIndex = -1;
		}
	}

	function selectSuggestion(s: AutocompleteSuggestion) {
		fDescription = s.description;
		fCategoryId = s.category_id;
		const cat = cats.find(c => c.id === s.category_id);
		if (cat) fType = cat.type;
		suggestions = [];
		showSuggestions = false;
		selectedSuggestionIndex = -1;
	}

	// Import
	let importInput: HTMLInputElement | undefined = $state();

	// Pagination
	const PAGE_SIZE = 50;
	let hasMore = $state(true);
	let loadingMore = $state(false);

	let prevOwnerId: string | null = null;
	let prevAccountId: number | null = null;
	let mounted = false;

	$effect(() => {
		const currentOwnerId = $sharedOwnerUserId;
		const currentAcctId = $currentAccountId;
		if (mounted && (currentOwnerId !== prevOwnerId || currentAcctId !== prevAccountId)) {
			const ownerChanged = currentOwnerId !== prevOwnerId;
			prevOwnerId = currentOwnerId;
			prevAccountId = currentAcctId;
			ownerChanged ? loadAll() : load();
		}
	});

	onMount(() => {
		prevOwnerId = $sharedOwnerUserId;
		prevAccountId = $currentAccountId;
		mounted = true;
		loadAll();
		window.addEventListener('shortcut-new', onShortcutNew);
		window.addEventListener('shortcut-close', onShortcutClose);
	});

	onDestroy(() => {
		clearTimeout(debounceTimer);
		if (typeof window !== 'undefined') {
			window.removeEventListener('shortcut-new', onShortcutNew);
			window.removeEventListener('shortcut-close', onShortcutClose);
		}
	});

	function onShortcutNew() { resetForm(); showForm = true; }
	function onShortcutClose() { showForm = false; showBatchCategoryPicker = false; }

	async function loadReferenceData() {
		const oid = $sharedOwnerUserId || undefined;
		const [c, a] = await Promise.all([categories.list(oid), accounts.list(oid)]);
		cats = c;
		accts = a;
		if (accts.length && !fAccountId) fAccountId = getDefaultAccountId();
		if (cats.length && !fCategoryId) fCategoryId = cats[0].id;
	}

	async function loadAll() {
		loading = true;
		try {
			await Promise.all([loadTransactions(), loadReferenceData()]);
		} catch (e: any) {
			error = e.message;
		}
		loading = false;
	}

	async function load() {
		try {
			await loadTransactions();
		} catch (e: any) {
			error = e.message;
		}
	}

	async function loadTransactions() {
		const oid = $sharedOwnerUserId || undefined;
		const aid = $currentAccountId !== null ? $currentAccountId.toString() : undefined;
		txns = await transactions.list({ from: filterFrom, to: filterTo, category_id: filterCat, account_id: aid, limit: PAGE_SIZE.toString(), sort_by: sortBy, sort_dir: sortDir, owner_id: oid });
		hasMore = txns.length === PAGE_SIZE;
		selectedIds = new Set();
	}

	async function loadMore() {
		loadingMore = true;
		const oid = $sharedOwnerUserId || undefined;
		const aid = $currentAccountId !== null ? $currentAccountId.toString() : undefined;
		try {
			const more = await transactions.list({
				from: filterFrom, to: filterTo, category_id: filterCat, account_id: aid,
				limit: PAGE_SIZE.toString(), offset: txns.length.toString(),
				sort_by: sortBy, sort_dir: sortDir, owner_id: oid
			});
			txns = [...txns, ...more];
			hasMore = more.length === PAGE_SIZE;
		} catch (e: any) {
			error = e.message;
		}
		loadingMore = false;
	}

	function isSharedAcct(accountId: number): boolean {
		return !!accts.find(a => a.id === accountId)?.is_shared;
	}

	const hasAnyShared = $derived(accts.some(a => a.is_shared));

	function toggleSort(column: string) {
		if (sortBy === column) {
			sortDir = sortDir === 'desc' ? 'asc' : 'desc';
		} else {
			sortBy = column;
			sortDir = 'desc';
		}
		load();
	}

	function sortIndicator(column: string): string {
		if (sortBy !== column) return '';
		return sortDir === 'asc' ? ' \u25B2' : ' \u25BC';
	}

	function catName(id: number): string {
		return cats.find(c => c.id === id)?.name || '—';
	}

	function acctName(id: number): string {
		return accts.find(a => a.id === id)?.name || '—';
	}

	function fmt(n: number): string {
		return formatMoney(n, $currencySymbol);
	}

	function extractTime(dt: string): string {
		if (!dt.includes('T')) return '';
		return dt.slice(11, 16);
	}

	function getDefaultAccountId(): number {
		const lastUsed = localStorage.getItem('lastUsedAccountId');
		if (lastUsed) {
			const id = parseInt(lastUsed);
			if (accts.find(a => a.id === id)) return id;
		}
		return accts[0]?.id || 0;
	}

	function resetForm() {
		editId = null;
		fType = 'expense';
		fAmount = 0;
		fDescription = '';
		fNote = '';
		fDate = new Date().toISOString().slice(0, 10);
		fTime = new Date().toTimeString().slice(0, 5);
		fCurrency = 'EUR';
		if (accts.length) fAccountId = getDefaultAccountId();
		if (cats.length) fCategoryId = cats[0].id;
	}

	function startEdit(txn: Transaction) {
		editId = txn.id;
		fType = txn.type;
		fAccountId = txn.account_id;
		fCategoryId = txn.category_id;
		fAmount = txn.amount;
		fDescription = txn.description || '';
		fNote = txn.note || '';
		if (txn.date.includes('T')) {
			fDate = txn.date.slice(0, 10);
			fTime = txn.date.slice(11, 16);
		} else {
			fDate = txn.date;
			fTime = '00:00';
		}
		fCurrency = txn.currency;
		showForm = true;
	}

	async function submit(e: Event) {
		e.preventDefault();
		error = '';
		submitting = true;
		const data = {
			account_id: fAccountId,
			category_id: fCategoryId,
			type: fType,
			amount: fAmount,
			currency: fCurrency,
			description: fDescription || undefined,
			note: fNote || undefined,
			date: `${fDate}T${fTime}`
		};
		try {
			localStorage.setItem('lastUsedAccountId', fAccountId.toString());
			if (editId) {
				await transactions.update(editId, data);
			} else {
				const isFuture = new Date(`${fDate}T${fTime}`) > new Date();
				if (isFuture) {
					await scheduled.create({
						account_id: fAccountId,
						category_id: fCategoryId,
						type: fType,
						amount: fAmount,
						currency: fCurrency,
						description: fDescription || undefined,
						note: fNote || undefined,
						rrule: 'FREQ=DAILY',
						next_occurrence: `${fDate}T${fTime}`,
						max_occurrences: 1
					});
					addToast('Future-dated transaction scheduled — it will be created automatically on ' + fDate);
				} else {
					await transactions.create(data);
				}
			}
			showForm = false;
			resetForm();
			await load();
		} catch (e: any) {
			error = e.message;
		}
		submitting = false;
	}

	function remove(id: number) {
		confirmMessage = 'Delete this transaction?';
		confirmAction = async () => {
			await transactions.delete(id);
			await load();
		};
		confirmOpen = true;
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

	function batchDelete() {
		confirmMessage = `Delete ${selectedIds.size} transaction(s)?`;
		confirmAction = async () => {
			error = '';
			try {
				await batch.deleteTransactions([...selectedIds]);
				await load();
			} catch (e: any) {
				error = e.message;
			}
		};
		confirmOpen = true;
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
			await csv.exportTransactions({
				from: filterFrom,
				to: filterTo,
				owner_id: $sharedOwnerUserId || undefined,
				account_id: $currentAccountId !== null ? $currentAccountId.toString() : undefined
			});
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
			addToast(`Successfully imported ${result.imported} transaction(s).`);
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
			<AccountFilterPill accounts={accts} />
			<button class="btn-ghost" onclick={exportCsv}>Export CSV</button>
			<button class="btn-ghost" onclick={() => importInput?.click()}>Import CSV</button>
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
						<select id="currency" bind:value={fCurrency}>
							<option value="EUR">EUR</option>
							<option value="USD">USD</option>
							<option value="GBP">GBP</option>
							<option value="CHF">CHF</option>
							<option value="JPY">JPY</option>
							<option value="CAD">CAD</option>
							<option value="AUD">AUD</option>
							<option value="BRL">BRL</option>
							<option value="SEK">SEK</option>
							<option value="NOK">NOK</option>
							<option value="DKK">DKK</option>
							<option value="PLN">PLN</option>
							<option value="CZK">CZK</option>
							<option value="HUF">HUF</option>
							<option value="CNY">CNY</option>
							<option value="INR">INR</option>
							<option value="KRW">KRW</option>
						</select>
					</div>
				</div>
				<div class="form-row">
					<div class="form-group">
						<label for="account">Account</label>
						<select id="account" bind:value={fAccountId} onchange={() => localStorage.setItem('lastUsedAccountId', fAccountId.toString())}>
							{#each accts as a (a.id)}
								<option value={a.id}>{a.name}{a.is_shared ? ` (shared by ${a.owner_name})` : ''}</option>
							{/each}
						</select>
					</div>
					<div class="form-group">
						<label for="form-category">Category</label>
						<CategoryPicker
							id="form-category"
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
					<div class="form-group">
						<label for="time">Time</label>
						<input id="time" type="time" bind:value={fTime} required />
					</div>
				</div>
				<div class="form-group autocomplete-wrap">
					<label for="desc">Name</label>
					<input id="desc" type="text" bind:value={fDescription} maxlength="500"
						oninput={onDescriptionInput}
						onkeydown={onDescriptionKeydown}
						onfocusout={() => setTimeout(() => { showSuggestions = false; selectedSuggestionIndex = -1; }, 150)}
						placeholder="Optional" autocomplete="off" />
					{#if showSuggestions}
						<ul class="autocomplete-list">
							{#each suggestions as s, i (s.description)}
								<li>
									<button type="button" class:active={i === selectedSuggestionIndex} onmousedown={() => selectSuggestion(s)}>
										{s.description}
										<span class="autocomplete-cat">{catName(s.category_id)}</span>
									</button>
								</li>
							{/each}
						</ul>
					{/if}
				</div>
				<div class="form-group">
					<label for="note">Note</label>
					<textarea id="note" bind:value={fNote} maxlength="2000" rows="3"
						placeholder="Optional longer memo" autocomplete="off"></textarea>
				</div>
				<button class="btn-primary" type="submit" disabled={submitting}>{submitting ? 'Saving...' : editId ? 'Update' : 'Create'}</button>
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
					{#each cats as c (c.id)}
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
					{#each cats as c (c.id)}
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
						<th>Date</th><th>Time</th><th>Type</th><th>Category</th><th>Account</th><th>Name</th>
						{#if hasAnyShared}<th>Added by</th>{/if}
						<th>Amount</th><th></th>
					</tr>
				</thead>
				<tbody>
					{#each Array(5) as _, i (i)}
						<tr class="skeleton-row">
							<td><div class="skeleton" style="width:16px;height:16px"></div></td>
							<td><div class="skeleton" style="width:80px"></div></td>
							<td><div class="skeleton" style="width:50px"></div></td>
							<td><div class="skeleton" style="width:60px"></div></td>
							<td><div class="skeleton" style="width:80px"></div></td>
							<td><div class="skeleton" style="width:70px"></div></td>
							<td><div class="skeleton" style="width:120px"></div></td>
							{#if hasAnyShared}<td><div class="skeleton" style="width:60px"></div></td>{/if}
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
						<th class="sortable" onclick={() => toggleSort('date')}>Date{sortIndicator('date')}</th>
						<th>Time</th>
						<th>Type</th>
						<th class="sortable" onclick={() => toggleSort('category')}>Category{sortIndicator('category')}</th>
						<th>Account</th>
						<th class="sortable" onclick={() => toggleSort('description')}>Name{sortIndicator('description')}</th>
						{#if hasAnyShared}<th>Added by</th>{/if}
						<th class="sortable" onclick={() => toggleSort('amount')}>Amount{sortIndicator('amount')}</th>
						<th></th>
					</tr>
				</thead>
				<tbody>
					{#each txns as txn (txn.id)}
						<tr>
							<td class="check-col">
								<input type="checkbox" checked={selectedIds.has(txn.id)} onchange={() => toggleSelect(txn.id)} />
							</td>
							<td>{txn.date.slice(0, 10)}</td>
							<td class="time-cell">{extractTime(txn.date) || '—'}</td>
							<td><span class="badge {txn.type === 'income' ? 'badge-income' : 'badge-expense'}">{txn.type}</span></td>
							<td>{catName(txn.category_id)}</td>
							<td>{acctName(txn.account_id)}</td>
							<td>{txn.description || '—'}</td>
							{#if hasAnyShared}
								<td class="added-by">{isSharedAcct(txn.account_id) ? (txn.created_by_name || '—') : ''}</td>
							{/if}
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

<ConfirmDialog bind:open={confirmOpen} message={confirmMessage} onconfirm={confirmAction} />

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
	.added-by {
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
	.sortable {
		cursor: pointer;
		user-select: none;
	}
	.sortable:hover {
		color: var(--primary);
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
	.autocomplete-list li button:hover,
	.autocomplete-list li button.active {
		background: var(--bg-hover);
	}
	.autocomplete-cat {
		font-size: 0.8rem;
		color: var(--text-muted);
		margin-left: 1rem;
	}
</style>
