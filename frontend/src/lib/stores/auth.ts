import { writable } from 'svelte/store';
import { isAuthenticated } from '$lib/api/client';

export const authenticated = writable(false);
export const userName = writable('');
export const userId = writable(0);
export const isAdmin = writable(false);
export const forcePasswordChange = writable(false);
export const forceTotpSetup = writable(false);

export function initAuth() {
	authenticated.set(isAuthenticated());
	if (typeof window !== 'undefined') {
		const name = localStorage.getItem('user_name');
		const id = localStorage.getItem('user_id');
		const admin = localStorage.getItem('is_admin');
		const forcePC = localStorage.getItem('force_password_change');
		const forceTotp = localStorage.getItem('force_totp_setup');
		if (name) userName.set(name);
		if (id) userId.set(parseInt(id, 10));
		isAdmin.set(admin === 'true');
		forcePasswordChange.set(forcePC === 'true');
		forceTotpSetup.set(forceTotp === 'true');
	}
}

export function setAuthUser(name: string, id: number, admin: boolean, forcePC: boolean, totpEnabled: boolean) {
	userName.set(name);
	userId.set(id);
	isAdmin.set(admin);
	forcePasswordChange.set(forcePC);
	forceTotpSetup.set(!totpEnabled);
	authenticated.set(true);
	localStorage.setItem('user_name', name);
	localStorage.setItem('user_id', id.toString());
	localStorage.setItem('is_admin', admin.toString());
	localStorage.setItem('force_password_change', forcePC.toString());
	localStorage.setItem('force_totp_setup', (!totpEnabled).toString());
}

export function clearForcePasswordChange() {
	forcePasswordChange.set(false);
	localStorage.setItem('force_password_change', 'false');
}

export function clearForceTotpSetup() {
	forceTotpSetup.set(false);
	localStorage.setItem('force_totp_setup', 'false');
}

export function clearAuth() {
	authenticated.set(false);
	userName.set('');
	userId.set(0);
	isAdmin.set(false);
	forcePasswordChange.set(false);
	forceTotpSetup.set(false);
	localStorage.removeItem('user_name');
	localStorage.removeItem('user_id');
	localStorage.removeItem('is_admin');
	localStorage.removeItem('force_password_change');
	localStorage.removeItem('force_totp_setup');
}
