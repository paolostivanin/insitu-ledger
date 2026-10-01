import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import AccountsPage from '../routes/accounts/+page.svelte';
import ScheduledPage from '../routes/scheduled/+page.svelte';
import TransactionsPage from '../routes/transactions/+page.svelte';
import DashboardPage from '../routes/+page.svelte';
import CategoriesPage from '../routes/categories/+page.svelte';
import { accounts, categories, scheduled, transactions, reports } from './api/client';
import { sharedOwnerUserId } from './stores/shared';

vi.mock('./api/client', () => ({
	accounts: { list: vi.fn(), create: vi.fn() }, categories: { list: vi.fn(), create: vi.fn() }, scheduled: { list: vi.fn(), create: vi.fn() },
	transactions: { list: vi.fn(), create: vi.fn() }, reports: { byCategory: vi.fn(), byMonth: vi.fn() },
	batch: {}, csv: {},
	isAuthenticated: vi.fn(() => true)
}));
beforeEach(() => { vi.resetAllMocks(); sharedOwnerUserId.set(null); });
afterEach(cleanup);

it('ends a failed account load and Retry fetches data', async () => {
	vi.mocked(accounts.list).mockRejectedValueOnce(new Error('Network unavailable')).mockResolvedValueOnce([]);
	render(AccountsPage);
	await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('Network unavailable'));
	await fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
	await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
	expect(accounts.list).toHaveBeenCalledTimes(2);
});

it('transaction success cannot hide a failed reference-data request', async () => {
	let finish!: (value: any[]) => void;
	vi.mocked(transactions.list).mockImplementationOnce(() => new Promise(resolve => finish = resolve)).mockResolvedValue([]);
	vi.mocked(accounts.list).mockResolvedValue([]);
	vi.mocked(categories.list).mockRejectedValueOnce(new Error('Categories unavailable')).mockResolvedValue([]);
	render(TransactionsPage);
	await waitFor(() => expect(categories.list).toHaveBeenCalledTimes(1));
	finish([]);
	await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('Categories unavailable'));
	await fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
	await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
});

it('dashboard reports a partial failure and Retry loads a complete view', async () => {
	vi.mocked(accounts.list).mockResolvedValue([]);
	vi.mocked(transactions.list).mockResolvedValue([{ id: 1, account_id: 1, date: '2026-01-01', type: 'expense', amount: 7, description: 'Visible purchase' } as any]);
	vi.mocked(reports.byCategory).mockRejectedValueOnce(new Error('Unavailable')).mockResolvedValue([]);
	vi.mocked(reports.byMonth).mockResolvedValue([]);
	vi.mocked((await import('./api/client')).isAuthenticated).mockReturnValue(true);
	render(DashboardPage);
	await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('Some dashboard data could not be loaded'));
	expect(screen.getByRole('heading', { name: 'Dashboard' })).toBeTruthy();
	expect(screen.getByText('Visible purchase')).toBeTruthy();
	expect(screen.getByText('Expense categories unavailable')).toBeTruthy();
	await fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
	await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
	expect(reports.byCategory).toHaveBeenCalledTimes(2);
});

it('dashboard uses the full-page error only when every request fails', async () => {
	for (const request of [accounts.list, transactions.list, reports.byCategory, reports.byMonth]) {
		vi.mocked(request).mockRejectedValue(new Error('Unavailable'));
	}
	vi.mocked((await import('./api/client')).isAuthenticated).mockReturnValue(true);
	render(DashboardPage);
	await waitFor(() => expect(screen.getByRole('alert').textContent).toBe('Failed to load dashboard data.'));
	expect(screen.queryByRole('heading', { name: 'Recent Transactions' })).toBeNull();
});

it('dashboard does not present zero income or expenses when the monthly request fails', async () => {
	vi.mocked(accounts.list).mockResolvedValue([]); vi.mocked(transactions.list).mockResolvedValue([]);
	vi.mocked(reports.byCategory).mockResolvedValue([]); vi.mocked(reports.byMonth).mockRejectedValue(new Error('Unavailable'));
	vi.mocked((await import('./api/client')).isAuthenticated).mockReturnValue(true);
	render(DashboardPage);
	await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
	for (const label of ['This Month Income', 'This Month Expenses']) {
		expect(screen.getByText(label).parentElement?.querySelector('.stat-value')?.textContent).toBe('—');
	}
});

it.each([
	['Account', AccountsPage, accounts.create], ['Category', CategoriesPage, categories.create],
	['Scheduled', ScheduledPage, scheduled.create], ['Transaction', TransactionsPage, transactions.create]
] as const)('a failed %s save leaves the form open without offering a list Retry', async (name, component, create) => {
	vi.mocked(accounts.list).mockResolvedValue([{ id: 1, name: 'Wallet', currency: 'EUR', balance: 0 } as any]);
	vi.mocked(categories.list).mockResolvedValue([{ id: 1, name: 'Food', type: 'expense', parent_id: null } as any]);
	vi.mocked(scheduled.list).mockResolvedValue([]); vi.mocked(transactions.list).mockResolvedValue([]);
	vi.mocked(create).mockRejectedValue(new Error('Could not save'));
	const { container } = render(component);
	await waitFor(() => expect(screen.queryByText('Loading...')).toBeNull());
	await fireEvent.click(screen.getByRole('button', { name: `+ New ${name}` }));
	await fireEvent.submit(container.querySelector('form')!);
	await waitFor(() => expect(screen.getByRole('alert').textContent).toBe('Could not save'));
	expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
	expect(container.querySelector('form')).toBeTruthy();
	expect(create).toHaveBeenCalledOnce();
});

it('drops an older owner response even if it arrives last', async () => {
	let finish!: (value: any[]) => void;
	vi.mocked(accounts.list).mockImplementationOnce(() => new Promise(resolve => finish = resolve))
		.mockResolvedValueOnce([{ id: 2, name: 'Current owner account', balance: 0, currency: 'EUR' } as any]);
	render(AccountsPage);
	sharedOwnerUserId.set('2');
	await waitFor(() => expect(screen.getByText('Current owner account')).toBeTruthy());
	finish([{ id: 1, name: 'Previous owner account', balance: 0, currency: 'EUR' }]);
	await waitFor(() => expect(screen.queryByText('Previous owner account')).toBeNull());
	expect(screen.getByText('Current owner account')).toBeTruthy();
});

it('scheduled reference-data failure ends loading and can be retried', async () => {
	vi.mocked(scheduled.list).mockResolvedValue([]);
	vi.mocked(accounts.list).mockResolvedValue([]);
	vi.mocked(categories.list).mockRejectedValueOnce(new Error('Categories unavailable')).mockResolvedValueOnce([]);
	render(ScheduledPage);
	await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('Categories unavailable'));
	await fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
	await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
	expect(scheduled.list).toHaveBeenCalledTimes(2);
});
