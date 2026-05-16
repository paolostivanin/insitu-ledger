import { describe, it, expect, beforeEach, vi } from 'vitest';
import { get } from 'svelte/store';

describe('accountFilter store', () => {
	beforeEach(() => {
		localStorage.clear();
		vi.resetModules();
	});

	it('initializes to null when no filter is persisted', async () => {
		const { currentAccountId } = await import('./accountFilter');
		expect(get(currentAccountId)).toBeNull();
	});

	it('persists per-owner filter selection', async () => {
		// Pre-set the persisted owner BEFORE importing so the shared store init
		// picks it up and the accountFilter subscribers see owner='7' from the start.
		localStorage.setItem('sharedOwnerUserId', '7');
		const sharedMod = await import('./shared');
		const filterMod = await import('./accountFilter');

		filterMod.setAccountFilter(42);
		expect(get(filterMod.currentAccountId)).toBe(42);
		expect(localStorage.getItem('accountFilter:owner:7')).toBe('42');

		// Switching owner swaps the namespace; new owner has no saved filter.
		sharedMod.setSharedOwner('99');
		expect(get(filterMod.currentAccountId)).toBeNull();

		filterMod.setAccountFilter(11);
		expect(localStorage.getItem('accountFilter:owner:99')).toBe('11');

		// Switch back: previous owner's filter is restored.
		sharedMod.setSharedOwner('7');
		expect(get(filterMod.currentAccountId)).toBe(42);
	});

	it('clearAccountFilter removes the persisted entry', async () => {
		const { currentAccountId, setAccountFilter, clearAccountFilter } = await import('./accountFilter');
		setAccountFilter(5);
		expect(localStorage.getItem('accountFilter:self')).toBe('5');
		clearAccountFilter();
		expect(get(currentAccountId)).toBeNull();
		expect(localStorage.getItem('accountFilter:self')).toBeNull();
	});

	it('clearAllAccountFilters drops every per-owner key (logout hygiene)', async () => {
		// Seed several owner-scoped filters left behind by a prior session.
		localStorage.setItem('accountFilter:self', '1');
		localStorage.setItem('accountFilter:owner:5', '2');
		localStorage.setItem('accountFilter:owner:7', '3');
		// Unrelated keys must be left alone — only accountFilter:* is namespaced.
		localStorage.setItem('theme', 'dark');

		const { currentAccountId, clearAllAccountFilters } = await import('./accountFilter');
		clearAllAccountFilters();

		expect(get(currentAccountId)).toBeNull();
		expect(localStorage.getItem('accountFilter:self')).toBeNull();
		expect(localStorage.getItem('accountFilter:owner:5')).toBeNull();
		expect(localStorage.getItem('accountFilter:owner:7')).toBeNull();
		expect(localStorage.getItem('theme')).toBe('dark');
	});
});
