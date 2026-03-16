<script lang="ts">
	import { onMount } from 'svelte';
	import { scheduled, categories, accounts, type ScheduledTransaction, type Category, type Account } from '$lib/api/client';
	import CategoryPicker from '$lib/components/CategoryPicker.svelte';

	let items = $state<ScheduledTransaction[]>([]);
	let cats = $state<Category[]>([]);
	let accts = $state<Account[]>([]);
	let loading = $state(true);
	let showForm = $state(false);
	let editId = $state<number | null>(null);
	let error = $state('');

	let fType = $state<'income' | 'expense'>('expense');
	let fAccountId = $state(0);
	let fCategoryId = $state(0);
	let fAmount = $state(0);
	let fCurrency = $state('EUR');
	let fDescription = $state('');
	let fFrequency = $state('monthly');
	let fNextOccurrence = $state(new Date().toISOString().slice(0, 10));

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

	onMount(load);

	async function load() {
		loading = true;
		const [s, c, a] = await Promise.all([scheduled.list(), categories.list(), accounts.list()]);
		items = s;
		cats = c;
		accts = a;
		if (accts.length && !fAccountId) fAccountId = accts[0].id;
		if (cats.length && !fCategoryId) fCategoryId = cats[0].id;
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

	function rruleLabel(rrule: string): string {
		return rruleLabels[rrule] || rrule;
	}

	function resetForm() {
		editId = null;
		fType = 'expense';
		fAmount = 0;
		fCurrency = 'EUR';
		fDescription = '';
		fFrequency = 'monthly';
		fNextOccurrence = new Date().toISOString().slice(0, 10);
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
		fNextOccurrence = item.next_occurrence;
		// reverse lookup frequency
		fFrequency = Object.entries(frequencyMap).find(([, v]) => v === item.rrule)?.[0] || 'monthly';
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
			rrule: frequencyMap[fFrequency],
			next_occurrence: fNextOccurrence
		};
		try {
			if (editId) {
				await scheduled.update(editId, data);
			} else {
				await scheduled.create(data);
			}
			showForm = false;
			resetForm();
			await load();
		} catch (e: any) {
			error = e.message;
		}
	}

	async function remove(id: number) {
		if (!confirm('Delete this scheduled transaction?')) return;
		await scheduled.delete(id);
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
						<label for="next">Next Occurrence</label>
						<input id="next" type="date" bind:value={fNextOccurrence} required />
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
						<th>Next</th>
						<th>Type</th>
						<th>Category</th>
						<th>Account</th>
						<th>Description</th>
						<th>Amount</th>
						<th>Status</th>
						<th></th>
					</tr>
				</thead>
				<tbody>
					{#each items as item}
						<tr>
							<td>{rruleLabel(item.rrule)}</td>
							<td>{item.next_occurrence}</td>
							<td><span class="badge {item.type === 'income' ? 'badge-income' : 'badge-expense'}">{item.type}</span></td>
							<td>{catName(item.category_id)}</td>
							<td>{acctName(item.account_id)}</td>
							<td>{item.description || '—'}</td>
							<td class={item.type === 'income' ? 'amount-income' : 'amount-expense'}>
								{item.type === 'income' ? '+' : '-'}{fmt(item.amount)} {item.currency}
							</td>
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

<style>
	.page-header {
		display: flex;
		justify-content: space-between;
		align-items: center;
		margin-bottom: 1rem;
	}
	h2 { font-size: 1rem; margin-bottom: 1rem; }
</style>
