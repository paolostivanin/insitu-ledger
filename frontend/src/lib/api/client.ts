const BASE = '/api';

interface RequestOptions {
	method?: string;
	body?: unknown;
	params?: Record<string, string>;
}

class ApiError extends Error {
	constructor(
		public status: number,
		message: string
	) {
		super(message);
	}
}

function getToken(): string | null {
	if (typeof window === 'undefined') return null;
	return localStorage.getItem('token');
}

export function setToken(token: string) {
	localStorage.setItem('token', token);
}

export function clearToken() {
	localStorage.removeItem('token');
}

export function isAuthenticated(): boolean {
	return !!getToken();
}

// Ask the service worker to drop any cached API responses. Used after logout
// or 401 so a different user signing in on the same browser does not see
// stale data from the previous session.
export function clearApiCache() {
	if (typeof navigator === 'undefined') return;
	if (!navigator.serviceWorker?.controller) return;
	navigator.serviceWorker.controller.postMessage({ type: 'clear-api-cache' });
}

// Map an HTTP status + optional server message to a user-friendly string.
// We deliberately do not surface raw 5xx bodies (which may contain stack
// traces or internal paths) to end users.
function friendlyError(status: number, body: string): string {
	const trimmed = body.trim();
	const isShortPlain =
		trimmed.length > 0 && trimmed.length < 200 && !trimmed.startsWith('<') && !trimmed.startsWith('{');

	if (status === 400) return isShortPlain ? trimmed : 'The request was invalid.';
	if (status === 401) return 'Your session has expired. Please log in again.';
	if (status === 403) return "You don't have permission to do that.";
	if (status === 404) return 'Not found.';
	if (status === 409) return isShortPlain ? trimmed : 'That conflicts with existing data.';
	if (status === 413) return 'The file is too large.';
	if (status === 422) return isShortPlain ? trimmed : 'Some fields are invalid.';
	if (status === 429) return 'Too many requests. Please slow down and try again.';
	if (status >= 500) return 'The server ran into a problem. Please try again.';
	return isShortPlain ? trimmed : `Request failed (${status}).`;
}

async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
	const { method = 'GET', body, params } = opts;

	let url = `${BASE}${path}`;
	if (params) {
		const search = new URLSearchParams(
			Object.entries(params).filter(([, v]) => v !== '' && v !== undefined)
		);
		if (search.toString()) url += `?${search}`;
	}

	const headers: Record<string, string> = {};
	const token = getToken();
	if (token) headers['Authorization'] = `Bearer ${token}`;
	if (body) headers['Content-Type'] = 'application/json';

	const res = await fetch(url, {
		method,
		headers,
		body: body ? JSON.stringify(body) : undefined,
		cache: method === 'GET' ? 'no-store' : undefined
	});

	if (res.status === 401 && !path.startsWith('/auth/login')) {
		clearToken();
		clearApiCache();
		if (typeof window !== 'undefined') window.location.href = '/login';
		throw new ApiError(401, 'Your session has expired. Please log in again.');
	}

	if (!res.ok) {
		const text = await res.text();
		throw new ApiError(res.status, friendlyError(res.status, text));
	}

	if (res.status === 204) return undefined as T;
	return res.json();
}

// Auth
export interface LoginResponse {
	token?: string;
	user_id: number;
	name: string;
	is_admin: boolean;
	force_password_change: boolean;
	totp_enabled: boolean;
	totp_required?: boolean;
	currency_symbol?: string;
}

export const auth = {
	login: (login: string, password: string, totp_code?: string, trust_device?: boolean) =>
		request<LoginResponse>('/auth/login', {
			method: 'POST',
			body: { login, password, totp_code, trust_device }
		}),
	logout: () => request<void>('/auth/logout', { method: 'POST' })
};

// Trusted devices — long-lived per-browser cookies that let a user skip the
// 2FA prompt on subsequent logins. Managed from the user settings page.
export interface TrustedDevice {
	id: number;
	label: string;
	created_at: string;
	last_used_at: string;
	expires_at: string;
}

export const trustedDevices = {
	list: () => request<TrustedDevice[]>('/auth/trusted-devices'),
	revoke: (id: number) => request<void>(`/auth/trusted-devices/${id}`, { method: 'DELETE' }),
	revokeAll: () => request<void>('/auth/trusted-devices', { method: 'DELETE' })
};

// Transactions
export interface Transaction {
	id: number;
	account_id: number;
	category_id: number;
	// user_id mirrors the account owner (legacy); use created_by_user_id for
	// actual attribution. Will be dropped in a future major version.
	user_id: number;
	type: 'income' | 'expense';
	amount: number;
	currency: string;
	description: string | null;
	note: string | null;
	date: string;
	created_at: string;
	updated_at: string;
	sync_version: number;
	// Authenticated creator (since v1.15.0). May differ from user_id on shared
	// accounts. Set on create, sticky across edits.
	created_by_user_id: number | null;
	created_by_name: string | null;
}

export interface TransactionInput {
	account_id: number;
	category_id: number;
	type: 'income' | 'expense';
	amount: number;
	currency?: string;
	description?: string;
	note?: string | null;
	date: string;
}

export interface AutocompleteSuggestion {
	description: string;
	category_id: number;
}

export const transactions = {
	// owner_id is an optional list/autocomplete filter (since v1.15.0); mutations
	// derive auth from the path or the authenticated user.
	list: (params?: { from?: string; to?: string; category_id?: string; account_id?: string; limit?: string; offset?: string; sort_by?: string; sort_dir?: string; owner_id?: string }) =>
		request<Transaction[]>('/transactions', { params }),
	create: (data: TransactionInput) =>
		request<{ id: number }>('/transactions', { method: 'POST', body: data }),
	update: (id: number, data: TransactionInput) =>
		request<void>(`/transactions/${id}`, { method: 'PUT', body: data }),
	delete: (id: number) =>
		request<void>(`/transactions/${id}`, { method: 'DELETE' }),
	autocomplete: (q: string, owner_id?: string) =>
		request<AutocompleteSuggestion[]>('/transactions/autocomplete', { params: owner_id ? { q, owner_id } : { q } })
};

// Categories
export interface Category {
	id: number;
	user_id: number;
	parent_id: number | null;
	name: string;
	type: 'income' | 'expense';
	icon: string | null;
	color: string | null;
	created_at: string;
	updated_at: string;
	sync_version: number;
	read_only?: boolean;
}

export interface CategoryInput {
	parent_id?: number | null;
	name: string;
	type: 'income' | 'expense';
	icon?: string;
	color?: string;
}

export const categories = {
	// owner_id is an optional list filter (since v1.15.0); mutations derive
	// auth from the authenticated user.
	list: (owner_id?: string) => request<Category[]>('/categories', { params: owner_id ? { owner_id } : undefined }),
	create: (data: CategoryInput) =>
		request<{ id: number }>('/categories', { method: 'POST', body: data }),
	update: (id: number, data: CategoryInput) =>
		request<void>(`/categories/${id}`, { method: 'PUT', body: data }),
	delete: (id: number) =>
		request<void>(`/categories/${id}`, { method: 'DELETE' })
};

// Accounts
export interface Account {
	id: number;
	user_id: number;
	name: string;
	currency: string;
	balance: number;
	created_at: string;
	updated_at: string;
	sync_version: number;
	// Since v1.15.0: the account owner's id and display name (mirrors user_id;
	// owner_name is denormalized for the "Shared by [name]" UI badge). is_shared
	// is true when at least one other user has co-owner access.
	owner_user_id: number;
	owner_name: string;
	is_shared: boolean;
}

export interface AccountInput {
	name: string;
	currency?: string;
}

export const accounts = {
	// owner_id is an optional list filter (since v1.15.0); mutations derive
	// auth from the path or the authenticated user.
	list: (owner_id?: string) => request<Account[]>('/accounts', { params: owner_id ? { owner_id } : undefined }),
	create: (data: AccountInput) =>
		request<{ id: number }>('/accounts', { method: 'POST', body: data }),
	update: (id: number, data: AccountInput) =>
		request<void>(`/accounts/${id}`, { method: 'PUT', body: data }),
	delete: (id: number) =>
		request<void>(`/accounts/${id}`, { method: 'DELETE' })
};

// Scheduled transactions
export interface ScheduledTransaction {
	id: number;
	account_id: number;
	category_id: number;
	// user_id mirrors the account owner (legacy); use created_by_user_id for
	// actual attribution.
	user_id: number;
	type: 'income' | 'expense';
	amount: number;
	currency: string;
	description: string | null;
	note: string | null;
	rrule: string;
	next_occurrence: string;
	active: boolean;
	max_occurrences: number | null;
	occurrence_count: number;
	created_at: string;
	updated_at: string;
	sync_version: number;
	created_by_user_id: number | null;
	created_by_name: string | null;
}

export interface ScheduledInput {
	account_id: number;
	category_id: number;
	type: 'income' | 'expense';
	amount: number;
	currency?: string;
	description?: string;
	note?: string | null;
	rrule: string;
	next_occurrence: string;
	max_occurrences?: number | null;
	// Omit on create (defaults true server-side); omit on update to keep existing.
	active?: boolean;
}

export const scheduled = {
	// owner_id is an optional list filter (since v1.15.0); mutations derive
	// auth from the path or the authenticated user.
	list: (params?: { owner_id?: string; account_id?: string }) =>
		request<ScheduledTransaction[]>('/scheduled', { params }),
	create: (data: ScheduledInput) =>
		request<{ id: number }>('/scheduled', { method: 'POST', body: data }),
	update: (id: number, data: ScheduledInput) =>
		request<void>(`/scheduled/${id}`, { method: 'PUT', body: data }),
	delete: (id: number) =>
		request<void>(`/scheduled/${id}`, { method: 'DELETE' })
};

// Reports
export interface CategoryReport {
	category_id: number;
	category_name: string;
	category_color: string | null;
	type: string;
	total: number;
}

export interface MonthReport {
	month: string;
	type: string;
	total: number;
}

export interface TrendReport {
	period: string;
	type: string;
	total: number;
}

export const reports = {
	byCategory: (params?: { from?: string; to?: string; type?: string; owner_id?: string; account_id?: string }) =>
		request<CategoryReport[]>('/reports/by-category', { params }),
	byMonth: (params?: { year?: string; owner_id?: string; account_id?: string }) =>
		request<MonthReport[]>('/reports/by-month', { params }),
	trend: (params?: { from?: string; to?: string; group_by?: string; owner_id?: string; account_id?: string }) =>
		request<TrendReport[]>('/reports/trend', { params })
};

// Shared access — one row per (guest, account) pair.
export interface SharedAccess {
	id: number;
	owner_user_id: number;
	guest_user_id: number;
	account_id: number;
	account_name: string;
	permission: string;
	guest_name: string;
	guest_email: string;
}

// AccessibleOwner — an owner who has shared at least one account with the
// auth user, with the per-account grants nested under `accounts`.
export interface AccessibleOwnerAccount {
	account_id: number;
	account_name: string;
	permission: string;
}

export interface AccessibleOwner {
	owner_user_id: number;
	name: string;
	email: string;
	accounts: AccessibleOwnerAccount[];
}

export const shared = {
	list: () => request<SharedAccess[]>('/shared'),
	accessible: () => request<AccessibleOwner[]>('/shared/accessible'),
	// Since v1.15.0 every share is full co-owner write; the permission field
	// is not sent.
	create: (guest_email: string, account_id: number) =>
		request<{ id: number }>('/shared', {
			method: 'POST',
			body: { guest_email, account_id }
		}),
	delete: (id: number) => request<void>(`/shared/${id}`, { method: 'DELETE' })
};

// User profile
export interface UserProfile {
	id: number;
	username: string;
	name: string;
	email: string;
	is_admin: boolean;
	force_password_change: boolean;
	totp_enabled: boolean;
	currency_symbol: string;
}

export const me = {
	get: () => request<UserProfile>('/auth/me'),
	changePassword: (current_password: string, new_password: string) =>
		request<void>('/auth/change-password', { method: 'POST', body: { current_password, new_password } }),
	updateProfile: (data: { username?: string; email?: string; name?: string; currency_symbol?: string }) =>
		request<void>('/auth/profile', { method: 'PUT', body: data }),
	totpSetup: () => request<{ secret: string; qr_code: string; otpauth: string }>('/auth/totp/setup', { method: 'POST' }),
	totpVerify: (code: string) => request<void>('/auth/totp/verify', { method: 'POST', body: { code } }),
	totpReset: (password: string) => request<{ secret: string; qr_code: string; otpauth: string }>('/auth/totp/reset', { method: 'POST', body: { password } })
};

// User preferences (default account, etc.)
export interface Preferences {
	default_account_id: number | null;
}

export const preferences = {
	get: () => request<Preferences>('/profile/preferences'),
	set: (data: Preferences) =>
		request<void>('/profile/preferences', { method: 'PUT', body: data })
};

// Admin
export interface AdminUser {
	id: number;
	username: string;
	email: string;
	name: string;
	is_admin: boolean;
	force_password_change: boolean;
	totp_enabled: boolean;
	created_at: string;
}

// Batch operations — auth derived per-id from each transaction's account.
export const batch = {
	deleteTransactions: (ids: number[]) =>
		request<void>('/transactions/batch-delete', { method: 'POST', body: { ids } }),
	updateCategory: (ids: number[], category_id: number) =>
		request<void>('/transactions/batch-update-category', { method: 'POST', body: { ids, category_id } })
};

// CSV import/export
export const csv = {
	exportTransactions: async (params?: { from?: string; to?: string; owner_id?: string; account_id?: string }) => {
		let url = `${BASE}/transactions/export`;
		if (params) {
			const search = new URLSearchParams(
				Object.entries(params).filter(([, v]) => v !== '' && v !== undefined) as [string, string][]
			);
			if (search.toString()) url += `?${search}`;
		}
		const headers: Record<string, string> = {};
		const token = getToken();
		if (token) headers['Authorization'] = `Bearer ${token}`;
		const res = await fetch(url, { headers });
		if (!res.ok) throw new ApiError(res.status, await res.text());
		const blob = await res.blob();
		const a = document.createElement('a');
		a.href = URL.createObjectURL(blob);
		a.download = 'transactions.csv';
		a.click();
		URL.revokeObjectURL(a.href);
	},
	importTransactions: async (file: File) => {
		const url = `${BASE}/transactions/import`;
		const headers: Record<string, string> = {};
		const token = getToken();
		if (token) headers['Authorization'] = `Bearer ${token}`;
		const form = new FormData();
		form.append('file', file);
		const res = await fetch(url, { method: 'POST', headers, body: form });
		if (!res.ok) throw new ApiError(res.status, await res.text());
		return res.json() as Promise<{ imported: number }>;
	}
};

// Audit logs
export interface AuditLog {
	id: number;
	admin_user_id: number;
	admin_username: string;
	action: string;
	target_user_id: number | null;
	details: string | null;
	ip_address: string | null;
	created_at: string;
}

// Backup settings
export interface BackupSettings {
	enabled: boolean;
	frequency: string;
	retention_count: number;
	last_backup_at: string | null;
}

export const admin = {
	listUsers: () => request<AdminUser[]>('/admin/users'),
	createUser: (username: string, email: string, name: string, password: string) =>
		request<{ id: number }>('/admin/users', { method: 'POST', body: { username, email, name, password } }),
	updateUser: (id: number, data: { username?: string; email?: string; name?: string }) =>
		request<void>(`/admin/users/${id}`, { method: 'PUT', body: data }),
	deleteUser: (id: number) => request<void>(`/admin/users/${id}`, { method: 'DELETE' }),
	resetPassword: (id: number, new_password: string) =>
		request<void>(`/admin/users/${id}/reset-password`, { method: 'POST', body: { new_password } }),
	toggleAdmin: (id: number) =>
		request<void>(`/admin/users/${id}/toggle-admin`, { method: 'POST' }),
	disableTOTP: (id: number) =>
		request<void>(`/admin/users/${id}/disable-totp`, { method: 'POST' }),
	auditLogs: (params?: { limit?: string; offset?: string }) =>
		request<AuditLog[]>('/admin/audit-logs', { params }),
	getBackupSettings: () => request<BackupSettings>('/admin/backup/settings'),
	updateBackupSettings: (data: { enabled: boolean; frequency: string; retention_count: number }) =>
		request<void>('/admin/backup/settings', { method: 'PUT', body: data }),
	backup: async () => {
		const url = `${BASE}/admin/backup`;
		const headers: Record<string, string> = {};
		const token = getToken();
		if (token) headers['Authorization'] = `Bearer ${token}`;
		const res = await fetch(url, { headers });
		if (!res.ok) throw new ApiError(res.status, await res.text());
		const blob = await res.blob();
		const a = document.createElement('a');
		a.href = URL.createObjectURL(blob);
		a.download = `insitu-backup-${new Date().toISOString().slice(0, 10)}.db`;
		a.click();
		URL.revokeObjectURL(a.href);
	},
	restore: async (file: File) => {
		const url = `${BASE}/admin/restore`;
		const headers: Record<string, string> = {};
		const token = getToken();
		if (token) headers['Authorization'] = `Bearer ${token}`;
		const fd = new FormData();
		fd.append('file', file);
		const res = await fetch(url, { method: 'POST', headers, body: fd });
		if (!res.ok) {
			const body = await res.text();
			throw new ApiError(res.status, friendlyError(res.status, body));
		}
		return (await res.json()) as { status: string; restarting: boolean; pre_restore_snapshot: string };
	}
};
