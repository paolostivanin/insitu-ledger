<script lang="ts">
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
</script>

<svelte:head>
	<title>Error · InSitu Ledger</title>
</svelte:head>

<div class="wrap">
	<div class="card">
		<div class="status">{page.status}</div>
		<h1>{page.status === 404 ? 'Page not found' : 'Something went wrong'}</h1>
		<p class="msg">
			{page.status === 404
				? "The page you're looking for doesn't exist or has been moved."
				: "We ran into an unexpected error. Try going back, or return to the dashboard."}
		</p>
		<div class="actions">
			<button type="button" onclick={() => history.back()}>Go back</button>
			<button type="button" class="primary" onclick={() => goto('/')}>Dashboard</button>
		</div>
	</div>
</div>

<style>
	.wrap {
		min-height: 100dvh;
		display: grid;
		place-items: center;
		padding: 1rem;
	}
	.card {
		max-width: 28rem;
		text-align: center;
		padding: 2rem;
		border-radius: 1rem;
		background: var(--surface, #fff);
		box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
	}
	.status {
		font-size: 4rem;
		font-weight: 700;
		color: var(--accent, #6366f1);
		line-height: 1;
	}
	h1 {
		margin: 0.75rem 0 0.5rem;
		font-size: 1.5rem;
	}
	.msg {
		color: var(--muted, #64748b);
		margin-bottom: 1.5rem;
	}
	.actions {
		display: flex;
		gap: 0.5rem;
		justify-content: center;
	}
	button {
		padding: 0.6rem 1.2rem;
		border-radius: 0.5rem;
		border: 1px solid var(--border, #e2e8f0);
		background: var(--surface, #fff);
		color: inherit;
		font: inherit;
		cursor: pointer;
	}
	button.primary {
		background: var(--accent, #6366f1);
		color: white;
		border-color: transparent;
	}
	button:hover {
		filter: brightness(0.97);
	}
</style>
