<script lang="ts">
	let {
		open = $bindable(false), title = 'Confirm', message = 'Are you sure?',
		confirmText = 'Delete', cancelText = 'Cancel', danger = true, onconfirm
	}: {
		open: boolean; title?: string; message?: string; confirmText?: string;
		cancelText?: string; danger?: boolean; onconfirm: () => void | Promise<void>;
	} = $props();

	let dialog: HTMLDialogElement;
	let cancelButton: HTMLButtonElement;
	let busy = $state(false);
	let error = $state('');
	const ids = $props.id();

	$effect(() => {
		if (!open) { dialog?.close(); return; }
		const opener = document.activeElement as HTMLElement | null;
		error = '';
		dialog.showModal();
		cancelButton.focus();
		return () => { opener?.focus(); };
	});

	async function handleConfirm() {
		if (busy) return;
		busy = true;
		error = '';
		try {
			await onconfirm();
			open = false;
		} catch (e) {
			error = e instanceof Error ? e.message : 'Could not complete this action. Please try again.';
		} finally { busy = false; }
	}

	function handleBackdropClick(event: MouseEvent) {
		if (busy || event.target !== dialog) return;
		const bounds = dialog.getBoundingClientRect();
		if (event.clientX < bounds.left || event.clientX > bounds.right ||
			event.clientY < bounds.top || event.clientY > bounds.bottom) open = false;
	}
</script>

<dialog bind:this={dialog} aria-labelledby={`${ids}-title`} aria-describedby={`${ids}-message`}
	onclick={handleBackdropClick}
	oncancel={(event) => { event.preventDefault(); if (!busy) open = false; }}
	onclose={() => { open = false; }}>
	<h3 id={`${ids}-title`}>{title}</h3>
	<p id={`${ids}-message`}>{message}</p>
	{#if error}<p class="error-msg" role="alert">{error}</p>{/if}
	<div class="actions">
		<button bind:this={cancelButton} type="button" class="btn-ghost" disabled={busy} onclick={() => open = false}>{cancelText}</button>
		<button type="button" class={danger ? 'btn-danger' : 'btn-primary'} disabled={busy} onclick={handleConfirm}>
			{busy ? 'Working…' : confirmText}
		</button>
	</div>
</dialog>

<style>
	dialog {
		color: var(--text);
		background: var(--bg-card);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		padding: 1.5rem;
		width: min(450px, calc(100vw - 2rem));
		max-height: calc(100vh - 2rem);
		box-sizing: border-box;
		overflow: auto;
		box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3);
	}
	dialog::backdrop { background: rgba(0, 0, 0, 0.5); }
	h3 { margin: 0 0 0.75rem; font-size: 1.1rem; }
	p { margin: 0 0 1.25rem; color: var(--text-muted); font-size: 0.9rem; line-height: 1.5; }
	.actions { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 0.5rem; }
</style>
