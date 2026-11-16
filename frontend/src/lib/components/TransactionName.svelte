<script lang="ts">
	import type { ScheduledTransaction, Transaction } from '$lib/api/client';
	import { seriesBadge } from '$lib/series';

	// schedules is keyed by id; until it loads (or if the source schedule is
	// gone) the badge reads 'Recurring' instead of the frequency.
	let { txn, schedules }: {
		txn: Transaction;
		schedules: ReadonlyMap<number, ScheduledTransaction>;
	} = $props();

	const badge = $derived(
		txn.scheduled_id != null ? seriesBadge(txn, schedules.get(txn.scheduled_id)) : null
	);
</script>

{txn.description || '—'}{#if badge}
	<span class="series-badge" role="img" aria-label={badge.spoken} title={badge.spoken}>
		{#if badge.oneTime}
			<svg width="1em" height="1em" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
				<circle cx="12" cy="12" r="10"></circle>
				<polyline points="12 6 12 12 16 14"></polyline>
			</svg>
		{:else}
			<svg width="1em" height="1em" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
				<polyline points="17 1 21 5 17 9"></polyline>
				<path d="M3 11V9a4 4 0 0 1 4-4h14"></path>
				<polyline points="7 23 3 19 7 15"></polyline>
				<path d="M21 13v2a4 4 0 0 1-4 4H3"></path>
			</svg>
		{/if}
		<span aria-hidden="true">{badge.text}</span>
	</span>
{/if}

<style>
	.series-badge {
		display: flex;
		width: fit-content;
		align-items: center;
		gap: 0.3em;
		margin-top: 0.25rem;
		padding: 0.05rem 0.5rem;
		border-radius: var(--radius);
		background: color-mix(in srgb, var(--primary) 14%, transparent);
		color: var(--text);
		font-size: 0.78rem;
		line-height: 1.5;
		white-space: nowrap;
	}
	.series-badge svg {
		color: var(--primary);
		flex: none;
	}
</style>
