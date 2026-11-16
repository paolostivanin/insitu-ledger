import { afterEach, expect, it } from 'vitest';
import { render, screen, cleanup } from '@testing-library/svelte';
import TransactionName from './TransactionName.svelte';

afterEach(cleanup);

const rent = { id: 1, description: 'Rent', scheduled_id: 4, occurrence: 2, occurrence_total: 3 } as any;

it('renders an ordinary entry exactly as before', () => {
	const { container } = render(TransactionName, { txn: { id: 2, description: 'Coffee' } as any, schedules: new Map() });
	expect(container.textContent).toBe('Coffee');
	expect(screen.queryByRole('img')).toBeNull();
});

it('marks a scheduler-generated entry with a badge', () => {
	const schedules = new Map([[4, { id: 4, rrule: 'FREQ=MONTHLY', max_occurrences: 3 } as any]]);
	render(TransactionName, { txn: rent, schedules });
	expect(screen.getByText('Rent')).toBeTruthy();
	expect(screen.getByRole('img', { name: 'Recurring monthly, 2 of 3' })).toBeTruthy();
	expect(screen.getByText('Monthly · 2/3')).toBeTruthy();
});

it('shows ∞ for an open-ended series and Once for a one-time schedule', () => {
	const open = { id: 5, description: 'Cleaning', scheduled_id: 6, occurrence: 9, occurrence_total: null } as any;
	const once = { id: 7, description: 'Car service', scheduled_id: 8, occurrence: 1, occurrence_total: 1 } as any;
	const schedules = new Map([[6, { id: 6, rrule: 'FREQ=WEEKLY', max_occurrences: null } as any]]);
	render(TransactionName, { txn: open, schedules });
	expect(screen.getByText('Weekly · ∞')).toBeTruthy();
	expect(screen.getByRole('img', { name: 'Recurring weekly, no end date' })).toBeTruthy();
	cleanup();
	render(TransactionName, { txn: once, schedules: new Map() });
	expect(screen.getByText('Once')).toBeTruthy();
	expect(screen.getByRole('img', { name: 'Scheduled once' })).toBeTruthy();
});

it('falls back to Recurring until the schedule is known', async () => {
	const { rerender } = render(TransactionName, { txn: rent, schedules: new Map() });
	expect(screen.getByText('Recurring · 2/3')).toBeTruthy();
	await rerender({ txn: rent, schedules: new Map([[4, { id: 4, rrule: 'FREQ=WEEKLY;INTERVAL=2', max_occurrences: 3 } as any]]) });
	expect(screen.getByText('Biweekly · 2/3')).toBeTruthy();
});
