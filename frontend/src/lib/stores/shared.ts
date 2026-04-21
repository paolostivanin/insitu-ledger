import { writable, derived, get } from 'svelte/store';
import type { AccessibleOwner } from '$lib/api/client';

const storedOwnerId = typeof localStorage !== 'undefined' ? localStorage.getItem('sharedOwnerUserId') : null;

export const sharedOwnerUserId = writable<string | null>(storedOwnerId);

// Owners (and per-account grants) that the current user can switch into.
// Populated by +layout.svelte after login.
export const accessibleOwners = writable<AccessibleOwner[]>([]);

// 'write' when viewing My Data (no owner switch), or the per-account permission
// granted by the currently-selected owner. Returns null if the account is not
// accessible in the current context.
export function accountPermission(accountId: number): 'read' | 'write' | null {
	const ownerId = get(sharedOwnerUserId);
	if (!ownerId) return 'write';
	const owner = get(accessibleOwners).find(o => o.owner_user_id.toString() === ownerId);
	const grant = owner?.accounts.find(a => a.account_id === accountId);
	return (grant?.permission as 'read' | 'write') ?? null;
}

// True when there exists at least one write-grant in the current owner context.
// Used to decide whether to render "create" affordances at all on list pages.
export const hasAnyWriteInCurrentContext = derived(
	[sharedOwnerUserId, accessibleOwners],
	([$ownerId, $owners]) => {
		if (!$ownerId) return true;
		const owner = $owners.find(o => o.owner_user_id.toString() === $ownerId);
		return !!owner?.accounts.some(a => a.permission === 'write');
	}
);

export function setSharedOwner(ownerId: string | null) {
	sharedOwnerUserId.set(ownerId);
	if (typeof localStorage !== 'undefined') {
		if (ownerId) {
			localStorage.setItem('sharedOwnerUserId', ownerId);
		} else {
			localStorage.removeItem('sharedOwnerUserId');
		}
		// Stale legacy key from the global-share era.
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
