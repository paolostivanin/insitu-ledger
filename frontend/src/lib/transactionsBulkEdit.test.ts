import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import TransactionsPage from '../routes/transactions/+page.svelte';
import { accounts, batch, categories, scheduled, transactions, type TransactionListParams } from './api/client';
import { addToast } from './stores/toast';
import { sharedOwnerUserId } from './stores/shared';

vi.mock('./api/client', () => ({
	accounts: { list: vi.fn() }, categories: { list: vi.fn(), create: vi.fn() }, scheduled: { list: vi.fn(), create: vi.fn() },
	transactions: { list: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn(), autocomplete: vi.fn() },
	batch: { deleteTransactions: vi.fn(), updateTransactions: vi.fn() },
	csv: { exportTransactions: vi.fn(), importTransactions: vi.fn() }
}));
vi.mock('./stores/toast', async importOriginal => ({ ...(await importOriginal<object>()), addToast: vi.fn() }));

const wallet = { id: 1, user_id: 1, owner_user_id: 1, owner_name: 'Me', name: 'Wallet', currency: 'EUR', balance: 0, is_shared: false };
const family = { ...wallet, id: 2, name: 'Family', is_shared: true };
const food = { id: 10, user_id: 1, parent_id: null, name: 'Food', type: 'expense' };
const rent = { id: 12, user_id: 1, parent_id: null, name: 'Rent', type: 'expense' };

function row(id: number, over: Record<string, unknown> = {}) {
	return {
		id, account_id: 1, category_id: 10, user_id: 1, type: 'expense', amount: 5, currency: 'EUR',
		description: `Entry ${id}`, note: null, date: `2026-01-${String((id % 28) + 1).padStart(2, '0')}`,
		created_by_user_id: 1, created_by_name: 'Me', ...over
	};
}
const rows = (n: number) => Array.from({ length: n }, (_, i) => row(i + 1));

const OK = { updated: 2, unchanged: 0, skipped: 0, schedules_updated: 0, schedules_unchanged: 0, schedules_skipped: 0 };

// Serve `data` as a paged list; `hold` lets a test park specific requests.
let data: ReturnType<typeof row>[] = [];
let hold: ((params: TransactionListParams) => Promise<unknown[]> | undefined) | null = null;

beforeEach(() => {
	vi.resetAllMocks();
	sharedOwnerUserId.set(null);
	data = rows(3);
	hold = null;
	vi.mocked(transactions.list).mockImplementation(async (params?: TransactionListParams) => {
		const held = hold?.(params ?? {});
		if (held) return (await held) as any;
		const offset = Number(params?.offset ?? 0);
		return data.slice(offset, offset + Number(params?.limit ?? 50)) as any;
	});
	vi.mocked(accounts.list).mockResolvedValue([wallet] as any);
	vi.mocked(categories.list).mockResolvedValue([food, rent] as any);
	vi.mocked(scheduled.list).mockResolvedValue([]);
});
afterEach(cleanup);

const listCalls = () => vi.mocked(transactions.list).mock.calls.map(([params]) => params ?? {});
const lastListCall = () => listCalls().at(-1)!;
const rowBox = (id: number) => screen.getByRole('checkbox', { name: new RegExp(`^Select Entry ${id} `) });
const selectedCount = () => screen.queryByText(/^\d+ selected$/)?.textContent ?? null;
const withoutPaging = ({ limit: _l, offset: _o, ...rest }: TransactionListParams) => rest;

async function renderPage() {
	render(TransactionsPage);
	await waitFor(() => expect(screen.getByText(`Entry ${data[0].id}`)).toBeTruthy());
}

// Let pending reloads land so the next step starts from a settled page.
async function settle(calls: number) {
	await waitFor(() => expect(transactions.list).toHaveBeenCalledTimes(calls));
	await Promise.resolve();
}

async function openBulkEdit() {
	await fireEvent.click(screen.getByRole('button', { name: 'Edit…' }));
	return screen.findByRole('dialog');
}

// Like a browser picking a date: `input` updates the bound value (bind:value
// listens to it), then `change` triggers the reload.
async function setDate(label: 'From' | 'To', value: string) {
	const field = screen.getByLabelText(label);
	await fireEvent.input(field, { target: { value } });
	await fireEvent.change(field);
}

describe('applying a bulk edit', () => {
	it('sends exactly the selected ids and only the opted-in field, then toasts, reloads and clears the selection', async () => {
		vi.mocked(batch.updateTransactions).mockResolvedValue(OK);
		await renderPage();
		const before = listCalls().length;
		await fireEvent.click(rowBox(3));
		await fireEvent.click(rowBox(1));
		expect(selectedCount()).toBe('2 selected');
		await openBulkEdit();
		await fireEvent.click(screen.getByRole('checkbox', { name: 'Change name' }));
		await fireEvent.input(screen.getByRole('textbox', { name: 'New name' }), { target: { value: 'Stipendio' } });
		await fireEvent.click(screen.getByRole('button', { name: 'Apply to 2 entries' }));

		await waitFor(() => expect(addToast).toHaveBeenCalledWith('Updated 2 entries'));
		expect(batch.updateTransactions).toHaveBeenCalledTimes(1);
		expect(vi.mocked(batch.updateTransactions).mock.calls[0][0]).toStrictEqual({ ids: [1, 3], description: 'Stipendio' });
		await waitFor(() => expect(listCalls().length).toBe(before + 1));
		expect(selectedCount()).toBeNull();
		expect(screen.queryByRole('dialog')).toBeNull();
		// Schedules were untouched, so they are not refetched.
		expect(scheduled.list).toHaveBeenCalledTimes(1);
	});

	it('reloads schedules after it changed some, and sends the linked-schedule flag only when ticked', async () => {
		data = [row(1, { scheduled_id: 7 }), row(2, { scheduled_id: 7 })];
		vi.mocked(scheduled.list).mockResolvedValue([{ id: 7, user_id: 1, type: 'expense', rrule: 'FREQ=MONTHLY', deleted_at: null } as any]);
		vi.mocked(batch.updateTransactions).mockResolvedValue({ ...OK, schedules_updated: 1 });
		await renderPage();
		await fireEvent.click(screen.getByRole('checkbox', { name: 'Select all loaded entries' }));
		await openBulkEdit();
		await fireEvent.click(screen.getByRole('checkbox', { name: 'Change category' }));
		await fireEvent.change(screen.getByRole('combobox', { name: 'Category to apply' }), { target: { value: '12' } });
		await fireEvent.click(screen.getByRole('checkbox', { name: 'Also update 1 linked recurring schedule' }));
		await fireEvent.click(screen.getByRole('button', { name: 'Apply to 2 entries' }));

		await waitFor(() => expect(addToast).toHaveBeenCalledWith('Updated 2 entries · 1 recurring schedule updated'));
		expect(vi.mocked(batch.updateTransactions).mock.calls[0][0]).toStrictEqual({ ids: [1, 2], category_id: 12, update_schedules: true });
		await waitFor(() => expect(scheduled.list).toHaveBeenCalledTimes(2));
		for (const [params] of vi.mocked(scheduled.list).mock.calls) expect(params?.include_deleted).toBe('1');
	});

	it('keeps the dialog open and the selection intact when the server refuses', async () => {
		vi.mocked(batch.updateTransactions).mockRejectedValue(new Error('category type does not match the selected entries'));
		await renderPage();
		const before = listCalls().length;
		await fireEvent.click(rowBox(1));
		await fireEvent.click(rowBox(2));
		await openBulkEdit();
		await fireEvent.click(screen.getByRole('checkbox', { name: 'Change name' }));
		await fireEvent.input(screen.getByRole('textbox', { name: 'New name' }), { target: { value: 'Stipendio' } });
		await fireEvent.click(screen.getByRole('button', { name: 'Apply to 2 entries' }));

		await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('category type does not match'));
		expect(batch.updateTransactions).toHaveBeenCalledWith({ ids: [1, 2], description: 'Stipendio' });
		expect(screen.getByRole('dialog')).toBeTruthy();
		expect(addToast).not.toHaveBeenCalled();
		expect(listCalls().length).toBe(before);
		await fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
		expect(selectedCount()).toBe('2 selected');
		expect((rowBox(1) as HTMLInputElement).checked).toBe(true);
		expect((rowBox(2) as HTMLInputElement).checked).toBe(true);
	});

	it('deletes exactly the entries named in the confirmation', async () => {
		vi.mocked(batch.deleteTransactions).mockResolvedValue(undefined);
		await renderPage();
		await fireEvent.click(rowBox(2));
		await fireEvent.click(rowBox(3));
		await fireEvent.click(screen.getByRole('button', { name: 'Delete Selected' }));
		expect(screen.getByText('Delete 2 transaction(s)?')).toBeTruthy();
		await fireEvent.click(screen.getByRole('button', { name: 'Delete' }));
		await waitFor(() => expect(batch.deleteTransactions).toHaveBeenCalledWith([2, 3]));
	});

	it('the Escape shortcut closes the dialog without applying', async () => {
		await renderPage();
		await fireEvent.click(rowBox(1));
		await openBulkEdit();
		window.dispatchEvent(new CustomEvent('shortcut-close'));
		await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
		expect(batch.updateTransactions).not.toHaveBeenCalled();
		expect(selectedCount()).toBe('1 selected');
	});
});

describe('the selection never outlives the result set', () => {
	it('clears at once on search, exact match, "Added by me", date and category filter changes', async () => {
		vi.mocked(accounts.list).mockResolvedValue([wallet, family] as any);
		await renderPage();
		let calls = listCalls().length;
		// Park each reload: the old rows stay on screen, so only a clear made
		// before the request is answered can pass. It is answered before the
		// next step, as the old rows can't be ticked while it is pending.
		let release = () => {};
		hold = () => new Promise(resolve => release = () => resolve(data));

		const steps: Array<[string, () => Promise<unknown>]> = [
			['search', () => fireEvent.input(screen.getByLabelText('Search'), { target: { value: 'Entry' } })],
			['exact match', () => fireEvent.click(screen.getByRole('checkbox', { name: 'Exact match' }))],
			['added by me', () => fireEvent.click(screen.getByRole('checkbox', { name: 'Added by me' }))],
			['from date', () => setDate('From', '2026-01-01')],
			['to date', () => setDate('To', '2026-12-31')],
			['category', () => fireEvent.change(screen.getByLabelText('Category'), { target: { value: '10' } })]
		];
		for (const [label, change] of steps) {
			await fireEvent.click(rowBox(1));
			expect(selectedCount(), label).toBe('1 selected');
			await change();
			// Synchronously, before the reload answers.
			expect(selectedCount(), label).toBeNull();
			expect((rowBox(1) as HTMLInputElement).disabled, label).toBe(true);
			await settle(++calls);
			release();
			await waitFor(() => expect((rowBox(1) as HTMLInputElement).disabled, label).toBe(false));
		}
		expect(lastListCall()).toMatchObject({
			q: 'Entry', match: 'exact', added_by: 'me', from: '2026-01-01', to: '2026-12-31', category_id: '10'
		});
	});

	it('an owner switch closes an open bulk edit dialog', async () => {
		await renderPage();
		await fireEvent.click(rowBox(1));
		await openBulkEdit();
		sharedOwnerUserId.set('2');
		await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
		expect(selectedCount()).toBeNull();
		expect(batch.updateTransactions).not.toHaveBeenCalled();
	});
});

describe('search and filter params', () => {
	it('sends match=exact only with a non-blank search', async () => {
		await renderPage();
		let calls = listCalls().length;
		await fireEvent.click(screen.getByRole('checkbox', { name: 'Exact match' }));
		// Nothing changes for a blank search, so nothing is refetched.
		expect(listCalls().length).toBe(calls);

		await fireEvent.input(screen.getByLabelText('Search'), { target: { value: '   ' } });
		await settle(++calls);
		expect(lastListCall().match).toBeUndefined();

		await fireEvent.input(screen.getByLabelText('Search'), { target: { value: 'Stipendio' } });
		await settle(++calls);
		expect(lastListCall()).toMatchObject({ q: 'Stipendio', match: 'exact' });

		await fireEvent.click(screen.getByRole('checkbox', { name: 'Exact match' }));
		await settle(++calls);
		expect(lastListCall().q).toBe('Stipendio');
		expect(lastListCall().match).toBeUndefined();
	});

	it('offers "Added by me" only with a shared account, and sends added_by=me when ticked', async () => {
		await renderPage();
		expect(screen.queryByRole('checkbox', { name: 'Added by me' })).toBeNull();
		for (const params of listCalls()) expect(params.added_by).toBeUndefined();
		cleanup();

		vi.mocked(accounts.list).mockResolvedValue([wallet, family] as any);
		await renderPage();
		let calls = listCalls().length;
		await fireEvent.click(screen.getByRole('checkbox', { name: 'Added by me' }));
		await settle(++calls);
		expect(lastListCall().added_by).toBe('me');
		await fireEvent.click(screen.getByRole('checkbox', { name: 'Added by me' }));
		await settle(++calls);
		expect(lastListCall().added_by).toBeUndefined();
	});

	it('turns "Added by me" off and refetches when no shared account remains', async () => {
		vi.mocked(accounts.list).mockResolvedValueOnce([wallet, family] as any).mockResolvedValue([wallet] as any);
		await renderPage();
		await fireEvent.click(screen.getByRole('checkbox', { name: 'Added by me' }));
		await waitFor(() => expect(lastListCall().added_by).toBe('me'));
		sharedOwnerUserId.set('2');
		await waitFor(() => expect(lastListCall()).toMatchObject({ owner_id: '2' }));
		await waitFor(() => expect(lastListCall().added_by).toBeUndefined());
		expect(screen.queryByRole('checkbox', { name: 'Added by me' })).toBeNull();
	});

	it('exports with the same filters as the list', async () => {
		vi.mocked(accounts.list).mockResolvedValue([wallet, family] as any);
		await renderPage();
		let calls = listCalls().length;
		await fireEvent.input(screen.getByLabelText('Search'), { target: { value: 'Stipendio' } });
		await settle(++calls);
		await fireEvent.click(screen.getByRole('checkbox', { name: 'Exact match' }));
		await settle(++calls);
		await fireEvent.click(screen.getByRole('checkbox', { name: 'Added by me' }));
		await settle(++calls);
		const { csv } = await import('./api/client');
		await fireEvent.click(screen.getByRole('button', { name: 'Export CSV' }));
		expect(csv.exportTransactions).toHaveBeenCalledWith(withoutPaging(lastListCall()));
		expect(vi.mocked(csv.exportTransactions).mock.calls[0][0]).toMatchObject({ q: 'Stipendio', match: 'exact', added_by: 'me' });
	});
});

describe('select all matching', () => {
	async function selectAllLoaded() {
		await fireEvent.click(screen.getByRole('checkbox', { name: 'Select all loaded entries' }));
	}

	it('pages with limit 500 and the very same filters, then edits every matching entry', async () => {
		data = rows(1234);
		vi.mocked(accounts.list).mockResolvedValue([wallet, family] as any);
		vi.mocked(batch.updateTransactions).mockResolvedValue({ ...OK, updated: 1234 });
		await renderPage();
		let calls = listCalls().length;
		await fireEvent.input(screen.getByLabelText('Search'), { target: { value: 'Entry' } });
		await settle(++calls);
		await fireEvent.click(screen.getByRole('checkbox', { name: 'Exact match' }));
		await settle(++calls);
		await fireEvent.click(screen.getByRole('checkbox', { name: 'Added by me' }));
		await settle(++calls);
		await fireEvent.change(screen.getByLabelText('Category'), { target: { value: '10' } });
		await settle(++calls);
		await setDate('From', '2025-01-01');
		await settle(++calls);
		await setDate('To', '2026-12-31');
		await settle(++calls);
		const listed = lastListCall();
		expect(listed).toMatchObject({
			q: 'Entry', match: 'exact', added_by: 'me', category_id: '10', from: '2025-01-01', to: '2026-12-31', limit: '50'
		});

		await selectAllLoaded();
		expect(selectedCount()).toBe('50 selected');
		await fireEvent.click(screen.getByRole('button', { name: 'Select all matching entries' }));
		await waitFor(() => expect(screen.getByText('All 1234 matching entries selected')).toBeTruthy());
		expect(selectedCount()).toBe('1234 selected');

		const pages = listCalls().slice(calls);
		expect(pages.map(p => [p.limit, p.offset])).toEqual([['500', '0'], ['500', '500'], ['500', '1000']]);
		for (const params of pages) expect(withoutPaging(params)).toStrictEqual(withoutPaging(listed));

		await openBulkEdit();
		await fireEvent.click(screen.getByRole('checkbox', { name: 'Change name' }));
		await fireEvent.input(screen.getByRole('textbox', { name: 'New name' }), { target: { value: 'Stipendio' } });
		await fireEvent.click(screen.getByRole('button', { name: 'Apply to 1234 entries' }));
		await waitFor(() => expect(batch.updateTransactions).toHaveBeenCalledTimes(1));
		expect(vi.mocked(batch.updateTransactions).mock.calls[0][0]).toStrictEqual({
			ids: data.map(r => r.id), description: 'Stipendio'
		});
	});

	it.each([
		['typing a search', () => fireEvent.input(screen.getByLabelText('Search'), { target: { value: 'Rent' } })],
		['changing a filter', () => setDate('From', '2026-01-05')],
		['changing the selection', () => fireEvent.click(rowBox(1))]
	])('discards a result that arrives after %s', async (_label, change) => {
		data = rows(120);
		let release!: () => void;
		hold = params => params.limit === '500'
			? new Promise(resolve => release = () => resolve(data))
			: undefined;
		await renderPage();
		await selectAllLoaded();
		await fireEvent.click(screen.getByRole('button', { name: 'Select all matching entries' }));
		await waitFor(() => expect(release).toBeTypeOf('function'));
		await change();
		release();
		// Right away (before any debounced reload could mask it) and after it.
		for (const wait of [0, 400]) {
			await new Promise(resolve => setTimeout(resolve, wait));
			expect(screen.queryByText(/matching entries selected/)).toBeNull();
			expect(selectedCount()).not.toBe('120 selected');
		}
	});

	it('asks to narrow the search above 5000 matches and keeps the loaded selection', async () => {
		data = rows(5001);
		await renderPage();
		await selectAllLoaded();
		await fireEvent.click(screen.getByRole('button', { name: 'Select all matching entries' }));
		await waitFor(() => expect(screen.getByText(/Narrow the search/)).toBeTruthy());
		expect(selectedCount()).toBe('50 selected');
		expect(listCalls().filter(p => p.limit === '500')).toHaveLength(11);
	});

	it('is offered only when every loaded entry is selected and more exist', async () => {
		data = rows(3);
		await renderPage();
		await selectAllLoaded();
		expect(screen.queryByRole('button', { name: 'Select all matching entries' })).toBeNull();
		cleanup();
		data = rows(60);
		await renderPage();
		await fireEvent.click(rowBox(1));
		expect(screen.queryByRole('button', { name: 'Select all matching entries' })).toBeNull();
		await selectAllLoaded();
		expect(screen.getByRole('button', { name: 'Select all matching entries' })).toBeTruthy();
	});
});

describe('a stale list cannot be acted on', () => {
	const tableWrap = () => document.querySelector('.table-wrap') as HTMLElement;
	const headerBox = () => screen.getByRole('checkbox', { name: 'Select all loaded entries' }) as HTMLInputElement;
	const loadMoreButton = () => screen.getByRole('button', { name: 'Load More' }) as HTMLButtonElement;

	function expectInert(inert: boolean) {
		expect(tableWrap().getAttribute('aria-busy')).toBe(String(inert));
		expect(tableWrap().classList.contains('stale')).toBe(inert);
		expect(headerBox().disabled).toBe(inert);
		expect((rowBox(1) as HTMLInputElement).disabled).toBe(inert);
		expect(loadMoreButton().disabled).toBe(inert);
	}

	// jsdom still dispatches events to disabled controls, so this also proves
	// the handlers refuse a stale list on their own.
	async function tryToActOnIt() {
		await fireEvent.click(rowBox(1));
		await fireEvent.click(headerBox());
		await fireEvent.click(loadMoreButton());
	}

	it('dims the old rows while a reload is in flight; nothing can select or page them', async () => {
		data = rows(60);
		await renderPage();
		expectInert(false);
		await fireEvent.click(rowBox(1));
		let release = () => {};
		hold = params => params.category_id === '10'
			? new Promise(resolve => release = () => resolve(data.slice(0, 50)))
			: undefined;
		const before = listCalls().length;
		await fireEvent.change(screen.getByLabelText('Category'), { target: { value: '10' } });
		expect(selectedCount()).toBeNull();
		expectInert(true);

		await tryToActOnIt();
		expect(selectedCount()).toBeNull();
		expect(screen.queryByRole('button', { name: 'Edit…' })).toBeNull();
		expect(listCalls().length).toBe(before + 1);

		release();
		await waitFor(() => expectInert(false));
		await fireEvent.click(rowBox(1));
		expect(selectedCount()).toBe('1 selected');
	});

	it('keeps the old rows inert after the reload fails, with the error and Retry; Load More cannot mix filters', async () => {
		data = rows(60);
		await renderPage();
		hold = params => params.category_id === '10' ? Promise.reject(new Error('Network down')) : undefined;
		const before = listCalls().length;
		await fireEvent.change(screen.getByLabelText('Category'), { target: { value: '10' } });
		await waitFor(() => expect(screen.getByRole('alert').textContent).toBe('Network down'));
		expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy();
		// The previous filter's rows are still listed, but nothing acts on them.
		expect(screen.getByText('Entry 1')).toBeTruthy();
		expectInert(true);

		await tryToActOnIt();
		expect(selectedCount()).toBeNull();
		// No page of the new filter was fetched at the old list's offset.
		expect(listCalls().length).toBe(before + 1);
		expect(listCalls().some(p => p.offset !== undefined)).toBe(false);

		hold = null;
		await fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
		await waitFor(() => expectInert(false));
		expect(screen.queryByRole('alert')).toBeNull();
		await fireEvent.click(loadMoreButton());
		await waitFor(() => expect(lastListCall()).toMatchObject({ category_id: '10', offset: '50' }));
	});

	it('closes a batch-delete confirmation when a reload clears the selection; nothing is deleted', async () => {
		await renderPage();
		await fireEvent.click(rowBox(2));
		await fireEvent.click(rowBox(3));
		await fireEvent.click(screen.getByRole('button', { name: 'Delete Selected' }));
		expect(screen.getByRole('dialog').textContent).toContain('Delete 2 transaction(s)?');
		sharedOwnerUserId.set('2');
		await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
		expect(selectedCount()).toBeNull();
		expect(batch.deleteTransactions).not.toHaveBeenCalled();
	});

	it('keeps a running batch delete open until it finishes, and a single-entry delete across a reload', async () => {
		let finish!: () => void;
		vi.mocked(batch.deleteTransactions).mockImplementation(() => new Promise<void>(resolve => finish = resolve));
		await renderPage();
		await fireEvent.click(rowBox(2));
		await fireEvent.click(rowBox(3));
		await fireEvent.click(screen.getByRole('button', { name: 'Delete Selected' }));
		await fireEvent.click(screen.getByRole('button', { name: 'Delete' }));
		expect(batch.deleteTransactions).toHaveBeenCalledWith([2, 3]);
		sharedOwnerUserId.set('2');
		await waitFor(() => expect(lastListCall()).toMatchObject({ owner_id: '2' }));
		expect(screen.getByRole('dialog').textContent).toContain('Working…');
		finish();
		await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

		// A single-entry confirmation names one entry, not the selection.
		await waitFor(() => expect(screen.getAllByRole('button', { name: 'Del' }).length).toBe(3));
		await fireEvent.click(screen.getAllByRole('button', { name: 'Del' })[0]);
		sharedOwnerUserId.set('3');
		await waitFor(() => expect(lastListCall()).toMatchObject({ owner_id: '3' }));
		expect(screen.getByRole('dialog').textContent).toContain('Delete this transaction?');
	});
});

describe('bulk edit dialog sessions', () => {
	it("reports a request whose dialog a reload closed through a toast; Edit… waits for it, and the next dialog starts clean", async () => {
		let fail!: (e: Error) => void;
		vi.mocked(batch.updateTransactions).mockImplementation(() => new Promise((_resolve, reject) => fail = reject));
		await renderPage();
		await fireEvent.click(rowBox(1));
		await openBulkEdit();
		await fireEvent.click(screen.getByRole('checkbox', { name: 'Change name' }));
		await fireEvent.click(screen.getByRole('button', { name: 'Apply to 1 entry' }));
		await waitFor(() => expect(batch.updateTransactions).toHaveBeenCalledTimes(1));

		sharedOwnerUserId.set('2');
		await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
		await waitFor(() => expect((rowBox(1) as HTMLInputElement).disabled).toBe(false));
		await fireEvent.click(rowBox(1));
		const edit = screen.getByRole('button', { name: 'Edit…' }) as HTMLButtonElement;
		expect(edit.disabled).toBe(true);
		await fireEvent.click(edit);
		expect(screen.queryByRole('dialog')).toBeNull();

		fail(new Error('category not found'));
		await waitFor(() => expect(addToast).toHaveBeenCalledWith('category not found', 'error', 6000));
		await waitFor(() => expect(edit.disabled).toBe(false));
		const dialog = await openBulkEdit();
		expect(dialog.querySelector('[role="alert"]')).toBeNull();
		expect(screen.getByRole('button', { name: 'Apply to 1 entry' })).toBeTruthy();
		expect((screen.getByRole('button', { name: 'Cancel' }) as HTMLButtonElement).disabled).toBe(false);
		expect((screen.getByRole('checkbox', { name: 'Change name' }) as HTMLInputElement).checked).toBe(false);
		expect(batch.updateTransactions).toHaveBeenCalledTimes(1);
	});

	it('shows a failure inline, without a toast, while its dialog is still open', async () => {
		vi.mocked(batch.updateTransactions).mockRejectedValue(new Error('category not found'));
		await renderPage();
		await fireEvent.click(rowBox(1));
		await openBulkEdit();
		await fireEvent.click(screen.getByRole('checkbox', { name: 'Change name' }));
		await fireEvent.click(screen.getByRole('button', { name: 'Apply to 1 entry' }));
		await waitFor(() => expect(screen.getByRole('alert').textContent).toBe('category not found'));
		expect(addToast).not.toHaveBeenCalled();
	});
});

describe('the select-all banner', () => {
	it('does not claim only the loaded entries are selected when more are', async () => {
		data = rows(120);
		await renderPage();
		await fireEvent.click(screen.getByRole('checkbox', { name: 'Select all loaded entries' }));
		expect(screen.getByText('All 50 loaded entries are selected.')).toBeTruthy();
		await fireEvent.click(screen.getByRole('button', { name: 'Select all matching entries' }));
		await waitFor(() => expect(screen.getByText('All 120 matching entries selected')).toBeTruthy());
		await fireEvent.click(rowBox(1));
		expect(selectedCount()).toBe('119 selected');
		await fireEvent.click(rowBox(1));
		expect(selectedCount()).toBe('120 selected');
		expect(screen.queryByText(/loaded entries are selected/)).toBeNull();
		expect(screen.queryByRole('button', { name: 'Select all matching entries' })).toBeNull();
	});
});

describe('linked schedules when opening the bulk edit', () => {
	const sched = (id: number) => ({ id, user_id: 1, type: 'expense', rrule: 'FREQ=MONTHLY', deleted_at: null }) as any;

	it('refetches schedules first when a selected entry links one that is not loaded, so the count is complete', async () => {
		data = [row(1, { scheduled_id: 7 }), row(2, { scheduled_id: 8 }), row(3)];
		vi.mocked(scheduled.list).mockResolvedValueOnce([sched(8)]).mockResolvedValue([sched(7), sched(8)]);
		await renderPage();
		expect(scheduled.list).toHaveBeenCalledTimes(1);
		await fireEvent.click(screen.getByRole('checkbox', { name: 'Select all loaded entries' }));
		await openBulkEdit();
		expect(scheduled.list).toHaveBeenCalledTimes(2);
		expect(vi.mocked(scheduled.list).mock.calls[1][0]).toMatchObject({ include_deleted: '1' });
		const option = screen.getByRole('checkbox', { name: 'Also update 2 linked recurring schedules' }) as HTMLInputElement;
		expect(option.disabled).toBe(false);
		expect(screen.queryByText(/couldn't be loaded/)).toBeNull();

		// Everything is loaded now: reopening doesn't fetch again.
		await fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
		await openBulkEdit();
		expect(scheduled.list).toHaveBeenCalledTimes(2);
	});

	it.each([
		['the refetch still lacks one', () => vi.mocked(scheduled.list).mockResolvedValue([sched(8)])],
		['the refetch fails', () => vi.mocked(scheduled.list).mockResolvedValueOnce([sched(8)]).mockRejectedValue(new Error('offline'))]
	])('disables the schedule option with the reason when %s, and never sends it', async (_label, mock) => {
		data = [row(1, { scheduled_id: 7 }), row(2, { scheduled_id: 8 })];
		mock();
		vi.mocked(batch.updateTransactions).mockResolvedValue(OK);
		await renderPage();
		await fireEvent.click(screen.getByRole('checkbox', { name: 'Select all loaded entries' }));
		await openBulkEdit();
		expect(scheduled.list).toHaveBeenCalledTimes(2);
		const option = screen.getByRole('checkbox', { name: 'Also update linked recurring schedules' }) as HTMLInputElement;
		expect(option.disabled).toBe(true);
		expect(screen.getByText("Some linked recurring schedules couldn't be loaded.")).toBeTruthy();
		// The loaded schedule alone must not be presented as the whole count.
		expect(screen.queryByRole('checkbox', { name: /Also update 1 linked/ })).toBeNull();
		await fireEvent.click(option);
		await fireEvent.click(screen.getByRole('checkbox', { name: 'Change name' }));
		await fireEvent.input(screen.getByRole('textbox', { name: 'New name' }), { target: { value: 'Stipendio' } });
		await fireEvent.click(screen.getByRole('button', { name: 'Apply to 2 entries' }));
		await waitFor(() => expect(batch.updateTransactions).toHaveBeenCalledTimes(1));
		expect(vi.mocked(batch.updateTransactions).mock.calls[0][0]).toStrictEqual({ ids: [1, 2], description: 'Stipendio' });
	});
});
