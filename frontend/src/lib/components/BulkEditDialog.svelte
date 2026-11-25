<script lang="ts">
	import { untrack } from 'svelte';
	import type { BatchUpdateInput, Category, ScheduledTransaction, Transaction } from '$lib/api/client';
	import CategoryPicker from './CategoryPicker.svelte';
	import {
		SCHEDULES_UNAVAILABLE_REASON,
		buildBulkPayload,
		categoryDisabledReason,
		categoryOptions,
		initialCategoryId,
		nameError,
		scheduleConflict,
		summarizeSelection
	} from '$lib/bulkEdit';

	let {
		open = $bindable(false), rows, cats, schedules, schedulesIncomplete = false, onapply
	}: {
		open: boolean;
		// The selected entries, snapshotted when the dialog opens.
		rows: Transaction[];
		cats: Category[];
		// Loaded with include_deleted=1; only live schedules are offered.
		schedules: ReadonlyMap<number, ScheduledTransaction>;
		// Some linked schedules are missing from `schedules`, so the count would
		// be short: the schedule option is then unavailable.
		schedulesIncomplete?: boolean;
		// Sends the request; throw to keep the dialog open with the error shown.
		onapply: (input: BatchUpdateInput) => void | Promise<void>;
	} = $props();

	let dialog: HTMLDialogElement;
	let firstField: HTMLInputElement;
	let busy = $state(false);
	let error = $state('');
	let changeName = $state(false);
	let name = $state('');
	let changeCategory = $state(false);
	let categoryId = $state(0);
	let updateSchedules = $state(false);
	const ids = $props.id();
	// Bumped on every open and close. A request belongs to the session that
	// sent it: once that session is over, its outcome (busy, error, closing)
	// must never reach a later one.
	let session = 0;

	const summary = $derived(summarizeSelection(rows, schedules));
	const options = $derived(categoryOptions(cats, summary.owner, summary.type));
	const categoryReason = $derived(categoryDisabledReason(summary, cats));
	const scheduleCount = $derived(summary.scheduleIds.length);
	const nameProblem = $derived(changeName ? nameError(name) : null);
	const chosenCategory = $derived(
		changeCategory && !categoryReason ? options.find(c => c.id === categoryId) : undefined
	);
	// Never send the schedule flag while the count can't be trusted.
	const schedulesOn = $derived(updateSchedules && !schedulesIncomplete);
	const conflict = $derived(schedulesOn && scheduleCount > 0 ? scheduleConflict(summary, chosenCategory) : null);
	const payload = $derived(
		buildBulkPayload(
			rows.map(r => r.id),
			{ changeName, name, changeCategory, categoryId, updateSchedules: schedulesOn },
			summary,
			cats
		)
	);
	const entries = $derived(summary.count === 1 ? '1 entry' : `${summary.count} entries`);
	const schedulesLabel = $derived(
		scheduleCount === 1 ? '1 linked recurring schedule' : `${scheduleCount} linked recurring schedules`
	);

	// Every opening starts from "change nothing".
	function reset() {
		busy = false;
		error = '';
		changeName = false;
		name = summary.commonDescription ?? '';
		changeCategory = false;
		categoryId = initialCategoryId(summary, cats);
		updateSchedules = false;
	}

	$effect(() => {
		session++;
		if (!open) { dialog?.close(); return; }
		const opener = document.activeElement as HTMLElement | null;
		untrack(reset);
		dialog.showModal();
		firstField.focus();
		return () => { opener?.focus(); };
	});

	async function apply(event?: Event) {
		event?.preventDefault();
		const input = payload;
		if (busy || !input) return;
		const mine = session;
		busy = true;
		error = '';
		try {
			await onapply(input);
			if (mine === session) open = false;
		} catch (e) {
			if (mine === session) error = e instanceof Error ? e.message : 'Could not update the entries. Please try again.';
		} finally {
			if (mine === session) busy = false;
		}
	}

	function handleBackdropClick(event: MouseEvent) {
		if (busy || event.target !== dialog) return;
		const bounds = dialog.getBoundingClientRect();
		if (event.clientX < bounds.left || event.clientX > bounds.right ||
			event.clientY < bounds.top || event.clientY > bounds.bottom) open = false;
	}
</script>

<dialog bind:this={dialog} aria-labelledby={`${ids}-title`}
	onclick={handleBackdropClick}
	oncancel={(event) => { event.preventDefault(); if (!busy) open = false; }}
	onclose={() => { open = false; }}>
	<h3 id={`${ids}-title`}>Edit {entries}</h3>
	<form onsubmit={apply}>
		<div class="field">
			<label class="checkbox-label">
				<input bind:this={firstField} type="checkbox" bind:checked={changeName} disabled={busy} />
				Change name
			</label>
			{#if changeName}
				<input type="text" aria-label="New name" bind:value={name} disabled={busy}
					maxlength="500" autocomplete="off"
					aria-invalid={nameProblem ? 'true' : undefined}
					aria-describedby={nameProblem ? `${ids}-name-problem` : undefined} />
				{#if nameProblem}<p id={`${ids}-name-problem`} class="hint problem">{nameProblem}</p>{/if}
			{/if}
		</div>

		<div class="field">
			<label class="checkbox-label">
				<input type="checkbox" bind:checked={changeCategory} disabled={busy || !!categoryReason} />
				Change category
			</label>
			{#if categoryReason}
				<p class="hint">{categoryReason}</p>
			{:else if changeCategory}
				<div class="picker">
					<label for={`${ids}-category`} class="visually-hidden">Category to apply</label>
					<CategoryPicker
						id={`${ids}-category`}
						cats={options}
						type={summary.type ?? 'expense'}
						value={categoryId}
						onchange={(id) => categoryId = id}
						allowCreate={false}
						disabled={busy}
					/>
				</div>
			{/if}
		</div>

		{#if schedulesIncomplete}
			<div class="field">
				<label class="checkbox-label">
					<input type="checkbox" checked={false} disabled />
					Also update linked recurring schedules
				</label>
				<p class="hint">{SCHEDULES_UNAVAILABLE_REASON}</p>
			</div>
		{:else if scheduleCount > 0}
			<div class="field">
				<label class="checkbox-label">
					<input type="checkbox" bind:checked={updateSchedules} disabled={busy} />
					Also update {schedulesLabel}
				</label>
				<p class="hint">Future entries get the new name or category. Amounts, dates and repetition stay as they are.</p>
				{#if conflict}<p class="hint problem">{conflict}</p>{/if}
			</div>
		{/if}

		{#if error}<p class="error-msg" role="alert">{error}</p>{/if}
		<div class="actions">
			<button type="button" class="btn-ghost" disabled={busy} onclick={() => open = false}>Cancel</button>
			<button type="submit" class="btn-primary" disabled={busy || !payload}>
				{busy ? 'Working…' : `Apply to ${entries}`}
			</button>
		</div>
	</form>
</dialog>

<style>
	dialog {
		color: var(--text);
		background: var(--bg-card);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		padding: 1.5rem;
		width: min(480px, calc(100vw - 2rem));
		max-height: calc(100vh - 2rem);
		box-sizing: border-box;
		overflow: auto;
		box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3);
	}
	dialog::backdrop { background: rgba(0, 0, 0, 0.5); }
	h3 { margin: 0 0 1rem; font-size: 1.1rem; }
	.field { margin-bottom: 1rem; }
	.field input[type="text"], .picker { width: 100%; margin-top: 0.5rem; }
	.hint { margin: 0.35rem 0 0; color: var(--text-muted); font-size: 0.8rem; line-height: 1.4; }
	.hint.problem { color: var(--danger); }
	.visually-hidden {
		position: absolute; width: 1px; height: 1px; margin: -1px; padding: 0;
		overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0;
	}
	.actions { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 0.5rem; margin-top: 1.25rem; }
</style>
