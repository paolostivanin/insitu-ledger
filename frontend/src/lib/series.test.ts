import { describe, it, expect } from 'vitest';
import { RECURRING_FALLBACK, indexSchedules, isScheduled, seriesLabel } from './series';

const monthly = { rrule: 'FREQ=MONTHLY', max_occurrences: null };
const oneTime = { rrule: 'FREQ=DAILY', max_occurrences: 1 };

describe('seriesLabel', () => {
	it('returns null for ordinary entries', () => {
		expect(seriesLabel({}, monthly)).toBeNull();
		expect(seriesLabel({ scheduled_id: null, occurrence: 2, occurrence_total: 3 }, monthly)).toBeNull();
	});
	it('shows frequency and position when both are known', () => {
		expect(seriesLabel({ scheduled_id: 7, occurrence: 2, occurrence_total: 3 }, monthly)).toBe('Monthly · 2 of 3');
		expect(seriesLabel({ scheduled_id: 7, occurrence: 4, occurrence_total: 12 }, { rrule: 'FREQ=WEEKLY;INTERVAL=2', max_occurrences: 12 }))
			.toBe('Biweekly · 4 of 12');
	});
	it('shows only the frequency for open-ended series', () => {
		expect(seriesLabel({ scheduled_id: 7, occurrence: 5, occurrence_total: null }, monthly)).toBe('Monthly');
		expect(seriesLabel({ scheduled_id: 7 }, { rrule: 'FREQ=MONTHLY;INTERVAL=2', max_occurrences: null })).toBe('Every 2 months');
	});
	it('shows the frequency alone for backfilled history without a position', () => {
		expect(seriesLabel({ scheduled_id: 7, occurrence: null, occurrence_total: null }, { rrule: 'FREQ=YEARLY', max_occurrences: 5 }))
			.toBe('Yearly');
	});
	it('labels one-time schedules as Scheduled', () => {
		expect(seriesLabel({ scheduled_id: 7, occurrence: 1, occurrence_total: 1 }, oneTime)).toBe('Scheduled');
		// The schedule is usually gone once its single occurrence fires.
		expect(seriesLabel({ scheduled_id: 7, occurrence: 1, occurrence_total: 1 }, undefined)).toBe('Scheduled');
		// Backfilled history has no snapshot, so the schedule decides.
		expect(seriesLabel({ scheduled_id: 7 }, oneTime)).toBe('Scheduled');
	});
	it('does not treat a capped daily series or a monthly one-off cap as one-time by rrule alone', () => {
		expect(seriesLabel({ scheduled_id: 7, occurrence: 2, occurrence_total: 5 }, { rrule: 'FREQ=DAILY', max_occurrences: 5 }))
			.toBe('Daily · 2 of 5');
		expect(seriesLabel({ scheduled_id: 7 }, { rrule: 'FREQ=MONTHLY', max_occurrences: 1 })).toBe('Monthly');
	});
	it('falls back to Recurring when the schedule is unknown', () => {
		expect(seriesLabel({ scheduled_id: 7 }, undefined)).toBe(RECURRING_FALLBACK);
		expect(seriesLabel({ scheduled_id: 7, occurrence: 2, occurrence_total: 3 }, undefined)).toBe('Recurring · 2 of 3');
	});
	it('treats scheduled_id 0 as scheduled', () => {
		expect(isScheduled({ scheduled_id: 0 })).toBe(true);
		expect(isScheduled({ scheduled_id: undefined })).toBe(false);
	});
});

describe('seriesLabel edge cases', () => {
	const monthly = { rrule: 'FREQ=MONTHLY', max_occurrences: 2 };
	it('shows only the frequency when the position exceeds a lowered cap', () => {
		expect(seriesLabel({ scheduled_id: 7, occurrence: 4, occurrence_total: 2 }, monthly)).toBe('Monthly');
		expect(seriesLabel({ scheduled_id: 7, occurrence: 4, occurrence_total: 2 }, undefined)).toBe('Recurring');
	});
	it('keeps the position when the schedule is gone', () => {
		expect(seriesLabel({ scheduled_id: 7, occurrence: 2, occurrence_total: 3 }, undefined)).toBe('Recurring · 2 of 3');
	});
	it('only treats daily interval 1 capped at 1 as one-time', () => {
		const everyOtherDay = { rrule: 'FREQ=DAILY;INTERVAL=2', max_occurrences: 1 };
		expect(seriesLabel({ scheduled_id: 7, occurrence: null, occurrence_total: null }, everyOtherDay)).toBe('Every 2 days');
	});
});

describe('indexSchedules', () => {
	it('maps schedules by id', () => {
		const map = indexSchedules([{ id: 3, ...monthly }, { id: 9, ...oneTime }]);
		expect(map.get(3)?.rrule).toBe('FREQ=MONTHLY');
		expect(map.get(9)?.max_occurrences).toBe(1);
		expect(map.get(4)).toBeUndefined();
	});
	it('tolerates a missing list', () => {
		expect(indexSchedules(null).size).toBe(0);
		expect(indexSchedules(undefined).size).toBe(0);
	});
});
