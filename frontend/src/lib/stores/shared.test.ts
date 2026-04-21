import { describe, it, expect, beforeEach, vi } from 'vitest';
import { get } from 'svelte/store';

describe('shared store', () => {
	beforeEach(() => {
		localStorage.clear();
		vi.resetModules();
	});

	async function loadStore() {
		// Re-import to pick up fresh localStorage state
		const mod = await import('./shared');
		return mod;
	}

	it('initializes with null owner when localStorage is empty', async () => {
		const { sharedOwnerUserId } = await loadStore();
		expect(get(sharedOwnerUserId)).toBeNull();
	});

	it('sets shared owner and persists to localStorage', async () => {
		const { sharedOwnerUserId, setSharedOwner } = await loadStore();
		setSharedOwner('42');
		expect(get(sharedOwnerUserId)).toBe('42');
		expect(localStorage.getItem('sharedOwnerUserId')).toBe('42');
	});

	it('clears shared owner and removes from localStorage', async () => {
		const { sharedOwnerUserId, setSharedOwner, clearSharedOwner } = await loadStore();
		setSharedOwner('5');
		clearSharedOwner();
		expect(get(sharedOwnerUserId)).toBeNull();
		expect(localStorage.getItem('sharedOwnerUserId')).toBeNull();
	});

	it('drops the legacy sharedOwnerPermission key on set', async () => {
		localStorage.setItem('sharedOwnerPermission', 'write');
		const { setSharedOwner } = await loadStore();
		setSharedOwner('7');
		expect(localStorage.getItem('sharedOwnerPermission')).toBeNull();
	});

	describe('accountPermission', () => {
		it("returns 'write' when viewing My Data (no owner selected)", async () => {
			const { accountPermission } = await loadStore();
			expect(accountPermission(123)).toBe('write');
		});

		it('returns the per-account grant for the selected owner', async () => {
			const { accountPermission, accessibleOwners, setSharedOwner } = await loadStore();
			accessibleOwners.set([
				{
					owner_user_id: 7,
					name: 'Alice',
					email: 'a@x',
					accounts: [
						{ account_id: 100, account_name: 'Wallet', permission: 'read' },
						{ account_id: 101, account_name: 'Joint', permission: 'write' }
					]
				}
			]);
			setSharedOwner('7');
			expect(accountPermission(100)).toBe('read');
			expect(accountPermission(101)).toBe('write');
			expect(accountPermission(999)).toBeNull(); // not shared
		});
	});

	describe('hasAnyWriteInCurrentContext', () => {
		it('is true when viewing My Data', async () => {
			const { hasAnyWriteInCurrentContext } = await loadStore();
			expect(get(hasAnyWriteInCurrentContext)).toBe(true);
		});

		it('reflects whether any of the selected owner accounts is writable', async () => {
			const { hasAnyWriteInCurrentContext, accessibleOwners, setSharedOwner } = await loadStore();
			accessibleOwners.set([
				{
					owner_user_id: 7,
					name: 'Alice',
					email: 'a@x',
					accounts: [{ account_id: 100, account_name: 'Wallet', permission: 'read' }]
				},
				{
					owner_user_id: 8,
					name: 'Bob',
					email: 'b@x',
					accounts: [{ account_id: 200, account_name: 'Cash', permission: 'write' }]
				}
			]);
			setSharedOwner('7');
			expect(get(hasAnyWriteInCurrentContext)).toBe(false);
			setSharedOwner('8');
			expect(get(hasAnyWriteInCurrentContext)).toBe(true);
		});
	});
});
