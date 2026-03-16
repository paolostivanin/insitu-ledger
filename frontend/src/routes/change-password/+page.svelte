<script lang="ts">
	import { goto } from '$app/navigation';
	import { me } from '$lib/api/client';
	import { forcePasswordChange, forceTotpSetup, clearForcePasswordChange } from '$lib/stores/auth';

	let currentPassword = $state('');
	let newPassword = $state('');
	let confirmPassword = $state('');
	let error = $state('');
	let success = $state(false);

	async function submit(e: Event) {
		e.preventDefault();
		error = '';

		if (newPassword.length < 8) {
			error = 'Password must be at least 8 characters';
			return;
		}
		if (newPassword !== confirmPassword) {
			error = 'Passwords do not match';
			return;
		}

		try {
			await me.changePassword(currentPassword, newPassword);
			clearForcePasswordChange();
			success = true;
			setTimeout(() => {
				if ($forceTotpSetup) {
					goto('/setup-2fa');
				} else {
					goto('/');
				}
			}, 1500);
		} catch (err: any) {
			error = err.message || 'Failed to change password';
		}
	}
</script>

<div class="auth-page">
	<div class="auth-card card">
		<h1>Change Password</h1>
		{#if $forcePasswordChange}
			<p class="warning">You must change your password before continuing.</p>
		{/if}

		{#if success}
			<p class="success">Password changed. Redirecting...</p>
		{:else}
			{#if error}
				<p class="error-msg">{error}</p>
			{/if}

			<form onsubmit={submit}>
				<div class="form-group">
					<label for="current">Current Password</label>
					<input id="current" type="password" bind:value={currentPassword} required />
				</div>
				<div class="form-group">
					<label for="new">New Password</label>
					<input id="new" type="password" bind:value={newPassword} required minlength="8" />
				</div>
				<div class="form-group">
					<label for="confirm">Confirm New Password</label>
					<input id="confirm" type="password" bind:value={confirmPassword} required minlength="8" />
				</div>
				<button class="btn-primary full-width" type="submit">Change Password</button>
			</form>

			{#if !$forcePasswordChange}
				<p class="auth-link"><a href="/">Back to Dashboard</a></p>
			{/if}
		{/if}
	</div>
</div>

<style>
	.auth-page {
		display: flex;
		align-items: center;
		justify-content: center;
		min-height: 100vh;
	}
	.auth-card {
		width: 100%;
		max-width: 400px;
	}
	.auth-card h1 {
		color: var(--primary);
		margin-bottom: 0.5rem;
	}
	.warning {
		color: var(--warning);
		font-size: 0.9rem;
		margin-bottom: 1rem;
	}
	.success {
		color: var(--success);
		font-size: 0.9rem;
	}
	.full-width {
		width: 100%;
		padding: 0.65rem;
	}
	.auth-link {
		text-align: center;
		margin-top: 1rem;
		font-size: 0.85rem;
		color: var(--text-muted);
	}
</style>
