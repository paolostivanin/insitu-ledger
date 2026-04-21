<script lang="ts">
	import type { Account } from '$lib/api/client';
	import { currentAccountId, setAccountFilter } from '$lib/stores/accountFilter';

	let { accounts }: { accounts: Account[] } = $props();

	function onChange(e: Event) {
		const v = (e.target as HTMLSelectElement).value;
		setAccountFilter(v === '' ? null : parseInt(v, 10));
	}
</script>

{#if accounts.length > 0}
	<select class="account-filter" value={$currentAccountId?.toString() ?? ''} onchange={onChange}>
		<option value="">All accounts</option>
		{#each accounts as a (a.id)}
			<option value={a.id.toString()}>{a.name}</option>
		{/each}
	</select>
{/if}

<style>
	.account-filter {
		background: var(--bg-hover);
		color: var(--text);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		padding: 0.3rem 0.6rem;
		font-size: 0.85rem;
		cursor: pointer;
	}
</style>
