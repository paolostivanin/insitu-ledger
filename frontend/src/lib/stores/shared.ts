import { writable, derived, get } from 'svelte/store';
import type { AccessibleOwner, Account } from '$lib/api/client';

const storedOwnerId = typeof localStorage !== 'undefined' ? localStorage.getItem('sharedOwnerUserId') : null;

// Optional "filter by owner" — null means "show everything I can access".
// Since v1.15.0 this is purely a UI filter, not an auth scope: the backend
// derives access from each account's owner via the per-account share table.
// Kept under the legacy name `sharedOwnerUserId` to limit churn for callers.
export const sharedOwnerUserId = writable<string | null>(storedOwnerId);

// Owners (and per-account grants) that the current user can switch into.
// Populated by +layout.svelte after login. Drives the filter dropdown.
export const accessibleOwners = writable<AccessibleOwner[]>([]);

// Returns 'write' if the auth user can mutate the account (own or shared),
// or null if they have no access at all. Since v1.15.0 there is no read-only
// tier — kept as a permission-shaped function so existing call sites can be
// updated incrementally.
export function accountPermission(accountId: number): 'write' | null {
	// Own-data context (no filter) — assume access; the API enforces.
	const ownerId = get(sharedOwnerUserId);
	if (!ownerId) return 'write';
	// Filtered to a specific owner — the account must appear in their grants.
	const owner = get(accessibleOwners).find(o => o.owner_user_id.toString() === ownerId);
	if (!owner) return 'write'; // Filtering to self.
	const grant = owner.accounts.find(a => a.account_id === accountId);
	return grant ? 'write' : null;
}

// Always true since v1.15.0 — every share is co-owner. Kept as a derived
// store so existing reactive call sites keep working without code changes.
export const hasAnyWriteInCurrentContext = derived(sharedOwnerUserId, () => true);

// Owner-only gate: returns true iff the auth user owns the account. Used to
// hide rename/delete/share affordances for accounts the user merely co-owns.
export function accountIsOwn(account: Account, currentUserId: number | null): boolean {
	if (currentUserId === null) return false;
	return account.owner_user_id === currentUserId;
}

export function setSharedOwner(ownerId: string | null) {
	sharedOwnerUserId.set(ownerId);
	if (typeof localStorage !== 'undefined') {
		if (ownerId) {
			localStorage.setItem('sharedOwnerUserId', ownerId);
		} else {
			localStorage.removeItem('sharedOwnerUserId');
		}
		// Stale legacy keys.
		localStorage.removeItem('sharedOwnerPermission');
	}
}

export function clearSharedOwner() {
	sharedOwnerUserId.set(null);
	if (typeof localStorage !== 'undefined') {
		localStorage.removeItem('sharedOwnerUserId');
		localStorage.removeItem('sharedOwnerPermission');
	}
}
