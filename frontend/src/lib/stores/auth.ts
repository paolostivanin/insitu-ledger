import { writable } from 'svelte/store';
import { isAuthenticated } from '$lib/api/client';

export const DEFAULT_CURRENCY_SYMBOL = '€';

export const authenticated = writable(false);
export const userName = writable('');
export const userId = writable(0);
export const isAdmin = writable(false);
export const forcePasswordChange = writable(false);
export const forceTotpSetup = writable(false);
export const currencySymbol = writable(DEFAULT_CURRENCY_SYMBOL);

export function initAuth() {
	authenticated.set(isAuthenticated());
	if (typeof window !== 'undefined') {
		const name = localStorage.getItem('user_name');
		const id = localStorage.getItem('user_id');
		const admin = localStorage.getItem('is_admin');
		const forcePC = localStorage.getItem('force_password_change');
		const forceTotp = localStorage.getItem('force_totp_setup');
		const sym = localStorage.getItem('currency_symbol');
		if (name) userName.set(name);
		if (id) userId.set(parseInt(id, 10));
		isAdmin.set(admin === 'true');
		forcePasswordChange.set(forcePC === 'true');
		forceTotpSetup.set(forceTotp === 'true');
		currencySymbol.set(sym ?? DEFAULT_CURRENCY_SYMBOL);
	}
}

export function setAuthUser(
	name: string,
	id: number,
	admin: boolean,
	forcePC: boolean,
	totpEnabled: boolean,
	symbol?: string
) {
	userName.set(name);
	userId.set(id);
	isAdmin.set(admin);
	forcePasswordChange.set(forcePC);
	forceTotpSetup.set(!totpEnabled);
	authenticated.set(true);
	const sym = symbol ?? DEFAULT_CURRENCY_SYMBOL;
	currencySymbol.set(sym);
	localStorage.setItem('user_name', name);
	localStorage.setItem('user_id', id.toString());
	localStorage.setItem('is_admin', admin.toString());
	localStorage.setItem('force_password_change', forcePC.toString());
	localStorage.setItem('force_totp_setup', (!totpEnabled).toString());
	localStorage.setItem('currency_symbol', sym);
}

export function setCurrencySymbol(symbol: string) {
	currencySymbol.set(symbol);
	if (typeof window !== 'undefined') {
		localStorage.setItem('currency_symbol', symbol);
	}
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
	currencySymbol.set(DEFAULT_CURRENCY_SYMBOL);
	localStorage.removeItem('user_name');
	localStorage.removeItem('user_id');
	localStorage.removeItem('is_admin');
	localStorage.removeItem('force_password_change');
	localStorage.removeItem('force_totp_setup');
	localStorage.removeItem('currency_symbol');
}
