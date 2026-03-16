<script lang="ts">
	import '../app.css';
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { page } from '$app/stores';
	import { authenticated, userName, isAdmin, forcePasswordChange, forceTotpSetup, initAuth, clearAuth } from '$lib/stores/auth';
	import { auth as authApi, clearToken } from '$lib/api/client';

	let { children } = $props();

	onMount(() => {
		initAuth();
		if ($authenticated) {
			if ($forcePasswordChange) {
				goto('/change-password');
			} else if ($forceTotpSetup) {
				goto('/setup-2fa');
			}
		}
	});

	const navItems = [
		{ href: '/', label: 'Dashboard' },
		{ href: '/transactions', label: 'Transactions' },
		{ href: '/categories', label: 'Categories' },
		{ href: '/accounts', label: 'Accounts' },
		{ href: '/scheduled', label: 'Scheduled' },
		{ href: '/reports', label: 'Reports' },
		{ href: '/settings', label: 'Settings' }
	];

	async function logout() {
		try {
			await authApi.logout();
		} catch {
			// ignore
		}
		clearToken();
		clearAuth();
		goto('/login');
	}
</script>

{#if $authenticated}
	<nav class="topnav">
		<div class="nav-inner">
			<a href="/" class="brand">InSitu Ledger</a>
			<div class="nav-links">
				{#each navItems as item}
					<a href={item.href} class:active={$page.url.pathname === item.href}>{item.label}</a>
				{/each}
				{#if $isAdmin}
					<a href="/admin" class:active={$page.url.pathname === '/admin'}>Admin</a>
				{/if}
			</div>
			<div class="nav-right">
				<span class="user-name">{$userName}</span>
				<button class="btn-ghost" onclick={logout}>Logout</button>
			</div>
		</div>
	</nav>
{/if}

<main>
	{@render children()}
</main>

<style>
	.topnav {
		background: var(--bg-card);
		border-bottom: 1px solid var(--border);
		position: sticky;
		top: 0;
		z-index: 100;
	}
	.nav-inner {
		max-width: 1600px;
		margin: 0 auto;
		display: flex;
		align-items: center;
		padding: 0 1.5rem;
		height: 56px;
		gap: 2rem;
	}
	.brand {
		font-weight: 700;
		font-size: 1.1rem;
		color: var(--primary);
		white-space: nowrap;
	}
	.nav-links {
		display: flex;
		gap: 0.25rem;
		overflow-x: auto;
	}
	.nav-links a {
		color: var(--text-muted);
		padding: 0.4rem 0.75rem;
		border-radius: var(--radius);
		font-size: 0.875rem;
		white-space: nowrap;
		transition: background 0.15s, color 0.15s;
	}
	.nav-links a:hover {
		background: var(--bg-hover);
		color: var(--text);
	}
	.nav-links a.active {
		background: var(--primary);
		color: white;
	}
	.nav-right {
		margin-left: auto;
		display: flex;
		align-items: center;
		gap: 0.75rem;
		white-space: nowrap;
	}
	.user-name {
		color: var(--text-muted);
		font-size: 0.85rem;
	}
	main {
		min-height: calc(100vh - 56px);
	}
</style>
