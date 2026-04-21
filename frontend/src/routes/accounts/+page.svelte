<script lang="ts">
	import { onMount } from 'svelte';
	import { accounts, type Account } from '$lib/api/client';
	import ConfirmDialog from '$lib/components/ConfirmDialog.svelte';
	import { currencySymbol } from '$lib/stores/auth';
	import { formatMoney } from '$lib/format';
	import { sharedOwnerUserId, accountPermission } from '$lib/stores/shared';

	let accts = $state<Account[]>([]);
	let loading = $state(true);
	let showForm = $state(false);
	let editId = $state<number | null>(null);
	let error = $state('');
	let submitting = $state(false);

	let fName = $state('');
	let fCurrency = $state('EUR');

	// Confirm dialog
	let confirmOpen = $state(false);
	let confirmMessage = $state('');
	let confirmAction = $state(() => {});

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

	const isOwn = $derived($sharedOwnerUserId === null);

	async function load() {
		loading = true;
		accts = await accounts.list($sharedOwnerUserId || undefined);
		loading = false;
	}

	function canMutate(a: Account): boolean {
		return isOwn || accountPermission(a.id) === 'write';
	}

	function resetForm() {
		editId = null;
		fName = '';
		fCurrency = 'EUR';
	}

	function startEdit(a: Account) {
		editId = a.id;
		fName = a.name;
		fCurrency = a.currency;
		showForm = true;
	}

	async function submit(e: Event) {
		e.preventDefault();
		error = '';
		submitting = true;
		const oid = $sharedOwnerUserId || undefined;
		try {
			if (editId) {
				await accounts.update(editId, { name: fName, currency: fCurrency }, oid);
			} else {
				await accounts.create({ name: fName, currency: fCurrency });
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
		confirmMessage = 'Delete this account?';
		confirmAction = async () => {
			await accounts.delete(id, $sharedOwnerUserId || undefined);
			await load();
		};
		confirmOpen = true;
	}

	function fmt(n: number): string {
		return formatMoney(n, $currencySymbol);
	}

	function totalBalance(): number {
		return accts.reduce((sum, a) => sum + a.balance, 0);
	}
</script>

<div class="page">
	<div class="page-header">
		<h1>Accounts</h1>
		{#if isOwn}
			<button class="btn-primary" onclick={() => { resetForm(); showForm = !showForm; }}>
				{showForm ? 'Cancel' : '+ New Account'}
			</button>
		{/if}
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
						<input id="name" type="text" bind:value={fName} required maxlength="100" />
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
				<button class="btn-primary" type="submit" disabled={submitting}>{submitting ? 'Saving...' : editId ? 'Update' : 'Create'}</button>
			</form>
		</div>
	{/if}

	{#if loading}
		<p>Loading...</p>
	{:else if accts.length === 0}
		<p class="empty-state">No accounts yet. Create one to start tracking.</p>
	{:else}
		<div class="acct-grid">
			{#each accts as acct (acct.id)}
				<div class="card acct-card">
					<div class="acct-name">{acct.name}</div>
					<div class="acct-balance">{fmt(acct.balance)} <span class="acct-currency">{acct.currency}</span></div>
					{#if canMutate(acct)}
						<div class="actions" style="margin-top: 0.75rem">
							<button class="btn-ghost" onclick={() => startEdit(acct)}>Edit</button>
							{#if isOwn}
								<button class="btn-danger" onclick={() => remove(acct.id)}>Delete</button>
							{/if}
						</div>
					{/if}
				</div>
			{/each}
		</div>
		<div class="total-bar card" style="margin-top: 1rem">
			<span>Total Balance</span>
			<span class="total-amount">{fmt(totalBalance())}</span>
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
