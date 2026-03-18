<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { admin, type AdminUser, type BackupSettings } from '$lib/api/client';
	import { isAdmin } from '$lib/stores/auth';
	import ConfirmDialog from '$lib/components/ConfirmDialog.svelte';

	let users = $state<AdminUser[]>([]);
	let loading = $state(true);
	let error = $state('');

	// Confirm dialog
	let confirmOpen = $state(false);
	let confirmMessage = $state('');
	let confirmAction = $state(() => {});
	let showCreateForm = $state(false);
	let showResetForm = $state<number | null>(null);
	let editUserId = $state<number | null>(null);

	// Create user form
	let fUsername = $state('');
	let fName = $state('');
	let fEmail = $state('');
	let fPassword = $state('');

	// Reset password form
	let fResetPassword = $state('');

	// Edit user form
	let editUsername = $state('');
	let editName = $state('');
	let editEmail = $state('');

	onMount(async () => {
		if (!$isAdmin) { goto('/'); return; }
		await Promise.all([load(), loadBackupSettings()]);
	});

	async function load() {
		loading = true;
		try { users = await admin.listUsers(); } catch (e: any) { error = e.message; }
		loading = false;
	}

	async function createUser(e: Event) {
		e.preventDefault();
		error = '';
		try {
			await admin.createUser(fUsername, fEmail, fName, fPassword);
			fUsername = ''; fName = ''; fEmail = ''; fPassword = '';
			showCreateForm = false;
			await load();
		} catch (e: any) { error = e.message; }
	}

	function startEdit(user: AdminUser) {
		editUserId = user.id;
		editUsername = user.username;
		editName = user.name;
		editEmail = user.email;
	}

	async function saveEdit(e: Event) {
		e.preventDefault();
		if (!editUserId) return;
		error = '';
		try {
			await admin.updateUser(editUserId, { username: editUsername, name: editName, email: editEmail });
			editUserId = null;
			await load();
		} catch (e: any) { error = e.message; }
	}

	function deleteUser(id: number, name: string) {
		confirmMessage = `Delete user "${name}"? All their data will be permanently removed.`;
		confirmAction = async () => {
			try { await admin.deleteUser(id); await load(); } catch (e: any) { error = e.message; }
		};
		confirmOpen = true;
	}

	async function resetPassword(e: Event, id: number) {
		e.preventDefault();
		error = '';
		if (fResetPassword.length < 8) { error = 'Password must be at least 8 characters'; return; }
		try {
			await admin.resetPassword(id, fResetPassword);
			fResetPassword = ''; showResetForm = null;
			await load();
		} catch (e: any) { error = e.message; }
	}

	async function toggleAdmin(id: number) {
		try { await admin.toggleAdmin(id); await load(); } catch (e: any) { error = e.message; }
	}

	function disableTOTP(id: number, name: string) {
		confirmMessage = `Disable 2FA for "${name}"?`;
		confirmAction = async () => {
			try { await admin.disableTOTP(id); await load(); } catch (e: any) { error = e.message; }
		};
		confirmOpen = true;
	}

	// Backup settings
	let backupSettings = $state<BackupSettings | null>(null);
	let bkEnabled = $state(false);
	let bkFrequency = $state('daily');
	let bkRetention = $state(7);
	let savingBackupSettings = $state(false);

	async function loadBackupSettings() {
		try {
			backupSettings = await admin.getBackupSettings();
			bkEnabled = backupSettings.enabled;
			bkFrequency = backupSettings.frequency;
			bkRetention = backupSettings.retention_count;
		} catch (e: any) { error = e.message; }
	}

	async function saveBackupSettings() {
		savingBackupSettings = true;
		error = '';
		try {
			await admin.updateBackupSettings({ enabled: bkEnabled, frequency: bkFrequency, retention_count: bkRetention });
			await loadBackupSettings();
		} catch (e: any) { error = e.message; }
		savingBackupSettings = false;
	}

	async function downloadBackup() {
		error = '';
		try { await admin.backup(); } catch (e: any) { error = e.message; }
	}
</script>

<div class="page">
	<div class="page-header">
		<h1>User Management</h1>
		<div class="header-actions">
			<a href="/admin/audit-logs" class="btn-ghost" style="padding: 0.5rem 1rem; border-radius: var(--radius); border: 1px solid var(--border); text-decoration: none; font-size: 0.875rem;">Audit Logs</a>
			<button class="btn-ghost" onclick={downloadBackup}>Download Backup</button>
			<button class="btn-primary" onclick={() => showCreateForm = !showCreateForm}>
				{showCreateForm ? 'Cancel' : '+ New User'}
			</button>
		</div>
	</div>

	{#if error}
		<p class="error-msg">{error}</p>
	{/if}

	{#if showCreateForm}
		<div class="card" style="margin-bottom: 1.5rem">
			<h2>Create User</h2>
			<p class="hint">User will be required to change their password on first login.</p>
			<form onsubmit={createUser}>
				<div class="form-row">
					<div class="form-group">
						<label for="username">Username</label>
						<input id="username" type="text" bind:value={fUsername} required />
					</div>
					<div class="form-group">
						<label for="name">Name</label>
						<input id="name" type="text" bind:value={fName} required />
					</div>
					<div class="form-group">
						<label for="email">Email</label>
						<input id="email" type="email" bind:value={fEmail} required />
					</div>
					<div class="form-group">
						<label for="password">Temporary Password</label>
						<input id="password" type="password" bind:value={fPassword} required minlength="8" />
					</div>
				</div>
				<button class="btn-primary" type="submit">Create User</button>
			</form>
		</div>
	{/if}

	{#if loading}
		<p>Loading...</p>
	{:else}
		<div class="card table-wrap">
			<table>
				<thead>
					<tr>
						<th>Username</th>
						<th>Name</th>
						<th>Email</th>
						<th>Role</th>
						<th>2FA</th>
						<th>Status</th>
						<th>Created</th>
						<th></th>
					</tr>
				</thead>
				<tbody>
					{#each users as user}
						<tr>
							<td>{user.username}</td>
							<td>{user.name}</td>
							<td>{user.email}</td>
							<td>
								<span class="badge" class:badge-admin={user.is_admin}>
									{user.is_admin ? 'Admin' : 'User'}
								</span>
							</td>
							<td>
								{#if user.totp_enabled}
									<span class="badge badge-ok">Enabled</span>
								{:else}
									<span class="badge badge-off">Off</span>
								{/if}
							</td>
							<td>
								{#if user.force_password_change}
									<span class="badge badge-warning">Must change PW</span>
								{:else}
									<span class="badge badge-ok">Active</span>
								{/if}
							</td>
							<td>{user.created_at.slice(0, 10)}</td>
							<td class="actions">
								<button class="btn-ghost" onclick={() => startEdit(user)}>Edit</button>
								<button class="btn-ghost" onclick={() => toggleAdmin(user.id)}>
									{user.is_admin ? 'Demote' : 'Promote'}
								</button>
								<button class="btn-ghost" onclick={() => { showResetForm = showResetForm === user.id ? null : user.id; fResetPassword = ''; }}>
									Reset PW
								</button>
								{#if user.totp_enabled}
									<button class="btn-ghost" onclick={() => disableTOTP(user.id, user.name)}>
										Disable 2FA
									</button>
								{/if}
								<button class="btn-danger" onclick={() => deleteUser(user.id, user.name)}>Delete</button>
							</td>
						</tr>
						{#if editUserId === user.id}
							<tr>
								<td colspan="8">
									<form class="inline-form" onsubmit={saveEdit}>
										<input type="text" bind:value={editUsername} placeholder="Username" required />
										<input type="text" bind:value={editName} placeholder="Name" required />
										<input type="email" bind:value={editEmail} placeholder="Email" required />
										<button class="btn-primary" type="submit">Save</button>
										<button class="btn-ghost" type="button" onclick={() => editUserId = null}>Cancel</button>
									</form>
								</td>
							</tr>
						{/if}
						{#if showResetForm === user.id}
							<tr>
								<td colspan="8">
									<form class="inline-form" onsubmit={(e) => resetPassword(e, user.id)}>
										<input type="password" bind:value={fResetPassword} placeholder="New temporary password (min 8 chars)" required minlength="8" />
										<button class="btn-primary" type="submit">Set Password</button>
										<span class="hint">User will be forced to change it on next login.</span>
									</form>
								</td>
							</tr>
						{/if}
					{/each}
				</tbody>
			</table>
		</div>
	{/if}

	<div class="card backup-settings" style="margin-top: 1.5rem">
		<h2>Automatic Backup Schedule</h2>
		{#if backupSettings}
			<div class="form-row">
				<div class="form-group">
					<label>
						<input type="checkbox" bind:checked={bkEnabled} />
						Enable automatic backups
					</label>
				</div>
				<div class="form-group">
					<label for="bk-freq">Frequency</label>
					<select id="bk-freq" bind:value={bkFrequency} disabled={!bkEnabled}>
						<option value="daily">Daily</option>
						<option value="weekly">Weekly</option>
						<option value="monthly">Monthly</option>
					</select>
				</div>
				<div class="form-group">
					<label for="bk-retention">Keep last N backups</label>
					<input id="bk-retention" type="number" min="1" max="100" bind:value={bkRetention} disabled={!bkEnabled} />
				</div>
				<div class="form-group" style="align-self: flex-end">
					<button class="btn-primary" onclick={saveBackupSettings} disabled={savingBackupSettings}>
						{savingBackupSettings ? 'Saving...' : 'Save'}
					</button>
				</div>
			</div>
			{#if backupSettings.last_backup_at}
				<p class="hint">Last automatic backup: {backupSettings.last_backup_at}</p>
			{:else}
				<p class="hint">No automatic backups yet.</p>
			{/if}
		{:else}
			<p>Loading backup settings...</p>
		{/if}
	</div>
</div>

<ConfirmDialog bind:open={confirmOpen} message={confirmMessage} onconfirm={confirmAction} />

<style>
	.page-header {
		display: flex;
		justify-content: space-between;
		align-items: center;
		margin-bottom: 1rem;
	}
	.header-actions {
		display: flex;
		gap: 0.5rem;
		align-items: center;
	}
	h2 { font-size: 1rem; margin-bottom: 0.25rem; }
	.hint {
		color: var(--text-muted);
		font-size: 0.8rem;
		margin-bottom: 0.75rem;
	}
	.badge-admin {
		background: rgba(99, 102, 241, 0.12);
		color: var(--primary);
		border: 1px solid rgba(99, 102, 241, 0.25);
	}
	.badge-warning {
		background: rgba(245, 158, 11, 0.10);
		color: #fbbf24;
		border: 1px solid rgba(245, 158, 11, 0.25);
	}
	.badge-ok {
		background: rgba(34, 197, 94, 0.10);
		color: var(--success);
		border: 1px solid rgba(34, 197, 94, 0.25);
	}
	.badge-off {
		background: rgba(139, 143, 163, 0.10);
		color: var(--text-muted);
		border: 1px solid rgba(139, 143, 163, 0.2);
	}
	.inline-form {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		padding: 0.5rem 0;
	}
	.inline-form input {
		max-width: 250px;
	}
</style>
