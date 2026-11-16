import { afterEach, expect, it } from 'vitest';
import { render, screen, cleanup } from '@testing-library/svelte';
import TransactionName from './TransactionName.svelte';

afterEach(cleanup);

const rent = { id: 1, description: 'Rent', scheduled_id: 4, occurrence: 2, occurrence_total: 3 } as any;

it('renders an ordinary entry exactly as before', () => {
	const { container } = render(TransactionName, { txn: { id: 2, description: 'Coffee' } as any, schedules: new Map() });
	expect(container.textContent).toBe('Coffee');
	expect(screen.queryByRole('img', { name: 'Recurring' })).toBeNull();
});

it('marks a scheduler-generated entry with the glyph and series label', () => {
	const schedules = new Map([[4, { id: 4, rrule: 'FREQ=MONTHLY', max_occurrences: 3 } as any]]);
	render(TransactionName, { txn: rent, schedules });
	expect(screen.getByText('Rent')).toBeTruthy();
	expect(screen.getByRole('img', { name: 'Recurring' })).toBeTruthy();
	expect(screen.getByText('Monthly · 2 of 3')).toBeTruthy();
});

it('falls back to Recurring until the schedule is known', async () => {
	const { rerender } = render(TransactionName, { txn: rent, schedules: new Map() });
	expect(screen.getByText('Recurring · 2 of 3')).toBeTruthy();
	await rerender({ txn: rent, schedules: new Map([[4, { id: 4, rrule: 'FREQ=WEEKLY;INTERVAL=2', max_occurrences: 3 } as any]]) });
	expect(screen.getByText('Biweekly · 2 of 3')).toBeTruthy();
});
