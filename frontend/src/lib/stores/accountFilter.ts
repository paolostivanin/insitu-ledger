import { writable, get } from 'svelte/store';
import { sharedOwnerUserId } from './shared';

// currentAccountId narrows dashboard + transactions + scheduled to a single
// account in the active owner's space, or shows all accounts when null.
export const currentAccountId = writable<number | null>(null);

// Persistence key is namespaced by owner so switching owners doesn't carry the
// previous owner's filter over (different account IDs, different visibility).
function storageKey(ownerId: string | null): string {
	return ownerId ? `accountFilter:owner:${ownerId}` : 'accountFilter:self';
}

// Initialize from localStorage on first load (matches the persisted owner).
if (typeof localStorage !== 'undefined') {
	const ownerId = get(sharedOwnerUserId);
	const raw = localStorage.getItem(storageKey(ownerId));
	if (raw) {
		const id = parseInt(raw, 10);
		if (!isNaN(id)) currentAccountId.set(id);
	}
}

// Persist on every change against the *current* owner key.
currentAccountId.subscribe(val => {
	if (typeof localStorage === 'undefined') return;
	const ownerId = get(sharedOwnerUserId);
	const key = storageKey(ownerId);
	if (val === null) localStorage.removeItem(key);
	else localStorage.setItem(key, val.toString());
});

// When the owner changes, reload the filter for the new owner's namespace.
sharedOwnerUserId.subscribe(ownerId => {
	if (typeof localStorage === 'undefined') return;
	const raw = localStorage.getItem(storageKey(ownerId));
	if (raw) {
		const id = parseInt(raw, 10);
		currentAccountId.set(isNaN(id) ? null : id);
	} else {
		currentAccountId.set(null);
	}
});

export function setAccountFilter(id: number | null) {
	currentAccountId.set(id);
}

export function clearAccountFilter() {
	currentAccountId.set(null);
}
