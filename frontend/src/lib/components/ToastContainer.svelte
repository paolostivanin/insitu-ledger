<script lang="ts">
	import { toasts, dismissToast } from '$lib/stores/toast';
</script>

{#if $toasts.length > 0}
	<div class="toast-container">
		{#each $toasts as toast (toast.id)}
			<div class="toast toast-{toast.type}" onclick={() => dismissToast(toast.id)} onkeydown={() => {}} role="alert" tabindex="-1">
				<span>{toast.message}</span>
				<button class="toast-close" onclick={() => dismissToast(toast.id)}>&times;</button>
			</div>
		{/each}
	</div>
{/if}

<style>
	.toast-container {
		position: fixed;
		bottom: 1.5rem;
		right: 1.5rem;
		z-index: 300;
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
		max-width: 400px;
	}
	.toast {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 0.75rem;
		padding: 0.75rem 1rem;
		border-radius: var(--radius);
		font-size: 0.875rem;
		cursor: pointer;
		animation: slideIn 0.2s ease-out;
		box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
	}
	.toast-success {
		background: var(--success);
		color: white;
	}
	.toast-error {
		background: var(--danger);
		color: white;
	}
	.toast-close {
		background: none;
		border: none;
		color: white;
		font-size: 1.2rem;
		padding: 0;
		cursor: pointer;
		opacity: 0.8;
	}
	.toast-close:hover {
		opacity: 1;
	}
	@keyframes slideIn {
		from {
			transform: translateX(100%);
			opacity: 0;
		}
		to {
			transform: translateX(0);
			opacity: 1;
		}
	}
</style>
