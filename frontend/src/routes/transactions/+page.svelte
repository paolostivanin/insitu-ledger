<script lang="ts">
	import { onMount, onDestroy, untrack } from 'svelte';
	import { page } from '$app/stores';
	import { transactions, categories, accounts, batch, csv, scheduled, type Transaction, type Category, type Account, type AutocompleteSuggestion, type ScheduledTransaction, type TransactionListParams, type BatchUpdateInput, type BatchUpdateResult } from '$lib/api/client';
	import CategoryPicker from '$lib/components/CategoryPicker.svelte';
	import ConfirmDialog from '$lib/components/ConfirmDialog.svelte';
	import BulkEditDialog from '$lib/components/BulkEditDialog.svelte';
	import AccountFilterPill from '$lib/components/AccountFilterPill.svelte';
	import TransactionName from '$lib/components/TransactionName.svelte';
	import { addToast } from '$lib/stores/toast';
	import { sharedOwnerUserId } from '$lib/stores/shared';
	import { currentAccountId } from '$lib/stores/accountFilter';
	import { currencySymbol } from '$lib/stores/auth';
	import { formatMoney } from '$lib/format';
	import { extractTime, localDateInputValue, toIsoOffset } from '$lib/datetime';
	import { indexSchedules } from '$lib/series';
	import { BULK_CAP, SELECT_ALL_PAGE_SIZE, bulkResultMessage, fetchAllMatching, missingScheduleIds } from '$lib/bulkEdit';
	import { categoryForType, formCategoryError, keepFormCategory, type OriginalCategory } from '$lib/categoryTree';

	let txns = $state<Transaction[]>([]);
	let cats = $state<Category[]>([]);
	let accts = $state<Account[]>([]);
	// Only resolves the series label on recurring rows; never gates the list.
	let scheduleById = $state<Map<number, ScheduledTransaction>>(new Map());
	let loading = $state(true);
	let showForm = $state(false);
	let editId = $state<number | null>(null);
	let error = $state('');
	let loadError = $state('');
	let submitting = $state(false);
	// The edited entry's category as loaded; it may be soft-deleted (unlisted).
	let editOriginal: OriginalCategory | null = null;

	// Confirm dialog
	let confirmOpen = $state(false);
	let confirmMessage = $state('');
	let confirmAction = $state(() => {});
	// A batch-delete confirmation names the selection it was opened for, so it
	// closes with that selection unless its request is already running.
	let confirmIsBatch = false;
	let batchDeleting = false;

	// Filters
	let filterFrom = $state('');
	let filterTo = $state('');
	let filterCat = $state('');
	let filterQ = $state('');
	// Whole-name match instead of substring; sent only with a non-blank search.
	let exactMatch = $state(false);
	// Only entries I added; offered only when an account is shared.
	let addedByMe = $state(false);
	// Distinct from the form's `debounceTimer` below — sharing one would make the
	// search box and the description autocomplete cancel each other.
	let searchDebounceTimer: ReturnType<typeof setTimeout>;
	// Monotonic request counter: debounced typing makes out-of-order responses
	// likely (type "cof" then "coffee"; the slower "cof" must not win).
	let loadSeq = 0;
	// The rows on screen no longer match the filters: a reload is pending, in
	// flight or failed. Until the latest load succeeds nothing may select or
	// act on them, or page more rows onto them.
	let listStale = $state(false);

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
	let fDate = $state(localDateInputValue());
	let fTime = $state(new Date().toTimeString().slice(0, 5));
	let fCurrency = $state('EUR');

	// Batch selection, keyed by id. Always reassign (never mutate) so it stays
	// reactive. It may hold rows beyond the loaded pages ("select all matching").
	let selected = $state<Map<number, Transaction>>(new Map());
	// "Select all matching": bumping the token discards an in-flight fetch.
	let selectAllToken = 0;
	let selectAllState = $state<'idle' | 'loading' | 'all' | 'tooMany'>('idle');
	// Bulk edit dialog; bulkRows is the selection snapshotted when it opened.
	let bulkOpen = $state(false);
	let bulkRows = $state<Transaction[]>([]);
	let bulkApplying = $state(false);
	// Refetching schedules before opening (see openBulkEdit).
	let bulkPreparing = $state(false);
	// Bumped whenever the dialog opens or the page closes it: a request whose
	// session is over reports through a toast, not the dialog.
	let bulkSession = 0;
	// Missing schedules would make "N linked recurring schedules" undercount.
	const bulkSchedulesMissing = $derived(missingScheduleIds(bulkRows, scheduleById).length > 0);

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
		// Reuse the recurring amount, but never clobber a value the user already typed.
		if (s.amount != null && (!fAmount || fAmount === 0)) {
			fAmount = s.amount;
			if (s.currency) fCurrency = s.currency;
		}
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
		const initialCat = $page.url.searchParams.get('category_id');
		if (initialCat) filterCat = initialCat;
		loadAll();
		window.addEventListener('shortcut-new', onShortcutNew);
		window.addEventListener('shortcut-close', onShortcutClose);
	});

	onDestroy(() => {
		clearTimeout(debounceTimer);
		clearTimeout(searchDebounceTimer);
		if (typeof window !== 'undefined') {
			window.removeEventListener('shortcut-new', onShortcutNew);
			window.removeEventListener('shortcut-close', onShortcutClose);
		}
	});

	function onShortcutNew() { resetForm(); showForm = true; }
	function onShortcutClose() {
		if (bulkOpen) {
			// Like the dialog's own Escape handling: never abandon a request in flight.
			if (!bulkApplying) bulkOpen = false;
			return;
		}
		showForm = false;
	}

	let referenceSeq = 0;
	let pageLoadSeq = 0;
	async function loadReferenceData() {
		const seq = ++referenceSeq;
		cats = []; accts = [];
		const oid = $sharedOwnerUserId || undefined;
		const [c, a] = await Promise.all([categories.list(oid), accounts.list(oid)]);
		if (seq !== referenceSeq) return;
		cats = c;
		accts = a;
		if (!accts.some(a => a.id === fAccountId)) fAccountId = getDefaultAccountId();
		if (!keepFormCategory(cats, fCategoryId, editId !== null ? editOriginal : null)) {
			fCategoryId = categoryForType(cats, fType, 0);
		}
	}

	// Fire-and-forget, once per owner (schedules aren't filtered by account or
	// search): a failure just leaves recurring rows on the 'Recurring' fallback.
	// refresh: same owner, so keep the current labels until the answer lands.
	let scheduleSeq = 0;
	async function loadSchedules(refresh = false) {
		const seq = ++scheduleSeq;
		if (!refresh) scheduleById = new Map();
		try {
			// Finished series are soft-deleted; include them so their entries
			// still read "Monthly · 3/3" rather than "Recurring · 3/3".
			const list = await scheduled.list({ owner_id: $sharedOwnerUserId || undefined, include_deleted: '1' });
			if (seq === scheduleSeq) scheduleById = indexSchedules(list);
		} catch { /* keep the fallback label */ }
	}

	async function loadAll() {
		const seq = ++pageLoadSeq;
		loading = true;
		txns = [];
		loadError = '';
		void loadSchedules();
		try {
			const results = await Promise.allSettled([loadTransactions(), loadReferenceData()]);
			const failure = results.find(result => result.status === 'rejected');
			if (failure?.status === 'rejected') throw failure.reason;
		} catch (e: any) {
			if (seq === pageLoadSeq) loadError = e.message;
		} finally {
			if (seq === pageLoadSeq) loading = false;
		}
	}

	// keepError: reload without hiding a reference-data failure still on screen.
	async function load(keepError = false) {
		const seq = loadSeq + 1;
		if (!keepError) loadError = '';
		try {
			await loadTransactions();
		} catch (e: any) {
			if (seq === loadSeq) loadError = e.message;
		}
	}

	// The one place the list filters are turned into query params: the list,
	// Load More, "select all matching" and the CSV export must agree, or a bulk
	// edit could reach entries that aren't in the list the user is looking at.
	function listParams(): TransactionListParams {
		const params: TransactionListParams = {
			from: filterFrom,
			to: filterTo,
			category_id: filterCat,
			q: filterQ,
			account_id: $currentAccountId !== null ? $currentAccountId.toString() : undefined,
			owner_id: $sharedOwnerUserId || undefined,
			sort_by: sortBy,
			sort_dir: sortDir
		};
		if (exactMatch && filterQ.trim() !== '') params.match = 'exact';
		if (addedByMe) params.added_by = 'me';
		return params;
	}

	// Drop the selection and anything acting on it. Runs synchronously whenever
	// the result set is about to change.
	function clearSelection() {
		selected = new Map();
		selectAllToken++;
		selectAllState = 'idle';
		if (bulkOpen) {
			bulkOpen = false;
			bulkSession++;
		}
		if (confirmOpen && confirmIsBatch && !batchDeleting) confirmOpen = false;
	}

	async function loadTransactions() {
		// Before the await: a selection made against the old filters must never
		// survive into (or act on) the new result set.
		clearSelection();
		listStale = true;
		const params = { ...listParams(), limit: PAGE_SIZE.toString() };
		const seq = ++loadSeq;
		let rows: Transaction[];
		try {
			rows = await transactions.list(params);
		} catch (e) {
			if (seq !== loadSeq) return;
			throw e;
		}
		// A newer request has already been issued — drop this stale response.
		if (seq !== loadSeq) return;
		txns = rows;
		hasMore = txns.length === PAGE_SIZE;
		listStale = false;
		// Rows ticked on the old list while this request was in flight.
		clearSelection();
	}

	function onSearchInput() {
		clearSelection();
		// The search box no longer matches the rows, even before the debounce.
		listStale = true;
		clearTimeout(searchDebounceTimer);
		searchDebounceTimer = setTimeout(() => void load(), 300);
	}

	function onExactMatchChange() {
		// Without a search term the toggle changes nothing that is sent.
		if (filterQ.trim() === '') return;
		clearTimeout(searchDebounceTimer);
		void load();
	}

	// Without a shared account every entry is mine and the toggle is hidden;
	// don't leave it applied as an invisible filter (e.g. after switching to an
	// owner with no shared accounts). Waits for reference data to settle, as
	// accounts are briefly empty while they reload.
	$effect(() => {
		if (addedByMe && !hasAnyShared && !loading) {
			untrack(() => {
				addedByMe = false;
				void load(true);
			});
		}
	});

	async function loadMore() {
		// Paging a stale list would append the new filters' rows at the old
		// offset, mixing two result sets.
		if (listStale || loadingMore) return;
		loadingMore = true;
		// Observe, don't claim, the sequence: if the filters change mid-flight
		// this page belongs to the previous result set and must be dropped.
		const seq = loadSeq;
		try {
			const more = await transactions.list({
				...listParams(),
				limit: PAGE_SIZE.toString(), offset: txns.length.toString()
			});
			if (seq !== loadSeq) return;
			txns = [...txns, ...more];
			hasMore = more.length === PAGE_SIZE;
		} catch (e: any) {
			if (seq === loadSeq) loadError = e.message;
		} finally {
			loadingMore = false;
		}
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
		editOriginal = null;
		fType = 'expense';
		fAmount = 0;
		fDescription = '';
		fNote = '';
		fDate = localDateInputValue();
		fTime = new Date().toTimeString().slice(0, 5);
		fCurrency = 'EUR';
		if (accts.length) fAccountId = getDefaultAccountId();
		fCategoryId = categoryForType(cats, fType, 0);
	}

	// Switching type swaps an incompatible category for the first one of the
	// new type, so the form never holds (or submits) a mismatched category.
	function setType(type: 'income' | 'expense') {
		fType = type;
		fCategoryId = categoryForType(cats, type, fCategoryId);
	}

	function startEdit(txn: Transaction) {
		editId = txn.id;
		editOriginal = { categoryId: txn.category_id, type: txn.type };
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
		const categoryError = formCategoryError(cats, fType, fCategoryId, editId !== null ? editOriginal : null);
		if (categoryError) {
			error = categoryError;
			return;
		}
		submitting = true;
		const isoDate = toIsoOffset(`${fDate}T${fTime}`);
		const data = {
			account_id: fAccountId,
			category_id: fCategoryId,
			type: fType,
			amount: fAmount,
			currency: fCurrency,
			description: fDescription || undefined,
			note: fNote || undefined,
			date: isoDate
		};
		try {
			localStorage.setItem('lastUsedAccountId', fAccountId.toString());
			if (editId) {
				await transactions.update(editId, data);
			} else {
				const isFuture = new Date(isoDate) > new Date();
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
						next_occurrence: isoDate,
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
		confirmIsBatch = false;
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
	const allLoadedSelected = $derived(txns.length > 0 && txns.every(t => selected.has(t.id)));

	// A manual change of the selection also abandons "select all matching".
	function setSelection(next: Map<number, Transaction>) {
		selected = next;
		selectAllToken++;
		selectAllState = 'idle';
	}

	function toggleSelect(txn: Transaction) {
		if (listStale) return;
		const next = new Map(selected);
		if (next.has(txn.id)) next.delete(txn.id);
		else next.set(txn.id, txn);
		setSelection(next);
	}

	function toggleSelectAll() {
		if (listStale) return;
		setSelection(allLoadedSelected ? new Map() : new Map(txns.map(t => [t.id, t])));
	}

	async function selectAllMatching() {
		if (listStale) return;
		const token = ++selectAllToken;
		const seq = loadSeq;
		const params = listParams();
		const isStale = () => token !== selectAllToken || seq !== loadSeq;
		selectAllState = 'loading';
		error = '';
		try {
			const result = await fetchAllMatching(
				(offset, limit) => transactions.list({ ...params, limit: limit.toString(), offset: offset.toString() }),
				{ pageSize: SELECT_ALL_PAGE_SIZE, cap: BULK_CAP, isStale }
			);
			if (result.kind === 'stale' || isStale()) return;
			if (result.kind === 'tooMany') {
				selectAllState = 'tooMany';
				return;
			}
			selected = new Map(result.rows.map(t => [t.id, t]));
			selectAllState = 'all';
		} catch (e: any) {
			if (isStale()) return;
			selectAllState = 'idle';
			error = e.message;
		}
	}

	function batchDelete() {
		if (listStale || selected.size === 0) return;
		// Delete exactly what the confirmation names. Clearing the selection
		// closes the confirmation, unless this request is already running.
		const ids = [...selected.keys()];
		confirmIsBatch = true;
		confirmMessage = `Delete ${ids.length} transaction(s)?`;
		confirmAction = async () => {
			batchDeleting = true;
			try {
				await batch.deleteTransactions(ids);
				await load();
			} finally {
				batchDeleting = false;
			}
		};
		confirmOpen = true;
	}

	async function openBulkEdit() {
		if (listStale || bulkApplying || bulkPreparing || selected.size === 0) return;
		const snapshot = selected;
		// Schedules are loaded once per owner; a link to one created since (or
		// a failed fetch) would make the schedule count come up short.
		if (missingScheduleIds(snapshot.values(), scheduleById).length > 0) {
			bulkPreparing = true;
			try {
				await loadSchedules(true);
			} finally {
				bulkPreparing = false;
			}
			// The selection changed or a reload started meanwhile.
			if (snapshot !== selected || listStale || bulkApplying) return;
		}
		bulkRows = [...snapshot.values()];
		bulkSession++;
		bulkOpen = true;
	}

	async function applyBulkEdit(input: BatchUpdateInput) {
		const session = bulkSession;
		bulkApplying = true;
		let result: BatchUpdateResult;
		try {
			result = await batch.updateTransactions(input);
		} catch (e: any) {
			// The dialog shows the error inline only while the session that sent
			// it is still on screen; otherwise (closed by a reload, or another
			// session) make sure the failure is still seen.
			if (session !== bulkSession || !bulkOpen) addToast(e?.message || 'Could not update the entries.', 'error', 6000);
			throw e;
		} finally {
			bulkApplying = false;
		}
		addToast(bulkResultMessage(result));
		if (result.schedules_updated > 0) void loadSchedules();
		await load();
	}

	// CSV export/import
	async function exportCsv() {
		error = '';
		try {
			await csv.exportTransactions(listParams());
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

	{#if error}<p class="error-msg" role="alert">{error}</p>{/if}
	{#if loadError}
		<p class="error-msg" role="alert">{loadError}</p>
		<button class="btn-ghost" onclick={() => void loadAll()}>Retry</button>
	{/if}

	{#if showForm}
		<div class="card" style="margin-bottom: 1.5rem">
			<h2>{editId ? 'Edit' : 'New'} Transaction</h2>
			<form onsubmit={submit}>
				<div class="form-row">
					<div class="form-group">
						<label for="type">Type</label>
						<select id="type" value={fType} onchange={(e) => setType(e.currentTarget.value as 'income' | 'expense')}>
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
				<label for="fq">Search</label>
				<input
					id="fq"
					type="search"
					bind:value={filterQ}
					oninput={onSearchInput}
					placeholder="Search descriptions..."
					maxlength="200"
					autocomplete="off"
				/>
				<label class="checkbox-label filter-option">
					<input type="checkbox" bind:checked={exactMatch} onchange={onExactMatchChange} />
					Exact match
				</label>
			</div>
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
			{#if hasAnyShared}
				<div class="form-group added-by-filter">
					<label class="checkbox-label">
						<input type="checkbox" bind:checked={addedByMe} onchange={() => load()} />
						Added by me
					</label>
				</div>
			{/if}
		</div>
	</div>

	{#if selected.size > 0}
		<div class="batch-bar card">
			<span>{selected.size} selected</span>
			<button class="btn-primary" onclick={openBulkEdit} disabled={listStale || bulkApplying || bulkPreparing}>Edit…</button>
			<button class="btn-danger" onclick={batchDelete} disabled={listStale}>Delete Selected</button>
			<button class="btn-ghost" onclick={() => setSelection(new Map())}>Clear</button>
		</div>
	{/if}

	{#if selectAllState === 'all'}
		<div class="select-all-banner" role="status">
			<span>All {selected.size} matching entries selected</span>
			<span aria-hidden="true">·</span>
			<button type="button" class="link-btn" onclick={() => setSelection(new Map())}>Clear</button>
		</div>
	{:else if selectAllState === 'tooMany'}
		<div class="select-all-banner" role="status">
			<span>More than {BULK_CAP} entries match. Narrow the search to select them all.</span>
		</div>
	{:else if selectAllState === 'loading' || (allLoadedSelected && hasMore && selected.size === txns.length)}
		<div class="select-all-banner" role="status">
			<span>All {txns.length} loaded entries are selected.</span>
			<button type="button" class="link-btn" onclick={selectAllMatching} disabled={selectAllState === 'loading' || listStale}>
				{selectAllState === 'loading' ? 'Selecting all matching entries…' : 'Select all matching entries'}
			</button>
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
		<div class="card table-wrap" class:stale={listStale} aria-busy={listStale}>
			<table>
				<thead>
					<tr>
						<th class="check-col">
							<input type="checkbox" aria-label="Select all loaded entries" checked={allLoadedSelected} disabled={listStale} onchange={toggleSelectAll} />
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
								<input type="checkbox" aria-label={`Select ${txn.description || 'entry'} ${txn.date.slice(0, 10)}`} checked={selected.has(txn.id)} disabled={listStale} onchange={() => toggleSelect(txn)} />
							</td>
							<td>{txn.date.slice(0, 10)}</td>
							<td class="time-cell">{extractTime(txn.date) || '—'}</td>
							<td><span class="badge {txn.type === 'income' ? 'badge-income' : 'badge-expense'}">{txn.type}</span></td>
							<td>{catName(txn.category_id)}</td>
							<td>{acctName(txn.account_id)}</td>
							<td><TransactionName {txn} schedules={scheduleById} /></td>
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
				<button class="btn-ghost" onclick={loadMore} disabled={loadingMore || listStale}>
					{loadingMore ? 'Loading...' : 'Load More'}
				</button>
			</div>
		{/if}
	{/if}
</div>

<ConfirmDialog bind:open={confirmOpen} message={confirmMessage} onconfirm={confirmAction} />
<BulkEditDialog bind:open={bulkOpen} rows={bulkRows} cats={cats} schedules={scheduleById}
	schedulesIncomplete={bulkSchedulesMissing} onapply={applyBulkEdit} />

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
	.table-wrap.stale {
		opacity: 0.55;
		transition: opacity 0.15s;
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
	.filter-option {
		margin-top: 0.4rem;
		font-size: 0.8rem;
		color: var(--text-muted);
	}
	.added-by-filter {
		flex: 0 0 auto;
		display: flex;
		align-items: flex-end;
		padding-bottom: 0.55rem;
	}
	.select-all-banner {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		justify-content: center;
		gap: 0.5rem;
		margin-bottom: 1rem;
		padding: 0.6rem 1rem;
		border: 1px solid var(--border);
		border-radius: var(--radius);
		background: var(--bg-hover);
		font-size: 0.875rem;
	}
	.link-btn {
		padding: 0;
		background: none;
		color: var(--primary);
		font-weight: 500;
	}
	.link-btn:hover:not(:disabled) {
		text-decoration: underline;
	}
	.link-btn:disabled {
		color: var(--text-muted);
		cursor: default;
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
