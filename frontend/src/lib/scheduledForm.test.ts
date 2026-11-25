import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import ScheduledPage from '../routes/scheduled/+page.svelte';
import { accounts, categories, scheduled } from './api/client';
import { sharedOwnerUserId } from './stores/shared';

// The Scheduled form has the same rule as the transaction form: never submit
// a category whose type differs from the schedule's (the server does not
// check it), yet still save a schedule whose own category was deleted.
vi.mock('./api/client', () => ({
	accounts: { list: vi.fn() }, categories: { list: vi.fn(), create: vi.fn() },
	scheduled: { list: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn() }
}));

const wallet = { id: 1, user_id: 1, owner_user_id: 1, owner_name: 'Me', name: 'Wallet', currency: 'EUR', balance: 0, is_shared: false };
// Listed by name, so the income category comes first.
const salary = { id: 20, user_id: 1, parent_id: null, name: 'Aaa Salary', type: 'income' };
const food = { id: 10, user_id: 1, parent_id: null, name: 'Food', type: 'expense' };

function schedule(id: number, over: Record<string, unknown> = {}) {
	return {
		id, account_id: 1, category_id: 10, user_id: 1, type: 'expense', amount: 5, currency: 'EUR',
		description: `Plan ${id}`, note: null, rrule: 'FREQ=MONTHLY', next_occurrence: '2026-02-01T09:00:00+01:00',
		active: true, max_occurrences: null, occurrence_count: 0, deleted_at: null,
		created_by_user_id: 1, created_by_name: 'Me', ...over
	};
}

beforeEach(() => {
	vi.resetAllMocks();
	sharedOwnerUserId.set(null);
	vi.mocked(scheduled.list).mockResolvedValue([schedule(1)] as any);
	vi.mocked(scheduled.create).mockResolvedValue(undefined as any);
	vi.mocked(scheduled.update).mockResolvedValue(undefined as any);
	vi.mocked(accounts.list).mockResolvedValue([wallet] as any);
	vi.mocked(categories.list).mockResolvedValue([salary, food] as any);
});
afterEach(cleanup);

const formCategory = () => document.getElementById('form-category') as HTMLSelectElement;

async function renderPage(first = 'Plan 1') {
	render(ScheduledPage);
	await waitFor(() => expect(screen.getByText(first)).toBeTruthy());
}

async function openCreateForm() {
	await renderPage();
	await fireEvent.click(screen.getByRole('button', { name: '+ New Scheduled' }));
	await fireEvent.input(document.getElementById('amount')!, { target: { value: '12' } });
}

describe('scheduled form category/type consistency', () => {
	it('defaults a new schedule to an expense category even when an income category is listed first', async () => {
		await openCreateForm();
		expect(formCategory().value).toBe('10');
		await fireEvent.click(screen.getByRole('button', { name: 'Create' }));
		await waitFor(() => expect(scheduled.create).toHaveBeenCalledTimes(1));
		expect(vi.mocked(scheduled.create).mock.calls[0][0]).toMatchObject({ type: 'expense', category_id: 10 });
	});

	it('switching the type swaps in a category of the new type', async () => {
		await openCreateForm();
		await fireEvent.change(document.getElementById('type')!, { target: { value: 'income' } });
		expect(formCategory().value).toBe('20');
		await fireEvent.click(screen.getByRole('button', { name: 'Create' }));
		await waitFor(() => expect(scheduled.create).toHaveBeenCalledTimes(1));
		expect(vi.mocked(scheduled.create).mock.calls[0][0]).toMatchObject({ type: 'income', category_id: 20 });
	});

	it('refuses to save a schedule whose category has the other type until one is chosen', async () => {
		vi.mocked(scheduled.list).mockResolvedValue([schedule(3, { category_id: 20 })] as any);
		await renderPage('Plan 3');
		await fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
		await fireEvent.click(screen.getByRole('button', { name: 'Update' }));
		await waitFor(() => expect(screen.getByText('Choose an expense category')).toBeTruthy());
		expect(scheduled.update).not.toHaveBeenCalled();

		await fireEvent.change(formCategory(), { target: { value: '10' } });
		await fireEvent.click(screen.getByRole('button', { name: 'Update' }));
		await waitFor(() => expect(scheduled.update).toHaveBeenCalledTimes(1));
		expect(vi.mocked(scheduled.update).mock.calls[0]).toMatchObject([3, { type: 'expense', category_id: 10 }]);
	});

	it('refuses to create a schedule without a category of its type', async () => {
		vi.mocked(categories.list).mockResolvedValue([salary] as any);
		await openCreateForm();
		await fireEvent.click(screen.getByRole('button', { name: 'Create' }));
		await waitFor(() => expect(screen.getByText('Choose an expense category')).toBeTruthy());
		expect(scheduled.create).not.toHaveBeenCalled();
	});
});

describe('editing a schedule whose category was deleted', () => {
	const orphaned = () => vi.mocked(scheduled.list).mockResolvedValue([schedule(4, { category_id: 99 })] as any);

	it('saves an amount-only change, keeping the category', async () => {
		orphaned();
		await renderPage('Plan 4');
		await fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
		await fireEvent.input(document.getElementById('amount')!, { target: { value: '9' } });
		await fireEvent.click(screen.getByRole('button', { name: 'Update' }));
		await waitFor(() => expect(scheduled.update).toHaveBeenCalledTimes(1));
		expect(vi.mocked(scheduled.update).mock.calls[0]).toMatchObject([4, { type: 'expense', category_id: 99, amount: 9 }]);
	});

	it('keeps the category when the page reloads mid-edit', async () => {
		orphaned();
		await renderPage('Plan 4');
		await fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
		sharedOwnerUserId.set('2');
		await waitFor(() => expect(categories.list).toHaveBeenCalledTimes(2));
		await waitFor(() => expect(screen.getByText('Plan 4')).toBeTruthy());
		await fireEvent.click(screen.getByRole('button', { name: 'Update' }));
		await waitFor(() => expect(scheduled.update).toHaveBeenCalledTimes(1));
		expect(vi.mocked(scheduled.update).mock.calls[0]).toMatchObject([4, { category_id: 99 }]);
	});

	it("refuses an unknown category that is not the schedule's own", async () => {
		orphaned();
		vi.mocked(categories.list).mockResolvedValue([food] as any);
		await renderPage('Plan 4');
		await fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
		await fireEvent.change(document.getElementById('type')!, { target: { value: 'income' } });
		await fireEvent.click(screen.getByRole('button', { name: 'Update' }));
		await waitFor(() => expect(screen.getByText('Choose an income category')).toBeTruthy());
		expect(scheduled.update).not.toHaveBeenCalled();
	});
});
