import { writable } from 'svelte/store';

export const sharedOwnerUserId = writable<string | null>(null);
export const sharedOwnerPermission = writable<string>('write');

export function setSharedOwner(ownerId: string | null, permission: string = 'write') {
	sharedOwnerUserId.set(ownerId);
	sharedOwnerPermission.set(permission);
}

export function clearSharedOwner() {
	sharedOwnerUserId.set(null);
	sharedOwnerPermission.set('write');
}
