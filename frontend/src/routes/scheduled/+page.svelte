<script lang="ts">
	import { onMount } from 'svelte';
	import { scheduled, categories, accounts, type ScheduledTransaction, type Category, type Account } from '$lib/api/client';
	import CategoryPicker from '$lib/components/CategoryPicker.svelte';
	import ConfirmDialog from '$lib/components/ConfirmDialog.svelte';
	import { currencySymbol } from '$lib/stores/auth';
	import { formatMoney } from '$lib/format';
	import { sharedOwnerUserId } from '$lib/stores/shared';

	let items = $state<ScheduledTransaction[]>([]);
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

	let fType = $state<'income' | 'expense'>('expense');
	let fAccountId = $state(0);
	let fCategoryId = $state(0);
	let fAmount = $state(0);
	let fCurrency = $state('EUR');
	let fDescription = $state('');
	let fNote = $state('');
	let fFrequency = $state('monthly');
	let fNextDate = $state(new Date().toISOString().slice(0, 10));
	let fNextTime = $state('09:00');
	let fEndMode = $state<'never' | 'count' | 'date'>('never');
	let fMaxOccurrences = $state<string>('');
	let fEndDate = $state('');
	let fActive = $state(true);

	const frequencyMap: Record<string, string> = {
		daily: 'FREQ=DAILY',
		weekly: 'FREQ=WEEKLY',
		biweekly: 'FREQ=WEEKLY;INTERVAL=2',
		monthly: 'FREQ=MONTHLY',
		quarterly: 'FREQ=MONTHLY;INTERVAL=3',
		yearly: 'FREQ=YEARLY'
	};

	const rruleLabels: Record<string, string> = {
		'FREQ=DAILY': 'Daily',
		'FREQ=WEEKLY': 'Weekly',
		'FREQ=WEEKLY;INTERVAL=2': 'Biweekly',
		'FREQ=MONTHLY': 'Monthly',
		'FREQ=MONTHLY;INTERVAL=3': 'Quarterly',
		'FREQ=YEARLY': 'Yearly'
	};

	let mounted = false;
	let prevOwnerId: string | null = null;

	$effect(() => {
		const oid = $sharedOwnerUserId;
		if (mounted && oid !== prevOwnerId) {
			prevOwnerId = oid;
			void load();
		}
	});

	onMount(async () => {
		prevOwnerId = $sharedOwnerUserId;
		mounted = true;
		await load();
	});

	async function load() {
		loading = true;
		const oid = $sharedOwnerUserId || undefined;
		const [s, c, a] = await Promise.all([
			scheduled.list({ owner_id: oid }),
			categories.list(oid),
			accounts.list(oid)
		]);
		items = s;
		cats = c;
		accts = a;
		if (accts.length && !fAccountId) fAccountId = accts[0].id;
		if (cats.length && !fCategoryId) fCategoryId = cats[0].id;
		loading = false;
	}

	function isSharedAcct(accountId: number): boolean {
		return !!accts.find(a => a.id === accountId)?.is_shared;
	}

	const hasAnyShared = $derived(accts.some(a => a.is_shared));

	function catName(id: number): string {
		return cats.find(c => c.id === id)?.name || '—';
	}

	function acctName(id: number): string {
		return accts.find(a => a.id === id)?.name || '—';
	}

	function fmt(n: number): string {
		return formatMoney(n, $currencySymbol);
	}

	function rruleLabel(rrule: string): string {
		return rruleLabels[rrule] || rrule;
	}

	function extractTime(dt: string): string {
		if (!dt.includes('T')) return '';
		return dt.split('T')[1].slice(0, 5);
	}

	// Strip optional ;UNTIL=... from an rrule and return the base + the YYYY-MM-DD UNTIL date (if any).
	function splitRrule(rrule: string): { base: string; until: string } {
		const parts = rrule.split(';');
		const base = parts.filter(p => !p.startsWith('UNTIL=')).join(';');
		const untilRaw = parts.find(p => p.startsWith('UNTIL='))?.slice(6) || '';
		// Tolerate 20261231T235959Z, 20261231, 2026-12-31, 2026-12-31T...
		let until = '';
		if (untilRaw) {
			const core = untilRaw.split('T')[0];
			if (/^\d{8}$/.test(core)) {
				until = `${core.slice(0, 4)}-${core.slice(4, 6)}-${core.slice(6, 8)}`;
			} else {
				until = core;
			}
		}
		return { base, until };
	}

	function buildRrule(frequencyKey: string, endMode: string, endDate: string): string {
		const base = frequencyMap[frequencyKey];
		if (endMode === 'date' && endDate) {
			const compact = endDate.replace(/-/g, '');
			return `${base};UNTIL=${compact}T235959Z`;
		}
		return base;
	}

	function resetForm() {
		editId = null;
		fType = 'expense';
		fAmount = 0;
		fCurrency = 'EUR';
		fDescription = '';
		fNote = '';
		fFrequency = 'monthly';
		fNextDate = new Date().toISOString().slice(0, 10);
		fNextTime = '09:00';
		fEndMode = 'never';
		fMaxOccurrences = '';
		fEndDate = '';
		fActive = true;
		if (accts.length) fAccountId = accts[0].id;
		if (cats.length) fCategoryId = cats[0].id;
	}

	function startEdit(item: ScheduledTransaction) {
		editId = item.id;
		fType = item.type;
		fAccountId = item.account_id;
		fCategoryId = item.category_id;
		fAmount = item.amount;
		fCurrency = item.currency;
		fDescription = item.description || '';
		fNote = item.note || '';
		// Split next_occurrence into date and time parts
		if (item.next_occurrence.includes('T')) {
			const [d, t] = item.next_occurrence.split('T');
			fNextDate = d;
			fNextTime = t;
		} else {
			fNextDate = item.next_occurrence;
			fNextTime = '09:00';
		}
		// Strip ;UNTIL=... before reverse-looking up the frequency key.
		const { base, until } = splitRrule(item.rrule);
		fFrequency = Object.entries(frequencyMap).find(([, v]) => v === base)?.[0] || 'monthly';
		if (until) {
			fEndMode = 'date';
			fEndDate = until;
			fMaxOccurrences = '';
		} else if (item.max_occurrences != null) {
			fEndMode = 'count';
			fMaxOccurrences = String(item.max_occurrences);
			fEndDate = '';
		} else {
			fEndMode = 'never';
			fMaxOccurrences = '';
			fEndDate = '';
		}
		fActive = item.active;
		showForm = true;
	}

	async function submit(e: Event) {
		e.preventDefault();
		error = '';
		if (fEndMode === 'date' && !fEndDate) {
			error = 'Pick an end date';
			return;
		}
		submitting = true;
		const maxOcc = fEndMode === 'count' && fMaxOccurrences ? parseInt(fMaxOccurrences, 10) : null;
		const data: any = {
			account_id: fAccountId,
			category_id: fCategoryId,
			type: fType,
			amount: fAmount,
			currency: fCurrency,
			description: fDescription || undefined,
			note: fNote || undefined,
			rrule: buildRrule(fFrequency, fEndMode, fEndDate),
			next_occurrence: `${fNextDate}T${fNextTime}`,
			max_occurrences: maxOcc && maxOcc > 0 ? maxOcc : null
		};
		try {
			if (editId) {
				// Only send `active` on update so create-flow keeps server default.
				await scheduled.update(editId, { ...data, active: fActive });
			} else {
				await scheduled.create(data);
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
		confirmMessage = 'Delete this scheduled transaction?';
		confirmAction = async () => {
			await scheduled.delete(id);
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
</script>

<div class="page">
	<div class="page-header">
		<h1>Scheduled Transactions</h1>
		<button class="btn-primary" onclick={() => { resetForm(); showForm = !showForm; }}>
			{showForm ? 'Cancel' : '+ New Scheduled'}
		</button>
	</div>

	{#if error}
		<p class="error-msg">{error}</p>
	{/if}

	{#if showForm}
		<div class="card" style="margin-bottom: 1.5rem">
			<h2>{editId ? 'Edit' : 'New'} Scheduled Transaction</h2>
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
						<label for="freq">Frequency</label>
						<select id="freq" bind:value={fFrequency}>
							<option value="daily">Daily</option>
							<option value="weekly">Weekly</option>
							<option value="biweekly">Biweekly</option>
							<option value="monthly">Monthly</option>
							<option value="quarterly">Quarterly</option>
							<option value="yearly">Yearly</option>
						</select>
					</div>
				</div>
				<div class="form-row">
					<div class="form-group">
						<label for="account">Account</label>
						<select id="account" bind:value={fAccountId}>
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
						<label for="next-date">Next Date</label>
						<input id="next-date" type="date" bind:value={fNextDate} required />
					</div>
				</div>
				<div class="form-row">
					<div class="form-group">
						<label for="next-time">Time</label>
						<input id="next-time" type="time" bind:value={fNextTime} required />
					</div>
					<div class="form-group">
						<label for="ends">Ends</label>
						<select id="ends" bind:value={fEndMode}>
							<option value="never">Never</option>
							<option value="count">After N occurrences</option>
							<option value="date">On date</option>
						</select>
					</div>
					{#if fEndMode === 'count'}
						<div class="form-group">
							<label for="max-occ">Stop after N</label>
							<input id="max-occ" type="number" min="1" bind:value={fMaxOccurrences} placeholder="e.g. 12" />
						</div>
					{:else if fEndMode === 'date'}
						<div class="form-group">
							<label for="end-date">End date</label>
							<input id="end-date" type="date" bind:value={fEndDate} required />
						</div>
					{/if}
				</div>
				{#if editId}
					<div class="form-group">
						<label class="inline-toggle">
							<input type="checkbox" bind:checked={fActive} />
							<span>Active</span>
							<span class="hint">{fActive ? 'Schedule is running' : 'Paused — no new transactions will be created'}</span>
						</label>
					</div>
				{/if}
				<div class="form-group">
					<label for="desc">Name</label>
					<input id="desc" type="text" bind:value={fDescription} placeholder="Optional" maxlength="500" />
				</div>
				<div class="form-group">
					<label for="note">Note</label>
					<textarea id="note" bind:value={fNote} maxlength="2000" rows="3" placeholder="Optional longer memo"></textarea>
				</div>
				<button class="btn-primary" type="submit" disabled={submitting}>{submitting ? 'Saving...' : editId ? 'Update' : 'Create'}</button>
			</form>
		</div>
	{/if}

	{#if loading}
		<p>Loading...</p>
	{:else if items.length === 0}
		<p class="empty-state">No scheduled transactions yet.</p>
	{:else}
		<div class="card table-wrap">
			<table>
				<thead>
					<tr>
						<th>Frequency</th>
						<th>Next Date</th>
						<th>Time</th>
						<th>Type</th>
						<th>Category</th>
						<th>Account</th>
						<th>Name</th>
						{#if hasAnyShared}<th>Added by</th>{/if}
						<th>Amount</th>
						<th>Progress</th>
						<th>Status</th>
						<th></th>
					</tr>
				</thead>
				<tbody>
					{#each items as item}
						<tr>
							<td>{rruleLabel(item.rrule)}</td>
							<td>{item.next_occurrence.includes('T') ? item.next_occurrence.split('T')[0] : item.next_occurrence}</td>
							<td>{extractTime(item.next_occurrence) || '—'}</td>
							<td><span class="badge {item.type === 'income' ? 'badge-income' : 'badge-expense'}">{item.type}</span></td>
							<td>{catName(item.category_id)}</td>
							<td>{acctName(item.account_id)}</td>
							<td>{item.description || '—'}</td>
							{#if hasAnyShared}
								<td class="added-by">{isSharedAcct(item.account_id) ? (item.created_by_name || '—') : ''}</td>
							{/if}
							<td class={item.type === 'income' ? 'amount-income' : 'amount-expense'}>
								{item.type === 'income' ? '+' : '-'}{fmt(item.amount)} {item.currency}
							</td>
							<td>{item.max_occurrences != null ? `${item.occurrence_count}/${item.max_occurrences}` : '∞'}</td>
							<td>{item.active ? 'Active' : 'Paused'}</td>
							<td class="actions">
								<button class="btn-ghost" onclick={() => startEdit(item)}>Edit</button>
								<button class="btn-danger" onclick={() => remove(item.id)}>Del</button>
							</td>
						</tr>
					{/each}
				</tbody>
			</table>
		</div>
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
	h2 { font-size: 1rem; margin-bottom: 1rem; }
	.added-by {
		color: var(--text-muted);
		font-size: 0.85rem;
	}
	.inline-toggle {
		display: inline-flex;
		align-items: center;
		gap: 0.5rem;
		cursor: pointer;
	}
	.inline-toggle .hint {
		color: var(--text-muted);
		font-size: 0.85rem;
	}
</style>
