import { writable } from 'svelte/store';

const storedOwnerId = typeof localStorage !== 'undefined' ? localStorage.getItem('sharedOwnerUserId') : null;
const storedPermission = typeof localStorage !== 'undefined' ? localStorage.getItem('sharedOwnerPermission') || 'write' : 'write';

export const sharedOwnerUserId = writable<string | null>(storedOwnerId);
export const sharedOwnerPermission = writable<string>(storedOwnerId ? storedPermission : 'write');

export function setSharedOwner(ownerId: string | null, permission: string = 'write') {
	sharedOwnerUserId.set(ownerId);
	sharedOwnerPermission.set(permission);
	if (typeof localStorage !== 'undefined') {
		if (ownerId) {
			localStorage.setItem('sharedOwnerUserId', ownerId);
			localStorage.setItem('sharedOwnerPermission', permission);
		} else {
			localStorage.removeItem('sharedOwnerUserId');
			localStorage.removeItem('sharedOwnerPermission');
		}
	}
}

export function clearSharedOwner() {
	sharedOwnerUserId.set(null);
	sharedOwnerPermission.set('write');
	if (typeof localStorage !== 'undefined') {
		localStorage.removeItem('sharedOwnerUserId');
		localStorage.removeItem('sharedOwnerPermission');
	}
}
