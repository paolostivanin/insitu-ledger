<script lang="ts">
	import type { ScheduledTransaction, Transaction } from '$lib/api/client';
	import { seriesLabel } from '$lib/series';

	// schedules is keyed by id; until it loads (or if the source schedule is
	// gone) the label reads 'Recurring' instead of the frequency.
	let { txn, schedules }: {
		txn: Transaction;
		schedules: ReadonlyMap<number, ScheduledTransaction>;
	} = $props();

	const label = $derived(
		txn.scheduled_id != null ? seriesLabel(txn, schedules.get(txn.scheduled_id)) : null
	);
</script>

{txn.description || '—'}{#if label}<svg class="recurring-glyph" role="img" aria-label="Recurring" width="1em" height="1em" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
		<title>Recurring</title>
		<polyline points="17 1 21 5 17 9"></polyline>
		<path d="M3 11V9a4 4 0 0 1 4-4h14"></path>
		<polyline points="7 23 3 19 7 15"></polyline>
		<path d="M21 13v2a4 4 0 0 1-4 4H3"></path>
	</svg>
	<span class="series-label">{label}</span>
{/if}

<style>
	.recurring-glyph {
		margin-left: 0.35em;
		vertical-align: -0.125em;
		color: var(--text-muted);
	}
	.series-label {
		display: block;
		margin-top: 0.1rem;
		color: var(--text-muted);
		font-size: 0.8rem;
	}
</style>
