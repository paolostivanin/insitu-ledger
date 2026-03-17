<script lang="ts">
	let {
		open = $bindable(false),
		title = 'Confirm',
		message = 'Are you sure?',
		confirmText = 'Delete',
		cancelText = 'Cancel',
		danger = true,
		onconfirm
	}: {
		open: boolean;
		title?: string;
		message?: string;
		confirmText?: string;
		cancelText?: string;
		danger?: boolean;
		onconfirm: () => void;
	} = $props();

	function handleConfirm() {
		open = false;
		onconfirm();
	}

	function handleCancel() {
		open = false;
	}

	function handleKeydown(e: KeyboardEvent) {
		if (e.key === 'Escape') handleCancel();
	}
</script>

{#if open}
	<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
	<div class="overlay" role="dialog" aria-modal="true" tabindex="-1" onkeydown={handleKeydown}>
		<!-- svelte-ignore a11y_no_static_element_interactions a11y_click_events_have_key_events -->
		<div class="backdrop" onclick={handleCancel}></div>
		<div class="dialog">
			<h3>{title}</h3>
			<p>{message}</p>
			<div class="actions">
				<button class="btn-ghost" onclick={handleCancel}>{cancelText}</button>
				<button class={danger ? 'btn-danger' : 'btn-primary'} onclick={handleConfirm}>{confirmText}</button>
			</div>
		</div>
	</div>
{/if}

<style>
	.overlay {
		position: fixed;
		inset: 0;
		z-index: 100;
		display: flex;
		align-items: center;
		justify-content: center;
	}
	.backdrop {
		position: absolute;
		inset: 0;
		background: rgba(0, 0, 0, 0.5);
	}
	.dialog {
		position: relative;
		background: var(--bg-card);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		padding: 1.5rem;
		min-width: 340px;
		max-width: 450px;
		box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3);
	}
	h3 {
		margin: 0 0 0.75rem;
		font-size: 1.1rem;
	}
	p {
		margin: 0 0 1.25rem;
		color: var(--text-muted);
		font-size: 0.9rem;
		line-height: 1.5;
	}
	.actions {
		display: flex;
		justify-content: flex-end;
		gap: 0.5rem;
	}
</style>
