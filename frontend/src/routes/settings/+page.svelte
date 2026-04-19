<script lang="ts">
	import { onMount } from 'svelte';
	import { shared, me, type SharedAccess, type UserProfile } from '$lib/api/client';
	import ConfirmDialog from '$lib/components/ConfirmDialog.svelte';
	import { setCurrencySymbol, DEFAULT_CURRENCY_SYMBOL } from '$lib/stores/auth';

	let profile = $state<UserProfile | null>(null);
	let sharedList = $state<SharedAccess[]>([]);
	let loading = $state(true);
	let error = $state('');
	let showShareForm = $state(false);

	// Confirm dialog
	let confirmOpen = $state(false);
	let confirmMessage = $state('');
	let confirmAction = $state(() => {});

	let fEmail = $state('');
	let fPermission = $state('read');

	// Password change
	let pwCurrent = $state('');
	let pwNew = $state('');
	let pwConfirm = $state('');
	let pwError = $state('');
	let pwSuccess = $state(false);

	// Profile edit
	let profileUsername = $state('');
	let profileEmail = $state('');
	let profileName = $state('');
	let profileError = $state('');
	let profileSuccess = $state(false);

	// Currency symbol
	let currencyInput = $state(DEFAULT_CURRENCY_SYMBOL);
	let currencyError = $state('');
	let currencySaved = $state(false);

	// 2FA
	let totpQR = $state('');
	let totpSecret = $state('');
	let totpCode = $state('');
	let totpError = $state('');
	let totpSetupActive = $state(false);
	let totpResetPassword = $state('');
	let showTotpReset = $state(false);

	onMount(async () => {
		loading = true;
		const [p, s] = await Promise.all([me.get(), shared.list()]);
		profile = p;
		profileUsername = p.username;
		profileEmail = p.email;
		profileName = p.name;
		currencyInput = p.currency_symbol ?? DEFAULT_CURRENCY_SYMBOL;
		setCurrencySymbol(currencyInput);
		sharedList = s;
		loading = false;
	});

	async function saveCurrency(e: Event) {
		e.preventDefault();
		currencyError = '';
		currencySaved = false;
		const sym = currencyInput.trim();
		try {
			await me.updateProfile({ currency_symbol: sym });
			setCurrencySymbol(sym);
			if (profile) profile.currency_symbol = sym;
			currencySaved = true;
		} catch (e: any) {
			currencyError = e.message || 'Failed to update currency symbol';
		}
	}

	async function loadShared() {
		sharedList = await shared.list();
	}

	async function submitShare(e: Event) {
		e.preventDefault();
		error = '';
		try {
			await shared.create(fEmail, fPermission);
			fEmail = '';
			fPermission = 'read';
			showShareForm = false;
			await loadShared();
		} catch (e: any) {
			error = e.message;
		}
	}

	function removeShare(id: number) {
		confirmMessage = 'Revoke this shared access?';
		confirmAction = async () => {
			await shared.delete(id);
			await loadShared();
		};
		confirmOpen = true;
	}

	async function changePassword(e: Event) {
		e.preventDefault();
		pwError = '';
		pwSuccess = false;
		if (pwNew.length < 8) { pwError = 'Password must be at least 8 characters'; return; }
		if (pwNew !== pwConfirm) { pwError = 'Passwords do not match'; return; }
		try {
			await me.changePassword(pwCurrent, pwNew);
			pwCurrent = ''; pwNew = ''; pwConfirm = '';
			pwSuccess = true;
		} catch (e: any) {
			pwError = e.message || 'Failed to change password';
		}
	}

	async function updateProfile(e: Event) {
		e.preventDefault();
		profileError = '';
		profileSuccess = false;
		const data: Record<string, string> = {};
		if (profileUsername !== profile?.username) data.username = profileUsername;
		if (profileEmail !== profile?.email) data.email = profileEmail;
		if (profileName !== profile?.name) data.name = profileName;
		if (Object.keys(data).length === 0) return;
		try {
			await me.updateProfile(data);
			profile = await me.get();
			profileSuccess = true;
		} catch (e: any) {
			profileError = e.message || 'Failed to update profile';
		}
	}

	async function startTOTPSetup() {
		totpError = '';
		try {
			const res = await me.totpSetup();
			totpQR = res.qr_code;
			totpSecret = res.secret;
			totpSetupActive = true;
		} catch (e: any) {
			totpError = e.message;
		}
	}

	async function verifyTOTP(e: Event) {
		e.preventDefault();
		totpError = '';
		try {
			await me.totpVerify(totpCode);
			totpSetupActive = false;
			totpCode = '';
			totpQR = '';
			totpSecret = '';
			profile = await me.get();
		} catch (e: any) {
			totpError = e.message || 'Invalid code';
		}
	}

	async function resetTOTP(e: Event) {
		e.preventDefault();
		totpError = '';
		try {
			const res = await me.totpReset(totpResetPassword);
			totpResetPassword = '';
			showTotpReset = false;
			// Go straight into setup flow with the new QR
			totpQR = res.qr_code;
			totpSecret = res.secret;
			totpSetupActive = true;
			profile = await me.get();
		} catch (e: any) {
			totpError = e.message || 'Incorrect password';
		}
	}
</script>

<div class="page">
	<h1>Settings</h1>

	{#if loading}
		<p>Loading...</p>
	{:else}
		<!-- Profile -->
		<div class="card section">
			<h2>Profile</h2>
			{#if profileError}<p class="error-msg">{profileError}</p>{/if}
			{#if profileSuccess}<p class="success-msg">Profile updated.</p>{/if}
			<form onsubmit={updateProfile}>
				<div class="form-row">
					<div class="form-group">
						<label for="p-username">Username</label>
						<input id="p-username" type="text" bind:value={profileUsername} required />
					</div>
					<div class="form-group">
						<label for="p-email">Email</label>
						<input id="p-email" type="email" bind:value={profileEmail} required />
					</div>
					<div class="form-group">
						<label for="p-name">Name</label>
						<input id="p-name" type="text" bind:value={profileName} required />
					</div>
					<div class="form-group" style="display: flex; align-items: flex-end">
						<button class="btn-primary" type="submit">Save</button>
					</div>
				</div>
			</form>
		</div>

		<!-- Currency -->
		<div class="card section">
			<h2>Currency Symbol</h2>
			<p class="desc">Shown in front of every amount across the app. Examples: €, $, £, ¥, kr, zł.</p>
			{#if currencyError}<p class="error-msg">{currencyError}</p>{/if}
			{#if currencySaved}<p class="success-msg">Saved.</p>{/if}
			<form onsubmit={saveCurrency}>
				<div class="form-row">
					<div class="form-group">
						<label for="cur-sym">Symbol</label>
						<input id="cur-sym" type="text" maxlength="8" bind:value={currencyInput} style="max-width: 8rem" />
					</div>
					<div class="form-group" style="display: flex; align-items: flex-end">
						<button class="btn-primary" type="submit">Save</button>
					</div>
				</div>
			</form>
		</div>

		<!-- Change Password -->
		<div class="card section">
			<h2>Change Password</h2>
			{#if pwError}<p class="error-msg">{pwError}</p>{/if}
			{#if pwSuccess}<p class="success-msg">Password changed.</p>{/if}
			<form onsubmit={changePassword}>
				<div class="form-row">
					<div class="form-group">
						<label for="pw-c">Current Password</label>
						<input id="pw-c" type="password" bind:value={pwCurrent} required />
					</div>
					<div class="form-group">
						<label for="pw-n">New Password</label>
						<input id="pw-n" type="password" bind:value={pwNew} required minlength="8" />
					</div>
					<div class="form-group">
						<label for="pw-cf">Confirm</label>
						<input id="pw-cf" type="password" bind:value={pwConfirm} required minlength="8" />
					</div>
				</div>
				<button class="btn-primary" type="submit">Update Password</button>
			</form>
		</div>

		<!-- 2FA -->
		<div class="card section">
			<h2>Two-Factor Authentication (2FA)</h2>
			{#if totpError}<p class="error-msg">{totpError}</p>{/if}

			{#if profile?.totp_enabled && !totpSetupActive}
				<p class="status-on">2FA is enabled</p>
				{#if showTotpReset}
					<form onsubmit={resetTOTP} class="totp-reset-form">
						<div class="form-group">
							<label for="totp-pw">Enter your password to reset 2FA</label>
							<input id="totp-pw" type="password" bind:value={totpResetPassword} required />
						</div>
						<p class="desc">This will generate a new QR code. You'll need to scan it again with your authenticator app.</p>
						<div class="actions">
							<button class="btn-primary" type="submit">Reset 2FA</button>
							<button class="btn-ghost" type="button" onclick={() => showTotpReset = false}>Cancel</button>
						</div>
					</form>
				{:else}
					<button class="btn-ghost" onclick={() => showTotpReset = true}>Reset 2FA</button>
				{/if}
			{:else if totpSetupActive}
				<div class="totp-setup">
					<p class="desc">Scan this QR code with your authenticator app (Google Authenticator, Authy, etc.)</p>
					<div class="qr-wrap">
						{#if totpQR?.startsWith('data:image/')}
							<img src={totpQR} alt="TOTP QR Code" />
						{/if}
					</div>
					<p class="manual-key">Manual key: <code>{totpSecret}</code></p>
					<form onsubmit={verifyTOTP}>
						<div class="form-group">
							<label for="totp-v">Enter the 6-digit code to verify</label>
							<input
								id="totp-v"
								type="text"
								inputmode="numeric"
								pattern="[0-9]*"
								maxlength="6"
								bind:value={totpCode}
								required
								placeholder="000000"
							/>
						</div>
						<div class="actions">
							<button class="btn-primary" type="submit">Verify & Enable</button>
							<button class="btn-ghost" type="button" onclick={() => { totpSetupActive = false; totpQR = ''; totpSecret = ''; }}>Cancel</button>
						</div>
					</form>
				</div>
			{:else}
				<p class="desc">Add an extra layer of security to your account using an authenticator app.</p>
				<button class="btn-primary" onclick={startTOTPSetup}>Set Up 2FA</button>
			{/if}
		</div>

		<!-- Shared Access -->
		<div class="card section">
			<div class="section-header">
				<h2>Shared Access</h2>
				<button class="btn-primary" onclick={() => showShareForm = !showShareForm}>
					{showShareForm ? 'Cancel' : '+ Share'}
				</button>
			</div>
			<p class="desc">Share your financial data with other users.</p>

			{#if error}<p class="error-msg">{error}</p>{/if}

			{#if showShareForm}
				<form onsubmit={submitShare} style="margin: 1rem 0">
					<div class="form-row">
						<div class="form-group">
							<label for="s-email">User Email</label>
							<input id="s-email" type="email" bind:value={fEmail} required placeholder="user@example.com" />
						</div>
						<div class="form-group">
							<label for="s-perm">Permission</label>
							<select id="s-perm" bind:value={fPermission}>
								<option value="read">Read only</option>
								<option value="write">Read & Write</option>
							</select>
						</div>
						<div class="form-group" style="display: flex; align-items: flex-end">
							<button class="btn-primary" type="submit">Share</button>
						</div>
					</div>
				</form>
			{/if}

			{#if sharedList.length === 0}
				<p class="empty-state">Not shared with anyone.</p>
			{:else}
				<div class="table-wrap">
					<table>
						<thead>
							<tr><th>Name</th><th>Email</th><th>Permission</th><th></th></tr>
						</thead>
						<tbody>
							{#each sharedList as s}
								<tr>
									<td>{s.guest_name}</td>
									<td>{s.guest_email}</td>
									<td><span class="badge">{s.permission}</span></td>
									<td><button class="btn-danger" onclick={() => removeShare(s.id)}>Revoke</button></td>
								</tr>
							{/each}
						</tbody>
					</table>
				</div>
			{/if}
		</div>
	{/if}
</div>

<ConfirmDialog bind:open={confirmOpen} message={confirmMessage} confirmText="Revoke" onconfirm={confirmAction} />

<style>
	.section {
		margin-bottom: 1.5rem;
	}
	.section-header {
		display: flex;
		justify-content: space-between;
		align-items: center;
		margin-bottom: 0.5rem;
	}
	h2 { font-size: 1.1rem; margin-bottom: 0.75rem; }
	.desc {
		color: var(--text-muted);
		font-size: 0.85rem;
		margin-bottom: 1rem;
	}
	.success-msg {
		color: var(--success);
		font-size: 0.85rem;
		margin-bottom: 1rem;
	}
	.status-on {
		color: var(--success);
		font-weight: 600;
		margin-bottom: 1rem;
	}
	.totp-setup {
		margin-top: 0.5rem;
	}
	.qr-wrap {
		background: white;
		display: inline-block;
		padding: 0.5rem;
		border-radius: var(--radius);
		margin-bottom: 1rem;
	}
	.qr-wrap img {
		display: block;
		width: 200px;
		height: 200px;
	}
	.manual-key {
		font-size: 0.85rem;
		color: var(--text-muted);
		margin-bottom: 1rem;
	}
	.manual-key code {
		background: var(--bg);
		padding: 0.2rem 0.5rem;
		border-radius: 4px;
		font-size: 0.8rem;
		user-select: all;
	}
	.totp-reset-form {
		margin-top: 0.75rem;
	}
</style>
