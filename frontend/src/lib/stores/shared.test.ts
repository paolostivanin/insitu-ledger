import { describe, it, expect, beforeEach } from 'vitest';
import { get } from 'svelte/store';

describe('shared store', () => {
	beforeEach(() => {
		localStorage.clear();
	});

	async function loadStore() {
		// Re-import to pick up fresh localStorage state
		const mod = await import('./shared');
		return mod;
	}

	it('initializes with null owner when localStorage is empty', async () => {
		const { sharedOwnerUserId, sharedOwnerPermission } = await loadStore();
		expect(get(sharedOwnerUserId)).toBeNull();
		expect(get(sharedOwnerPermission)).toBe('write');
	});

	it('sets shared owner with permission and persists to localStorage', async () => {
		const { sharedOwnerUserId, sharedOwnerPermission, setSharedOwner } = await loadStore();
		setSharedOwner('42', 'read');
		expect(get(sharedOwnerUserId)).toBe('42');
		expect(get(sharedOwnerPermission)).toBe('read');
		expect(localStorage.getItem('sharedOwnerUserId')).toBe('42');
		expect(localStorage.getItem('sharedOwnerPermission')).toBe('read');
	});

	it('sets shared owner with default write permission', async () => {
		const { sharedOwnerUserId, sharedOwnerPermission, setSharedOwner } = await loadStore();
		setSharedOwner('10');
		expect(get(sharedOwnerUserId)).toBe('10');
		expect(get(sharedOwnerPermission)).toBe('write');
		expect(localStorage.getItem('sharedOwnerPermission')).toBe('write');
	});

	it('clears shared owner and removes from localStorage', async () => {
		const { sharedOwnerUserId, sharedOwnerPermission, setSharedOwner, clearSharedOwner } = await loadStore();
		setSharedOwner('5', 'read');
		clearSharedOwner();
		expect(get(sharedOwnerUserId)).toBeNull();
		expect(get(sharedOwnerPermission)).toBe('write');
		expect(localStorage.getItem('sharedOwnerUserId')).toBeNull();
		expect(localStorage.getItem('sharedOwnerPermission')).toBeNull();
	});
});
