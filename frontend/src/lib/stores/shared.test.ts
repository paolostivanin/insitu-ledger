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
		it("returns 'write' when no filter is set (aggregate view)", async () => {
			const { accountPermission } = await loadStore();
			expect(accountPermission(123)).toBe('write');
		});

		it("returns 'write' for accounts in the filtered owner's grants, null otherwise", async () => {
			const { accountPermission, accessibleOwners, setSharedOwner } = await loadStore();
			accessibleOwners.set([
				{
					owner_user_id: 7,
					name: 'Alice',
					email: 'a@x',
					accounts: [
						{ account_id: 100, account_name: 'Wallet', permission: 'write' },
						{ account_id: 101, account_name: 'Joint', permission: 'write' }
					]
				}
			]);
			setSharedOwner('7');
			expect(accountPermission(100)).toBe('write');
			expect(accountPermission(101)).toBe('write');
			expect(accountPermission(999)).toBeNull(); // not in this owner's grants
		});
	});

	describe('hasAnyWriteInCurrentContext', () => {
		it('is always true since v1.15.0 (every share is co-owner write)', async () => {
			const { hasAnyWriteInCurrentContext, setSharedOwner } = await loadStore();
			expect(get(hasAnyWriteInCurrentContext)).toBe(true);
			setSharedOwner('7');
			expect(get(hasAnyWriteInCurrentContext)).toBe(true);
		});
	});

	describe('accountIsOwn', () => {
		it('returns true when the account owner matches the current user', async () => {
			const { accountIsOwn } = await loadStore();
			const acct = {
				id: 1, user_id: 5, owner_user_id: 5, owner_name: 'Me',
				name: 'Wallet', currency: 'EUR', balance: 0,
				created_at: '', updated_at: '', sync_version: 1, is_shared: false
			};
			expect(accountIsOwn(acct, 5)).toBe(true);
		});

		it('returns false when viewing a co-owned account', async () => {
			const { accountIsOwn } = await loadStore();
			const acct = {
				id: 1, user_id: 99, owner_user_id: 99, owner_name: 'Alice',
				name: 'Joint', currency: 'EUR', balance: 0,
				created_at: '', updated_at: '', sync_version: 1, is_shared: true
			};
			expect(accountIsOwn(acct, 5)).toBe(false);
		});

		it('returns false when current user id is unknown', async () => {
			const { accountIsOwn } = await loadStore();
			const acct = {
				id: 1, user_id: 5, owner_user_id: 5, owner_name: 'Me',
				name: 'Wallet', currency: 'EUR', balance: 0,
				created_at: '', updated_at: '', sync_version: 1, is_shared: false
			};
			expect(accountIsOwn(acct, null)).toBe(false);
		});
	});
});
