import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import TransactionsPage from '../routes/transactions/+page.svelte';
import { accounts, categories, scheduled, transactions } from './api/client';
import { sharedOwnerUserId } from './stores/shared';

// The single-entry form must never submit a category whose type differs from
// the entry's type (the server does not check it). The API lists categories by
// name, so an income category is often first.
vi.mock('./api/client', () => ({
	accounts: { list: vi.fn() }, categories: { list: vi.fn(), create: vi.fn() }, scheduled: { list: vi.fn(), create: vi.fn() },
	transactions: { list: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn(), autocomplete: vi.fn() },
	batch: { deleteTransactions: vi.fn(), updateTransactions: vi.fn() },
	csv: { exportTransactions: vi.fn(), importTransactions: vi.fn() }
}));

const wallet = { id: 1, user_id: 1, owner_user_id: 1, owner_name: 'Me', name: 'Wallet', currency: 'EUR', balance: 0, is_shared: false };
const salary = { id: 20, user_id: 1, parent_id: null, name: 'Aaa Salary', type: 'income' };
const food = { id: 10, user_id: 1, parent_id: null, name: 'Food', type: 'expense' };
const legacy = {
	id: 7, account_id: 1, category_id: 20, user_id: 1, type: 'expense', amount: 5, currency: 'EUR',
	description: 'Legacy', note: null, date: '2026-01-05', created_by_user_id: 1, created_by_name: 'Me'
};

beforeEach(() => {
	vi.resetAllMocks();
	sharedOwnerUserId.set(null);
	vi.mocked(transactions.list).mockResolvedValue([legacy] as any);
	vi.mocked(transactions.create).mockResolvedValue(undefined as any);
	vi.mocked(transactions.update).mockResolvedValue(undefined as any);
	vi.mocked(accounts.list).mockResolvedValue([wallet] as any);
	vi.mocked(categories.list).mockResolvedValue([salary, food] as any);
	vi.mocked(scheduled.list).mockResolvedValue([]);
});
afterEach(cleanup);

const formCategory = () => document.getElementById('form-category') as HTMLSelectElement;

async function openCreateForm() {
	render(TransactionsPage);
	await waitFor(() => expect(screen.getByText('Legacy')).toBeTruthy());
	await fireEvent.click(screen.getByRole('button', { name: '+ New Transaction' }));
	await fireEvent.input(document.getElementById('amount')!, { target: { value: '12' } });
}

describe('single-entry form category/type consistency', () => {
	it('defaults a new expense to an expense category even when an income category is listed first', async () => {
		await openCreateForm();
		expect(formCategory().value).toBe('10');
		await fireEvent.click(screen.getByRole('button', { name: 'Create' }));
		await waitFor(() => expect(transactions.create).toHaveBeenCalledTimes(1));
		expect(vi.mocked(transactions.create).mock.calls[0][0]).toMatchObject({ type: 'expense', category_id: 10 });
	});

	it('switching the type swaps in a category of the new type', async () => {
		await openCreateForm();
		await fireEvent.change(document.getElementById('type')!, { target: { value: 'income' } });
		expect(formCategory().value).toBe('20');
		await fireEvent.click(screen.getByRole('button', { name: 'Create' }));
		await waitFor(() => expect(transactions.create).toHaveBeenCalledTimes(1));
		expect(vi.mocked(transactions.create).mock.calls[0][0]).toMatchObject({ type: 'income', category_id: 20 });
	});

	it('refuses to save an entry whose stored category has the other type until one is chosen', async () => {
		render(TransactionsPage);
		await waitFor(() => expect(screen.getByText('Legacy')).toBeTruthy());
		await fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
		await fireEvent.click(screen.getByRole('button', { name: 'Update' }));
		await waitFor(() => expect(screen.getByText('Choose an expense category')).toBeTruthy());
		expect(transactions.update).not.toHaveBeenCalled();

		await fireEvent.change(formCategory(), { target: { value: '10' } });
		await fireEvent.click(screen.getByRole('button', { name: 'Update' }));
		await waitFor(() => expect(transactions.update).toHaveBeenCalledTimes(1));
		expect(vi.mocked(transactions.update).mock.calls[0]).toMatchObject([7, { type: 'expense', category_id: 10 }]);
	});
});

describe('editing an entry whose category was deleted', () => {
	// The API no longer lists a soft-deleted category, so the entry's own
	// category_id (99) is unknown to the form.
	const orphaned = { ...legacy, id: 8, category_id: 99, description: 'Orphaned' };

	async function editOrphaned() {
		vi.mocked(transactions.list).mockResolvedValue([orphaned] as any);
		render(TransactionsPage);
		await waitFor(() => expect(screen.getByText('Orphaned')).toBeTruthy());
		await fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
	}

	it('saves an amount-only change, keeping the category', async () => {
		await editOrphaned();
		await fireEvent.input(document.getElementById('amount')!, { target: { value: '9' } });
		await fireEvent.click(screen.getByRole('button', { name: 'Update' }));
		await waitFor(() => expect(transactions.update).toHaveBeenCalledTimes(1));
		expect(vi.mocked(transactions.update).mock.calls[0]).toMatchObject([8, { type: 'expense', category_id: 99, amount: 9 }]);
	});

	it('keeps the category when the reference data reloads mid-edit', async () => {
		await editOrphaned();
		sharedOwnerUserId.set('2');
		await waitFor(() => expect(categories.list).toHaveBeenCalledTimes(2));
		await waitFor(() => expect(screen.getByText('Orphaned')).toBeTruthy());
		await fireEvent.click(screen.getByRole('button', { name: 'Update' }));
		await waitFor(() => expect(transactions.update).toHaveBeenCalledTimes(1));
		expect(vi.mocked(transactions.update).mock.calls[0]).toMatchObject([8, { category_id: 99 }]);
	});

	it('refuses an unknown category that is not the entry\'s own', async () => {
		// No income category exists, so switching the type leaves none chosen.
		vi.mocked(categories.list).mockResolvedValue([food] as any);
		await editOrphaned();
		await fireEvent.change(document.getElementById('type')!, { target: { value: 'income' } });
		await fireEvent.click(screen.getByRole('button', { name: 'Update' }));
		await waitFor(() => expect(screen.getByText('Choose an income category')).toBeTruthy());
		// Switching back picks a real category; the deleted one is not restored.
		await fireEvent.change(document.getElementById('type')!, { target: { value: 'expense' } });
		await fireEvent.click(screen.getByRole('button', { name: 'Update' }));
		await waitFor(() => expect(transactions.update).toHaveBeenCalledTimes(1));
		expect(vi.mocked(transactions.update).mock.calls[0]).toMatchObject([8, { type: 'expense', category_id: 10 }]);
	});

	it('refuses to create an entry without a category of its type', async () => {
		vi.mocked(categories.list).mockResolvedValue([salary] as any);
		await openCreateForm();
		await fireEvent.click(screen.getByRole('button', { name: 'Create' }));
		await waitFor(() => expect(screen.getByText('Choose an expense category')).toBeTruthy());
		expect(transactions.create).not.toHaveBeenCalled();
		expect(scheduled.create).not.toHaveBeenCalled();
	});
});
