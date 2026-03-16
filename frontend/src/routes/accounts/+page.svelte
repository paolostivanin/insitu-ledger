<script lang="ts">
	import { onMount } from 'svelte';
	import { accounts, type Account } from '$lib/api/client';

	let accts = $state<Account[]>([]);
	let loading = $state(true);
	let showForm = $state(false);
	let editId = $state<number | null>(null);
	let error = $state('');

	let fName = $state('');
	let fCurrency = $state('EUR');
	let fBalance = $state(0);

	onMount(load);

	async function load() {
		loading = true;
		accts = await accounts.list();
		loading = false;
	}

	function resetForm() {
		editId = null;
		fName = '';
		fCurrency = 'EUR';
		fBalance = 0;
	}

	function startEdit(a: Account) {
		editId = a.id;
		fName = a.name;
		fCurrency = a.currency;
		fBalance = a.balance;
		showForm = true;
	}

	async function submit(e: Event) {
		e.preventDefault();
		error = '';
		try {
			if (editId) {
				await accounts.update(editId, { name: fName, currency: fCurrency, balance: fBalance });
			} else {
				await accounts.create({ name: fName, currency: fCurrency, balance: fBalance });
			}
			showForm = false;
			resetForm();
			await load();
		} catch (e: any) {
			error = e.message;
		}
	}

	async function remove(id: number) {
		if (!confirm('Delete this account?')) return;
		await accounts.delete(id);
		await load();
	}

	function fmt(n: number): string {
		return n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
	}

	function totalBalance(): number {
		return accts.reduce((sum, a) => sum + a.balance, 0);
	}
</script>

<div class="page">
	<div class="page-header">
		<h1>Accounts</h1>
		<button class="btn-primary" onclick={() => { resetForm(); showForm = !showForm; }}>
			{showForm ? 'Cancel' : '+ New Account'}
		</button>
	</div>

	{#if error}
		<p class="error-msg">{error}</p>
	{/if}

	{#if showForm}
		<div class="card" style="margin-bottom: 1.5rem">
			<h2>{editId ? 'Edit' : 'New'} Account</h2>
			<form onsubmit={submit}>
				<div class="form-row">
					<div class="form-group">
						<label for="name">Name</label>
						<input id="name" type="text" bind:value={fName} required />
					</div>
					<div class="form-group">
						<label for="currency">Currency</label>
						<input id="currency" type="text" bind:value={fCurrency} required />
					</div>
					<div class="form-group">
						<label for="balance">Initial Balance</label>
						<input id="balance" type="number" step="0.01" bind:value={fBalance} />
					</div>
				</div>
				<button class="btn-primary" type="submit">{editId ? 'Update' : 'Create'}</button>
			</form>
		</div>
	{/if}

	{#if loading}
		<p>Loading...</p>
	{:else if accts.length === 0}
		<p class="empty-state">No accounts yet. Create one to start tracking.</p>
	{:else}
		<div class="acct-grid">
			{#each accts as acct}
				<div class="card acct-card">
					<div class="acct-name">{acct.name}</div>
					<div class="acct-balance">{fmt(acct.balance)} <span class="acct-currency">{acct.currency}</span></div>
					<div class="actions" style="margin-top: 0.75rem">
						<button class="btn-ghost" onclick={() => startEdit(acct)}>Edit</button>
						<button class="btn-danger" onclick={() => remove(acct.id)}>Delete</button>
					</div>
				</div>
			{/each}
		</div>
		<div class="total-bar card" style="margin-top: 1rem">
			<span>Total Balance</span>
			<span class="total-amount">{fmt(totalBalance())}</span>
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
	.acct-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
		gap: 1rem;
	}
	.acct-name {
		font-weight: 600;
		font-size: 1.1rem;
	}
	.acct-balance {
		font-size: 1.5rem;
		font-weight: 700;
		margin-top: 0.5rem;
	}
	.acct-currency {
		font-size: 0.85rem;
		color: var(--text-muted);
		font-weight: 400;
	}
	.total-bar {
		display: flex;
		justify-content: space-between;
		align-items: center;
		font-weight: 600;
	}
	.total-amount {
		font-size: 1.25rem;
	}
</style>
