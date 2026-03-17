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
		body: body ? JSON.stringify(body) : undefined
	});

	if (res.status === 401 && !path.startsWith('/auth/login')) {
		clearToken();
		if (typeof window !== 'undefined') window.location.href = '/login';
		throw new ApiError(401, 'Unauthorized');
	}

	if (!res.ok) {
		const text = await res.text();
		throw new ApiError(res.status, text);
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
}

export const auth = {
	login: (login: string, password: string, totp_code?: string) =>
		request<LoginResponse>('/auth/login', {
			method: 'POST',
			body: { login, password, totp_code }
		}),
	logout: () => request<void>('/auth/logout', { method: 'POST' })
};

// Transactions
export interface Transaction {
	id: number;
	account_id: number;
	category_id: number;
	user_id: number;
	type: 'income' | 'expense';
	amount: number;
	currency: string;
	description: string | null;
	date: string;
	created_at: string;
	updated_at: string;
	sync_version: number;
}

export interface TransactionInput {
	account_id: number;
	category_id: number;
	type: 'income' | 'expense';
	amount: number;
	currency?: string;
	description?: string;
	date: string;
}

export interface AutocompleteSuggestion {
	description: string;
	category_id: number;
}

export const transactions = {
	list: (params?: { from?: string; to?: string; category_id?: string; limit?: string; offset?: string }) =>
		request<Transaction[]>('/transactions', { params }),
	create: (data: TransactionInput) =>
		request<{ id: number }>('/transactions', { method: 'POST', body: data }),
	update: (id: number, data: TransactionInput) =>
		request<void>(`/transactions/${id}`, { method: 'PUT', body: data }),
	delete: (id: number) =>
		request<void>(`/transactions/${id}`, { method: 'DELETE' }),
	autocomplete: (q: string) =>
		request<AutocompleteSuggestion[]>('/transactions/autocomplete', { params: { q } })
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
}

export interface CategoryInput {
	parent_id?: number | null;
	name: string;
	type: 'income' | 'expense';
	icon?: string;
	color?: string;
}

export const categories = {
	list: () => request<Category[]>('/categories'),
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
}

export interface AccountInput {
	name: string;
	currency?: string;
	balance?: number;
}

export const accounts = {
	list: () => request<Account[]>('/accounts'),
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
	user_id: number;
	type: 'income' | 'expense';
	amount: number;
	currency: string;
	description: string | null;
	rrule: string;
	next_occurrence: string;
	active: boolean;
	created_at: string;
	updated_at: string;
	sync_version: number;
}

export interface ScheduledInput {
	account_id: number;
	category_id: number;
	type: 'income' | 'expense';
	amount: number;
	currency?: string;
	description?: string;
	rrule: string;
	next_occurrence: string;
}

export const scheduled = {
	list: () => request<ScheduledTransaction[]>('/scheduled'),
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
	byCategory: (params?: { from?: string; to?: string; type?: string }) =>
		request<CategoryReport[]>('/reports/by-category', { params }),
	byMonth: (params?: { year?: string }) =>
		request<MonthReport[]>('/reports/by-month', { params }),
	trend: (params?: { from?: string; to?: string; group_by?: string }) =>
		request<TrendReport[]>('/reports/trend', { params })
};

// Shared access
export interface SharedAccess {
	id: number;
	owner_user_id: number;
	guest_user_id: number;
	permission: string;
	guest_name: string;
	guest_email: string;
}

export const shared = {
	list: () => request<SharedAccess[]>('/shared'),
	create: (guest_email: string, permission: string) =>
		request<{ id: number }>('/shared', { method: 'POST', body: { guest_email, permission } }),
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
}

export const me = {
	get: () => request<UserProfile>('/auth/me'),
	changePassword: (current_password: string, new_password: string) =>
		request<void>('/auth/change-password', { method: 'POST', body: { current_password, new_password } }),
	updateProfile: (data: { username?: string; email?: string; name?: string }) =>
		request<void>('/auth/profile', { method: 'PUT', body: data }),
	totpSetup: () => request<{ secret: string; qr_code: string; otpauth: string }>('/auth/totp/setup', { method: 'POST' }),
	totpVerify: (code: string) => request<void>('/auth/totp/verify', { method: 'POST', body: { code } }),
	totpReset: (password: string) => request<{ secret: string; qr_code: string; otpauth: string }>('/auth/totp/reset', { method: 'POST', body: { password } })
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

// Batch operations
export const batch = {
	deleteTransactions: (ids: number[]) =>
		request<void>('/transactions/batch-delete', { method: 'POST', body: { ids } }),
	updateCategory: (ids: number[], category_id: number) =>
		request<void>('/transactions/batch-update-category', { method: 'POST', body: { ids, category_id } })
};

// CSV import/export
export const csv = {
	exportTransactions: async (params?: { from?: string; to?: string }) => {
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
	}
};
