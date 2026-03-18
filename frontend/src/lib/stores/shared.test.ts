import { describe, it, expect } from 'vitest';
import { get } from 'svelte/store';
import { sharedOwnerUserId, sharedOwnerPermission, setSharedOwner, clearSharedOwner } from './shared';

describe('shared store', () => {
	it('initializes with null owner', () => {
		expect(get(sharedOwnerUserId)).toBeNull();
		expect(get(sharedOwnerPermission)).toBe('write');
	});

	it('sets shared owner with permission', () => {
		setSharedOwner('42', 'read');
		expect(get(sharedOwnerUserId)).toBe('42');
		expect(get(sharedOwnerPermission)).toBe('read');
	});

	it('sets shared owner with default write permission', () => {
		setSharedOwner('10');
		expect(get(sharedOwnerUserId)).toBe('10');
		expect(get(sharedOwnerPermission)).toBe('write');
	});

	it('clears shared owner', () => {
		setSharedOwner('5', 'read');
		clearSharedOwner();
		expect(get(sharedOwnerUserId)).toBeNull();
		expect(get(sharedOwnerPermission)).toBe('write');
	});
});
