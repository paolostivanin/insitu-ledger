<script lang="ts">
	import { goto } from '$app/navigation';
	import { me } from '$lib/api/client';
	import { forceTotpSetup, clearForceTotpSetup } from '$lib/stores/auth';

	let totpQR = $state('');
	let totpSecret = $state('');
	let totpCode = $state('');
	let error = $state('');
	let loading = $state(true);

	async function startSetup() {
		try {
			const res = await me.totpSetup();
			totpQR = res.qr_code;
			totpSecret = res.secret;
			loading = false;
		} catch (e: any) {
			error = e.message || 'Failed to set up 2FA';
			loading = false;
		}
	}

	startSetup();

	async function verify(e: Event) {
		e.preventDefault();
		error = '';
		try {
			await me.totpVerify(totpCode);
			clearForceTotpSetup();
			goto('/');
		} catch (e: any) {
			error = e.message || 'Invalid code';
		}
	}
</script>

<div class="auth-page">
	<div class="auth-card card">
		<h1>Set Up 2FA</h1>
		{#if $forceTotpSetup}
			<p class="warning">Two-factor authentication is required before you can continue.</p>
		{/if}

		{#if error}
			<p class="error-msg">{error}</p>
		{/if}

		{#if loading}
			<p>Generating QR code...</p>
		{:else}
			<p class="desc">Scan this QR code with your authenticator app (Google Authenticator, Authy, etc.)</p>
			<div class="qr-wrap">
				{#if totpQR?.startsWith('data:image/')}
					<img src={totpQR} alt="TOTP QR Code" />
				{/if}
			</div>
			<p class="manual-key">Manual key: <code>{totpSecret}</code></p>
			<form onsubmit={verify}>
				<div class="form-group">
					<label for="totp-code">Enter the 6-digit code to verify</label>
					<input
						id="totp-code"
						type="text"
						inputmode="numeric"
						pattern="[0-9]*"
						maxlength="6"
						bind:value={totpCode}
						required
						placeholder="000000"
					/>
				</div>
				<button class="btn-primary full-width" type="submit">Verify & Enable 2FA</button>
			</form>
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
		max-width: 440px;
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
	.desc {
		color: var(--text-muted);
		font-size: 0.85rem;
		margin-bottom: 1rem;
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
	.full-width {
		width: 100%;
		padding: 0.65rem;
	}
</style>
