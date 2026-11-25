import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import BulkEditDialog from './BulkEditDialog.svelte';
import type { Category, ScheduledTransaction, Transaction } from '../api/client';
import { MIXED_OWNERS_REASON, MIXED_TYPES_REASON, SCHEDULES_UNAVAILABLE_REASON, SCHEDULE_TYPE_CONFLICT } from '../bulkEdit';

afterEach(cleanup);

function tx(id: number, over: Partial<Transaction> = {}): Transaction {
	return {
		id, account_id: 1, category_id: 12, user_id: 1, type: 'expense', amount: 5, currency: 'EUR',
		description: 'Stipendio', note: null, date: '2026-01-01', created_at: '', updated_at: '',
		sync_version: 1, created_by_user_id: 1, created_by_name: 'Owner', ...over
	};
}

function cat(id: number, name: string, over: Partial<Category> = {}): Category {
	return {
		id, user_id: 1, parent_id: null, name, type: 'expense', icon: null, color: null,
		created_at: '', updated_at: '', sync_version: 1, ...over
	};
}

function sched(id: number, over: Partial<ScheduledTransaction> = {}): ScheduledTransaction {
	return {
		id, account_id: 1, category_id: 12, user_id: 1, type: 'expense', amount: 5, currency: 'EUR',
		description: 'Stipendio', note: null, rrule: 'FREQ=MONTHLY', next_occurrence: '2026-02-01',
		active: true, max_occurrences: null, occurrence_count: 1, created_at: '', updated_at: '',
		deleted_at: null, sync_version: 1, created_by_user_id: 1, created_by_name: 'Owner', ...over
	};
}

const cats: Category[] = [
	cat(10, 'Food'),
	cat(11, 'Groceries', { parent_id: 10 }),
	cat(12, 'Rent'),
	cat(20, 'Salary', { type: 'income' }),
	cat(30, 'Partner food', { user_id: 2 })
];

function setup(
	rows: Transaction[],
	over: { schedules?: ScheduledTransaction[]; schedulesIncomplete?: boolean; onapply?: (input: unknown) => unknown } = {}
) {
	const onapply = vi.fn(over.onapply ?? (() => undefined));
	const schedules = new Map((over.schedules ?? []).map(s => [s.id, s]));
	const result = render(BulkEditDialog, {
		open: true, rows, cats, schedules, schedulesIncomplete: over.schedulesIncomplete, onapply: onapply as any
	});
	return { onapply, ...result };
}

const applyButton = () => screen.getByRole('button', { name: /^Apply to|^Working/ }) as HTMLButtonElement;
const cancelButton = () => screen.getByRole('button', { name: 'Cancel' }) as HTMLButtonElement;
const checkbox = (name: string | RegExp) => screen.getByRole('checkbox', { name }) as HTMLInputElement;
const nameInput = () => screen.getByRole('textbox', { name: 'New name' }) as HTMLInputElement;
const picker = () => screen.getByRole('combobox', { name: 'Category to apply' }) as HTMLSelectElement;
// Option labels collapse whitespace when displayed.
const label = (option: HTMLOptionElement | undefined) => option?.textContent?.replace(/\s+/g, ' ').trim();
const shownCategory = () => label(picker().selectedOptions[0]);

describe('gating and payload', () => {
	it('keeps Apply disabled until a valid change is opted into', async () => {
		setup([tx(1), tx(2)]);
		expect(applyButton().textContent?.trim()).toBe('Apply to 2 entries');
		expect(applyButton().disabled).toBe(true);
		await fireEvent.click(checkbox('Change name'));
		// Prefilled with the name the entries share.
		expect(nameInput().value).toBe('Stipendio');
		expect(applyButton().disabled).toBe(false);
		await fireEvent.input(nameInput(), { target: { value: '   ' } });
		expect(applyButton().disabled).toBe(true);
		expect(screen.getByText('Enter a name.')).toBeTruthy();
	});

	it('sends exactly the selected ids and only the name when only the name is ticked', async () => {
		const { onapply } = setup([tx(3), tx(1), tx(2)]);
		await fireEvent.click(checkbox('Change name'));
		await fireEvent.input(nameInput(), { target: { value: '  Salary ' } });
		await fireEvent.click(applyButton());
		expect(onapply).toHaveBeenCalledTimes(1);
		expect(onapply.mock.calls[0][0]).toStrictEqual({ ids: [1, 2, 3], description: 'Salary' });
		await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
	});

	it('limits the name to 500 bytes, not 500 characters', async () => {
		setup([tx(1)]);
		await fireEvent.click(checkbox('Change name'));
		await fireEvent.input(nameInput(), { target: { value: 'è'.repeat(250) } });
		expect(applyButton().disabled).toBe(false);
		await fireEvent.input(nameInput(), { target: { value: 'è'.repeat(251) } });
		expect(applyButton().disabled).toBe(true);
		expect(screen.getByText(/502\/500 bytes/)).toBeTruthy();
	});

	it('sends the category that is displayed, and only that field', async () => {
		const { onapply } = setup([tx(1), tx(2)]);
		await fireEvent.click(checkbox('Change category'));
		// Starts on the category the entries share.
		expect(picker().value).toBe('12');
		expect(shownCategory()).toBe('Rent');
		await fireEvent.change(picker(), { target: { value: '11' } });
		expect(shownCategory()).toBe('↳ Groceries');
		await fireEvent.click(applyButton());
		expect(onapply.mock.calls[0][0]).toStrictEqual({ ids: [1, 2], category_id: 11 });
	});

	it("offers only the owner's categories of the entries' type, without creating new ones", async () => {
		setup([tx(1)]);
		await fireEvent.click(checkbox('Change category'));
		const labels = [...picker().options].map(label);
		expect(labels).toEqual(['Food', '↳ Groceries', 'Rent']);
		expect([...picker().options].some(o => o.value === '__new__')).toBe(false);
		expect(screen.queryByText(/\+ New category/)).toBeNull();
	});

	it('starts on a real option when the entries share no category, and sends what it shows', async () => {
		const { onapply } = setup([tx(1, { category_id: 12 }), tx(2, { category_id: 10 })]);
		await fireEvent.click(checkbox('Change category'));
		expect(shownCategory()).toBe('Food');
		expect(picker().value).toBe('10');
		await fireEvent.click(applyButton());
		expect(onapply.mock.calls[0][0]).toStrictEqual({ ids: [1, 2], category_id: 10 });
	});

	it.each([
		['mix income and expense', [tx(1), tx(2, { type: 'income', category_id: 20 })], MIXED_TYPES_REASON],
		['span owners', [tx(1), tx(2, { user_id: 2, category_id: 30 })], MIXED_OWNERS_REASON]
	])('disables the category change with a reason when the entries %s', async (_label, rows, reason) => {
		const { onapply } = setup(rows);
		expect(checkbox('Change category').disabled).toBe(true);
		expect(screen.getByText(reason)).toBeTruthy();
		expect(screen.queryByRole('combobox')).toBeNull();
		// A rename is still possible and carries no category.
		await fireEvent.click(checkbox('Change name'));
		await fireEvent.click(applyButton());
		expect(onapply.mock.calls[0][0]).toStrictEqual({ ids: [1, 2], description: 'Stipendio' });
	});
});

describe('linked schedules', () => {
	it('hides the option when no live schedule is linked', () => {
		setup([tx(1, { scheduled_id: 5 }), tx(2, { scheduled_id: 6 })], {
			schedules: [sched(5, { deleted_at: '2026-03-01T00:00:00Z' })] // 6 is not loaded at all
		});
		expect(screen.queryByRole('checkbox', { name: /Also update/ })).toBeNull();
	});

	it('counts live schedules, starts unticked and sends update_schedules only when ticked', async () => {
		const { onapply } = setup(
			[tx(1, { scheduled_id: 5 }), tx(2, { scheduled_id: 5 }), tx(3, { scheduled_id: 6 }), tx(4, { scheduled_id: 7 })],
			{ schedules: [sched(5), sched(6, { active: false }), sched(7, { deleted_at: '2026-03-01T00:00:00Z' })] }
		);
		const option = checkbox('Also update 2 linked recurring schedules');
		expect(option.checked).toBe(false);
		await fireEvent.click(checkbox('Change name'));
		await fireEvent.click(applyButton());
		expect(onapply.mock.calls[0][0]).toStrictEqual({ ids: [1, 2, 3, 4], description: 'Stipendio' });

		cleanup();
		const second = setup([tx(1, { scheduled_id: 5 })], { schedules: [sched(5)] });
		await fireEvent.click(checkbox('Also update 1 linked recurring schedule'));
		await fireEvent.click(checkbox('Change name'));
		await fireEvent.click(applyButton());
		expect(second.onapply.mock.calls[0][0]).toStrictEqual({ ids: [1], description: 'Stipendio', update_schedules: true });
	});

	it('disables the option with the reason while some linked schedules are not loaded, and never sends it', async () => {
		const { onapply } = setup([tx(1, { scheduled_id: 5 }), tx(2, { scheduled_id: 6 })], {
			schedules: [sched(5)], schedulesIncomplete: true
		});
		const option = checkbox('Also update linked recurring schedules');
		expect(option.disabled).toBe(true);
		expect(option.checked).toBe(false);
		expect(screen.getByText(SCHEDULES_UNAVAILABLE_REASON)).toBeTruthy();
		// The one loaded schedule is not offered as if it were all of them.
		expect(screen.queryByRole('checkbox', { name: /Also update 1 linked/ })).toBeNull();
		await fireEvent.click(option);
		await fireEvent.click(checkbox('Change name'));
		await fireEvent.click(applyButton());
		expect(onapply.mock.calls[0][0]).toStrictEqual({ ids: [1, 2], description: 'Stipendio' });
	});

	it('drops an already ticked schedule option once the schedules turn out incomplete', async () => {
		const { onapply, rerender } = setup([tx(1, { scheduled_id: 5 })], { schedules: [sched(5)] });
		await fireEvent.click(checkbox('Also update 1 linked recurring schedule'));
		await fireEvent.click(checkbox('Change name'));
		await rerender({ schedulesIncomplete: true });
		expect(checkbox('Also update linked recurring schedules').checked).toBe(false);
		await fireEvent.click(applyButton());
		expect(onapply.mock.calls[0][0]).toStrictEqual({ ids: [1], description: 'Stipendio' });
	});

	it('blocks Apply when a linked schedule has another type than the new category', async () => {
		const { onapply } = setup([tx(1, { scheduled_id: 5 })], { schedules: [sched(5, { type: 'income' })] });
		await fireEvent.click(checkbox('Change category'));
		await fireEvent.click(checkbox(/Also update/));
		expect(applyButton().disabled).toBe(true);
		expect(screen.getByText(SCHEDULE_TYPE_CONFLICT)).toBeTruthy();
		await fireEvent.click(checkbox(/Also update/));
		expect(applyButton().disabled).toBe(false);
		expect(screen.queryByText(SCHEDULE_TYPE_CONFLICT)).toBeNull();
		// A rename alone can still reach the schedule.
		await fireEvent.click(checkbox('Change category'));
		await fireEvent.click(checkbox(/Also update/));
		await fireEvent.click(checkbox('Change name'));
		await fireEvent.click(applyButton());
		expect(onapply.mock.calls[0][0]).toStrictEqual({ ids: [1], description: 'Stipendio', update_schedules: true });
	});
});

describe('dialog behaviour', () => {
	it('keeps the dialog open with the error and lets the user retry the same request', async () => {
		const onapply = vi.fn()
			.mockRejectedValueOnce(new Error('category type does not match the selected entries'))
			.mockResolvedValueOnce(undefined);
		setup([tx(1), tx(2)], { onapply });
		await fireEvent.click(checkbox('Change name'));
		await fireEvent.click(applyButton());
		await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('category type does not match'));
		expect(screen.getByRole('dialog')).toBeTruthy();
		expect(applyButton().disabled).toBe(false);
		await fireEvent.click(applyButton());
		await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
		expect(onapply).toHaveBeenCalledTimes(2);
		expect(onapply.mock.calls[1][0]).toStrictEqual(onapply.mock.calls[0][0]);
	});

	it('sends once while busy and cannot be dismissed mid-request', async () => {
		let finish!: () => void;
		const { onapply } = setup([tx(1)], { onapply: () => new Promise<void>(resolve => finish = resolve) });
		const dialog = screen.getByRole('dialog');
		vi.spyOn(dialog, 'getBoundingClientRect').mockReturnValue({ left: 100, right: 400, top: 100, bottom: 300 } as DOMRect);
		await fireEvent.click(checkbox('Change name'));
		await fireEvent.click(applyButton());
		await fireEvent.click(applyButton());
		expect(onapply).toHaveBeenCalledTimes(1);
		expect(applyButton().textContent?.trim()).toBe('Working…');
		expect((screen.getByRole('button', { name: 'Cancel' }) as HTMLButtonElement).disabled).toBe(true);
		await fireEvent.click(dialog, { clientX: 10, clientY: 10 });
		expect(screen.getByRole('dialog')).toBeTruthy();
		finish();
		await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
	});

	it('ignores a second submit while busy, even one that bypasses the disabled button', async () => {
		let finish!: () => void;
		const { onapply } = setup([tx(1)], { onapply: () => new Promise<void>(resolve => finish = resolve) });
		await fireEvent.click(checkbox('Change name'));
		const form = screen.getByRole('dialog').querySelector('form')!;
		await fireEvent.submit(form);
		await fireEvent.submit(form);
		expect(onapply).toHaveBeenCalledTimes(1);
		finish();
		await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
	});

	it.each(['succeeds', 'fails'])(
		"a reopened dialog starts clean, and an earlier session's request that %s never reaches it",
		async outcome => {
			const pending: Array<{ resolve: () => void; reject: (e: Error) => void }> = [];
			const { onapply, rerender } = setup([tx(1)], {
				onapply: () => new Promise<void>((resolve, reject) => pending.push({ resolve, reject }))
			});
			await fireEvent.click(checkbox('Change name'));
			await fireEvent.click(applyButton());
			expect(applyButton().textContent?.trim()).toBe('Working…');

			// Closed by the page mid-request (a reload), then opened again.
			await rerender({ open: false });
			await rerender({ open: true });
			expect(applyButton().textContent?.trim()).toBe('Apply to 1 entry');
			expect(cancelButton().disabled).toBe(false);
			expect(checkbox('Change name').checked).toBe(false);
			expect(checkbox('Change name').disabled).toBe(false);

			// This session sends its own request; then the old one settles.
			await fireEvent.click(checkbox('Change name'));
			await fireEvent.click(applyButton());
			expect(onapply).toHaveBeenCalledTimes(2);
			if (outcome === 'fails') pending[0].reject(new Error('old failure'));
			else pending[0].resolve();
			await new Promise(resolve => setTimeout(resolve, 0));
			expect(screen.getByRole('dialog')).toBeTruthy();
			expect(screen.queryByRole('alert')).toBeNull();
			expect(applyButton().textContent?.trim()).toBe('Working…');

			pending[1].resolve();
			await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
		}
	);

	it('closes on Cancel without applying, and reopens with nothing ticked', async () => {
		const { onapply, rerender } = setup([tx(1)]);
		await fireEvent.click(checkbox('Change name'));
		await fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
		expect(screen.queryByRole('dialog')).toBeNull();
		await rerender({ open: true });
		expect(checkbox('Change name').checked).toBe(false);
		expect(applyButton().disabled).toBe(true);
		expect(onapply).not.toHaveBeenCalled();
	});
});
