<script lang="ts">
	import '../app.css';
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { page } from '$app/stores';
	import { authenticated, userName, isAdmin, forcePasswordChange, forceTotpSetup, initAuth, clearAuth } from '$lib/stores/auth';
	import { auth as authApi, shared as sharedApi, clearToken, type AccessibleOwner } from '$lib/api/client';
	import { initTheme } from '$lib/stores/theme';
	import { sharedOwnerUserId, setSharedOwner, clearSharedOwner } from '$lib/stores/shared';
	import ThemeToggle from '$lib/components/ThemeToggle.svelte';
	import ToastContainer from '$lib/components/ToastContainer.svelte';

	let { children } = $props();
	let showShortcutsHelp = $state(false);
	let isOffline = $state(false);
	let mobileMenuOpen = $state(false);
	let accessibleOwners = $state<AccessibleOwner[]>([]);

	onMount(() => {
		initTheme();
		initAuth();
		if ($authenticated) {
			if ($forcePasswordChange) {
				goto('/change-password');
			} else if ($forceTotpSetup) {
				goto('/setup-2fa');
			}
			loadAccessibleOwners();
		}

		const goOnline = () => { isOffline = false; };
		const goOffline = () => { isOffline = true; };
		window.addEventListener('online', goOnline);
		window.addEventListener('offline', goOffline);
		isOffline = !navigator.onLine;

		return () => {
			window.removeEventListener('online', goOnline);
			window.removeEventListener('offline', goOffline);
		};
	});

	async function loadAccessibleOwners() {
		try {
			accessibleOwners = await sharedApi.accessible();
		} catch {
			accessibleOwners = [];
		}
	}

	function switchOwner(e: Event) {
		const select = e.target as HTMLSelectElement;
		const val = select.value;
		if (val === '') {
			clearSharedOwner();
		} else {
			const owner = accessibleOwners.find(o => o.owner_user_id.toString() === val);
			setSharedOwner(val, owner?.permission || 'read');
		}
	}

	const navItems = [
		{ href: '/', label: 'Dashboard' },
		{ href: '/transactions', label: 'Transactions' },
		{ href: '/categories', label: 'Categories' },
		{ href: '/accounts', label: 'Accounts' },
		{ href: '/scheduled', label: 'Scheduled' },
		{ href: '/reports', label: 'Reports' },
		{ href: '/settings', label: 'Settings' }
	];

	const listPages = ['/transactions', '/accounts', '/categories', '/scheduled'];

	function handleKeydown(e: KeyboardEvent) {
		const tag = (e.target as HTMLElement)?.tagName;
		if (tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA') return;

		if (e.key === '?' && !e.ctrlKey && !e.metaKey) {
			e.preventDefault();
			showShortcutsHelp = !showShortcutsHelp;
			return;
		}

		if (e.key === 'Escape') {
			if (showShortcutsHelp) {
				showShortcutsHelp = false;
				return;
			}
			if (mobileMenuOpen) {
				mobileMenuOpen = false;
				return;
			}
			window.dispatchEvent(new CustomEvent('shortcut-close'));
			return;
		}

		if (e.key === 'n' && !e.ctrlKey && !e.metaKey) {
			const path = $page.url.pathname;
			if (listPages.includes(path)) {
				e.preventDefault();
				window.dispatchEvent(new CustomEvent('shortcut-new'));
			}
		}
	}

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

<svelte:window onkeydown={handleKeydown} />

{#if isOffline}
	<div class="offline-banner">You are offline. Some features may be unavailable.</div>
{/if}

{#if $authenticated}
	<nav class="topnav">
		<div class="nav-inner">
			<a href="/" class="brand">InSitu Ledger</a>
			<button class="hamburger" onclick={() => mobileMenuOpen = !mobileMenuOpen} aria-label="Toggle menu">
				<span class="hamburger-line"></span>
				<span class="hamburger-line"></span>
				<span class="hamburger-line"></span>
			</button>
			<div class="nav-links" class:open={mobileMenuOpen}>
				{#each navItems as item}
					<a href={item.href} class:active={$page.url.pathname === item.href} onclick={() => mobileMenuOpen = false}>{item.label}</a>
				{/each}
				{#if $isAdmin}
					<a href="/admin" class:active={$page.url.pathname.startsWith('/admin')} onclick={() => mobileMenuOpen = false}>Admin</a>
				{/if}
			</div>
			<div class="nav-right">
				{#if accessibleOwners.length > 0}
					<select class="owner-switcher" value={$sharedOwnerUserId || ''} onchange={switchOwner}>
						<option value="">My Data</option>
						{#each accessibleOwners as owner}
							<option value={owner.owner_user_id.toString()}>{owner.name} ({owner.permission})</option>
						{/each}
					</select>
				{/if}
				<ThemeToggle />
				<span class="user-name">{$userName}</span>
				<button class="btn-ghost" onclick={logout}>Logout</button>
			</div>
		</div>
	</nav>
{/if}

<main>
	{@render children()}
</main>

<ToastContainer />

{#if showShortcutsHelp}
	<div class="overlay" onclick={() => showShortcutsHelp = false} onkeydown={(e) => e.key === 'Escape' && (showShortcutsHelp = false)} role="dialog" aria-modal="true" tabindex="-1">
		<div class="shortcuts-modal card" onclick={(e) => e.stopPropagation()} onkeydown={() => {}} role="document" tabindex="-1">
			<h2>Keyboard Shortcuts</h2>
			<table class="shortcuts-table">
				<tbody>
					<tr><td class="key"><kbd>n</kbd></td><td>New item (on list pages)</td></tr>
					<tr><td class="key"><kbd>Escape</kbd></td><td>Close form / dialog</td></tr>
					<tr><td class="key"><kbd>?</kbd></td><td>Show / hide this help</td></tr>
				</tbody>
			</table>
			<button class="btn-ghost" onclick={() => showShortcutsHelp = false} style="margin-top: 1rem">Close</button>
		</div>
	</div>
{/if}

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
	.hamburger {
		display: none;
		flex-direction: column;
		gap: 4px;
		background: none;
		border: none;
		padding: 0.4rem;
		cursor: pointer;
	}
	.hamburger-line {
		display: block;
		width: 20px;
		height: 2px;
		background: var(--text-muted);
		border-radius: 1px;
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
	.owner-switcher {
		background: var(--bg-hover);
		color: var(--text);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		padding: 0.25rem 0.5rem;
		font-size: 0.8rem;
		cursor: pointer;
	}
	.user-name {
		color: var(--text-muted);
		font-size: 0.85rem;
	}
	main {
		min-height: calc(100vh - 56px);
	}
	.offline-banner {
		background: var(--warning);
		color: #000;
		text-align: center;
		padding: 0.4rem;
		font-size: 0.85rem;
		font-weight: 500;
	}
	.overlay {
		position: fixed;
		inset: 0;
		background: rgba(0, 0, 0, 0.6);
		display: flex;
		align-items: center;
		justify-content: center;
		z-index: 200;
	}
	.shortcuts-modal {
		max-width: 400px;
		width: 90%;
	}
	.shortcuts-modal h2 {
		margin-bottom: 1rem;
		font-size: 1.1rem;
	}
	.shortcuts-table {
		width: 100%;
	}
	.shortcuts-table td {
		padding: 0.4rem 0.5rem;
		border-bottom: 1px solid var(--border);
	}
	.shortcuts-table .key {
		width: 80px;
	}
	kbd {
		background: var(--bg-hover);
		border: 1px solid var(--border);
		border-radius: 4px;
		padding: 0.15rem 0.5rem;
		font-family: inherit;
		font-size: 0.8rem;
	}

	@media (max-width: 768px) {
		.hamburger {
			display: flex;
		}
		.nav-links {
			display: none;
			position: absolute;
			top: 56px;
			left: 0;
			right: 0;
			background: var(--bg-card);
			border-bottom: 1px solid var(--border);
			flex-direction: column;
			padding: 0.5rem;
			gap: 0;
		}
		.nav-links.open {
			display: flex;
		}
		.nav-links a {
			padding: 0.75rem 1rem;
		}
		.user-name {
			display: none;
		}
	}
</style>
