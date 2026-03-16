<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { admin, type AuditLog } from '$lib/api/client';
	import { isAdmin } from '$lib/stores/auth';

	let logs = $state<AuditLog[]>([]);
	let loading = $state(true);
	let error = $state('');
	let offset = $state(0);
	const limit = 50;
	let hasMore = $state(true);

	onMount(async () => {
		if (!$isAdmin) { goto('/'); return; }
		await load();
	});

	async function load() {
		loading = true;
		try {
			const result = await admin.auditLogs({ limit: limit.toString(), offset: offset.toString() });
			logs = result;
			hasMore = result.length === limit;
		} catch (e: any) {
			error = e.message;
		}
		loading = false;
	}

	async function nextPage() {
		offset += limit;
		await load();
	}

	async function prevPage() {
		offset = Math.max(0, offset - limit);
		await load();
	}
</script>

<div class="page">
	<div class="page-header">
		<h1>Audit Logs</h1>
		<a href="/admin" class="btn-ghost" style="padding: 0.5rem 1rem; border-radius: var(--radius); border: 1px solid var(--border); text-decoration: none; font-size: 0.875rem;">Back to Admin</a>
	</div>

	{#if error}
		<p class="error-msg">{error}</p>
	{/if}

	{#if loading}
		<p>Loading...</p>
	{:else if logs.length === 0}
		<p class="empty-state">No audit logs found</p>
	{:else}
		<div class="card table-wrap">
			<table>
				<thead>
					<tr>
						<th>Time</th>
						<th>Admin</th>
						<th>Action</th>
						<th>Target User</th>
						<th>Details</th>
						<th>IP</th>
					</tr>
				</thead>
				<tbody>
					{#each logs as log}
						<tr>
							<td class="time-cell">{log.created_at.replace('T', ' ').slice(0, 19)}</td>
							<td>{log.admin_username}</td>
							<td><span class="badge badge-action">{log.action}</span></td>
							<td>{log.target_user_id ?? '—'}</td>
							<td>{log.details || '—'}</td>
							<td class="time-cell">{log.ip_address || '—'}</td>
						</tr>
					{/each}
				</tbody>
			</table>
		</div>

		<div class="pagination">
			<button class="btn-ghost" onclick={prevPage} disabled={offset === 0}>Previous</button>
			<span class="page-info">Page {Math.floor(offset / limit) + 1}</span>
			<button class="btn-ghost" onclick={nextPage} disabled={!hasMore}>Next</button>
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
	.time-cell {
		color: var(--text-muted);
		font-size: 0.85rem;
		white-space: nowrap;
	}
	.badge-action {
		background: rgba(99, 102, 241, 0.12);
		color: var(--primary);
		border: 1px solid rgba(99, 102, 241, 0.25);
	}
	.pagination {
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 1rem;
		margin-top: 1rem;
	}
	.page-info {
		color: var(--text-muted);
		font-size: 0.85rem;
	}
</style>
