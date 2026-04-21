<script lang="ts">
	import { goto } from '$app/navigation';
	import { auth, setToken } from '$lib/api/client';
	import { setAuthUser } from '$lib/stores/auth';

	let login = $state('');
	let password = $state('');
	let totpCode = $state('');
	let trustDevice = $state(false);
	let error = $state('');
	let needsTOTP = $state(false);
	let totpInput: HTMLInputElement | undefined = $state();

	$effect(() => {
		if (needsTOTP) totpInput?.focus();
	});

	async function submit(e: Event) {
		e.preventDefault();
		error = '';
		try {
			const res = await auth.login(
				login,
				password,
				needsTOTP ? totpCode : undefined,
				needsTOTP ? trustDevice : undefined
			);

			if (res.totp_required) {
				needsTOTP = true;
				return;
			}

			if (!res.token) {
				error = 'Login failed';
				return;
			}

			setToken(res.token);
			setAuthUser(res.name, res.user_id, res.is_admin, res.force_password_change, res.totp_enabled, res.currency_symbol);
			if (res.force_password_change) {
				goto('/change-password');
			} else if (!res.totp_enabled) {
				goto('/setup-2fa');
			} else {
				goto('/');
			}
		} catch (err: any) {
			error = err.message || 'Login failed';
		}
	}
</script>

<div class="auth-page">
	<div class="auth-card card">
		<h1>InSitu Ledger</h1>
		<p class="subtitle">{needsTOTP ? 'Enter your 2FA code' : 'Sign in to your account'}</p>

		{#if error}
			<p class="error-msg">{error}</p>
		{/if}

		<form onsubmit={submit}>
			{#if !needsTOTP}
				<div class="form-group">
					<label for="login">Username or Email</label>
					<input id="login" type="text" bind:value={login} required />
				</div>
				<div class="form-group">
					<label for="password">Password</label>
					<input id="password" type="password" bind:value={password} required />
				</div>
			{:else}
				<div class="form-group">
					<label for="totp">Authentication Code</label>
					<input
						id="totp"
						type="text"
						inputmode="numeric"
						pattern="[0-9]*"
						maxlength="6"
						bind:value={totpCode}
						bind:this={totpInput}
						required
						placeholder="6-digit code"
					/>
					<p class="hint">Enter the code from your authenticator app</p>
				</div>
				<div class="form-group">
					<label class="checkbox-label">
						<input type="checkbox" bind:checked={trustDevice} />
						Trust this browser for 30 days
					</label>
				</div>
			{/if}
			<button class="btn-primary full-width" type="submit">
				{needsTOTP ? 'Verify' : 'Sign In'}
			</button>
		</form>

		{#if needsTOTP}
			<button class="btn-ghost full-width" style="margin-top: 0.5rem" onclick={() => { needsTOTP = false; totpCode = ''; error = ''; }}>
				Back
			</button>
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
		margin-bottom: 0.25rem;
	}
	.subtitle {
		color: var(--text-muted);
		margin-bottom: 1.5rem;
		font-size: 0.9rem;
	}
	.full-width {
		width: 100%;
		padding: 0.65rem;
	}
	.hint {
		color: var(--text-muted);
		font-size: 0.8rem;
		margin-top: 0.35rem;
	}
</style>
