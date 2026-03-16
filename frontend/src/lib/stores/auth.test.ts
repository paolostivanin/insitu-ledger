import { describe, it, expect, beforeEach } from 'vitest';
import { get } from 'svelte/store';
import { authenticated, userName, isAdmin, initAuth, setAuthUser, clearAuth } from './auth';

describe('auth store', () => {
	beforeEach(() => {
		localStorage.clear();
		clearAuth();
	});

	it('initializes as unauthenticated', () => {
		initAuth();
		expect(get(authenticated)).toBe(false);
		expect(get(userName)).toBe('');
	});

	it('sets auth user', () => {
		localStorage.setItem('token', 'fake-token');
		setAuthUser('John', 1, true, false, true);
		expect(get(authenticated)).toBe(true);
		expect(get(userName)).toBe('John');
		expect(get(isAdmin)).toBe(true);
	});

	it('persists to localStorage', () => {
		setAuthUser('Jane', 2, false, false, true);
		expect(localStorage.getItem('user_name')).toBe('Jane');
		expect(localStorage.getItem('user_id')).toBe('2');
		expect(localStorage.getItem('is_admin')).toBe('false');
	});

	it('clears auth', () => {
		setAuthUser('Jane', 2, false, false, true);
		clearAuth();
		expect(get(authenticated)).toBe(false);
		expect(get(userName)).toBe('');
		expect(localStorage.getItem('user_name')).toBeNull();
	});

	it('restores from localStorage on init', () => {
		localStorage.setItem('token', 'test-token');
		localStorage.setItem('user_name', 'Restored');
		localStorage.setItem('user_id', '5');
		localStorage.setItem('is_admin', 'true');
		localStorage.setItem('force_password_change', 'false');
		localStorage.setItem('force_totp_setup', 'false');
		initAuth();
		expect(get(authenticated)).toBe(true);
		expect(get(userName)).toBe('Restored');
		expect(get(isAdmin)).toBe(true);
	});
});
