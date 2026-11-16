import { describe, it, expect } from 'vitest';
import { RECURRING_FALLBACK, indexSchedules, isScheduled, seriesBadge } from './series';

const monthly = { rrule: 'FREQ=MONTHLY', max_occurrences: null };
const monthlyCapped = { rrule: 'FREQ=MONTHLY', max_occurrences: 12 };
const oneTime = { rrule: 'FREQ=DAILY', max_occurrences: 1 };
const text = (...args: Parameters<typeof seriesBadge>) => seriesBadge(...args)?.text ?? null;

describe('seriesBadge', () => {
	it('returns null for ordinary entries', () => {
		expect(seriesBadge({}, monthly)).toBeNull();
		expect(seriesBadge({ scheduled_id: null, occurrence: 2, occurrence_total: 3 }, monthly)).toBeNull();
	});
	it('shows frequency and a compact counter when both are known', () => {
		const badge = seriesBadge({ scheduled_id: 7, occurrence: 2, occurrence_total: 3 }, monthlyCapped);
		expect(badge).toEqual({ text: 'Monthly · 2/3', oneTime: false, spoken: 'Recurring monthly, 2 of 3' });
		expect(text({ scheduled_id: 7, occurrence: 4, occurrence_total: 12 }, { rrule: 'FREQ=WEEKLY;INTERVAL=2', max_occurrences: 12 }))
			.toBe('Biweekly · 4/12');
	});
	it('marks open-ended series with ∞', () => {
		expect(seriesBadge({ scheduled_id: 7, occurrence: 5, occurrence_total: null }, monthly))
			.toEqual({ text: 'Monthly · ∞', oneTime: false, spoken: 'Recurring monthly, no end date' });
		expect(text({ scheduled_id: 7 }, { rrule: 'FREQ=MONTHLY;INTERVAL=2', max_occurrences: null })).toBe('Every 2 months · ∞');
	});
	it('uses the schedule to tell backfilled history apart', () => {
		expect(text({ scheduled_id: 7 }, monthly)).toBe('Monthly · ∞');
		expect(text({ scheduled_id: 7 }, monthlyCapped)).toBe('Monthly');
	});
	it('labels one-time schedules as Once', () => {
		expect(seriesBadge({ scheduled_id: 7, occurrence: 1, occurrence_total: 1 }, undefined))
			.toEqual({ text: 'Once', oneTime: true, spoken: 'Scheduled once' });
		expect(text({ scheduled_id: 7 }, oneTime)).toBe('Once');
	});
	it('does not treat a capped daily series, every-other-day cap or a monthly cap of 1 as one-time', () => {
		expect(text({ scheduled_id: 7 }, { rrule: 'FREQ=DAILY', max_occurrences: 3 })).toBe('Daily');
		expect(text({ scheduled_id: 7 }, { rrule: 'FREQ=DAILY;INTERVAL=2', max_occurrences: 1 })).toBe('Every 2 days');
		expect(text({ scheduled_id: 7 }, { rrule: 'FREQ=MONTHLY', max_occurrences: 1 })).toBe('Monthly');
	});
	it('falls back to Recurring when the schedule is unknown, keeping the counter', () => {
		expect(text({ scheduled_id: 7, occurrence: 2, occurrence_total: 3 }, undefined)).toBe(`${RECURRING_FALLBACK} · 2/3`);
		expect(seriesBadge({ scheduled_id: 7, occurrence: 2, occurrence_total: 3 }, undefined)?.spoken).toBe('Recurring, 2 of 3');
		expect(text({ scheduled_id: 7, occurrence: 4, occurrence_total: null }, undefined)).toBe('Recurring · ∞');
		expect(text({ scheduled_id: 7 }, undefined)).toBe('Recurring');
	});
	it('shows only the frequency when the position exceeds a lowered cap', () => {
		expect(text({ scheduled_id: 7, occurrence: 4, occurrence_total: 2 }, { rrule: 'FREQ=MONTHLY', max_occurrences: 2 })).toBe('Monthly');
		expect(text({ scheduled_id: 7, occurrence: 4, occurrence_total: 2 }, undefined)).toBe('Recurring');
	});
	it('treats scheduled_id 0 as scheduled', () => {
		expect(isScheduled({ scheduled_id: 0 })).toBe(true);
		expect(text({ scheduled_id: 0, occurrence: 1, occurrence_total: 2 }, monthlyCapped)).toBe('Monthly · 1/2');
	});
});

describe('indexSchedules', () => {
	it('maps schedules by id', () => {
		const map = indexSchedules([{ id: 7, rrule: 'FREQ=MONTHLY' }, { id: 9, rrule: 'FREQ=WEEKLY' }]);
		expect(map.get(9)?.rrule).toBe('FREQ=WEEKLY');
		expect(map.size).toBe(2);
	});
	it('tolerates a missing list', () => {
		expect(indexSchedules(null).size).toBe(0);
		expect(indexSchedules(undefined).size).toBe(0);
	});
});
