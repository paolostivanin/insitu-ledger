import { describe, it, expect } from 'vitest';
import { resolvePeriod } from './reportPeriod';

// Local-calendar constructor throughout — resolvePeriod deliberately works in
// local time, so building fixtures from UTC strings would test the wrong thing.
const wed = new Date(2026, 8, 2); // Wed 2026-09-02
const sun = new Date(2026, 8, 6); // Sun 2026-09-06
const mon = new Date(2026, 8, 7); // Mon 2026-09-07

describe('resolvePeriod', () => {
	it('this_week starts on Monday', () => {
		expect(resolvePeriod('this_week', '', '', wed)).toEqual({ from: '2026-08-31', to: '2026-09-02' });
	});

	it('this_week treats Sunday as the end of the week, not the start', () => {
		expect(resolvePeriod('this_week', '', '', sun)).toEqual({ from: '2026-08-31', to: '2026-09-06' });
	});

	it('this_week on a Monday starts that same day', () => {
		expect(resolvePeriod('this_week', '', '', mon)).toEqual({ from: '2026-09-07', to: '2026-09-07' });
	});

	it('this_month runs from the 1st to today', () => {
		expect(resolvePeriod('this_month', '', '', wed)).toEqual({ from: '2026-09-01', to: '2026-09-02' });
	});

	it('this_year runs from Jan 1 to today', () => {
		expect(resolvePeriod('this_year', '', '', wed)).toEqual({ from: '2026-01-01', to: '2026-09-02' });
	});

	it('last_month covers the whole previous month', () => {
		expect(resolvePeriod('last_month', '', '', wed)).toEqual({ from: '2026-08-01', to: '2026-08-31' });
	});

	it('last_month rolls back over the year boundary', () => {
		expect(resolvePeriod('last_month', '', '', new Date(2026, 0, 15))).toEqual({
			from: '2025-12-01',
			to: '2025-12-31'
		});
	});

	it('last_month ends on the real last day of a short month', () => {
		// March -> February, non-leap.
		expect(resolvePeriod('last_month', '', '', new Date(2026, 2, 10))).toEqual({
			from: '2026-02-01',
			to: '2026-02-28'
		});
		// Leap year February.
		expect(resolvePeriod('last_month', '', '', new Date(2028, 2, 10))).toEqual({
			from: '2028-02-01',
			to: '2028-02-29'
		});
	});

	it('last_year covers the full prior calendar year', () => {
		expect(resolvePeriod('last_year', '', '', wed)).toEqual({ from: '2025-01-01', to: '2025-12-31' });
	});

	it('all returns unbounded, which the API client strips', () => {
		expect(resolvePeriod('all', '', '', wed)).toEqual({ from: '', to: '' });
	});

	it('custom passes the given bounds through, including half-open ranges', () => {
		expect(resolvePeriod('custom', '2026-01-05', '2026-02-09', wed)).toEqual({
			from: '2026-01-05',
			to: '2026-02-09'
		});
		expect(resolvePeriod('custom', '2026-01-05', '', wed)).toEqual({ from: '2026-01-05', to: '' });
	});

	it('does not shift boundaries for late-evening local times', () => {
		// 23:30 local on the 1st is already the 2nd in UTC east of Greenwich;
		// a toISOString-based implementation would report from 2026-09-02.
		const lateOnTheFirst = new Date(2026, 8, 1, 23, 30);
		expect(resolvePeriod('this_month', '', '', lateOnTheFirst)).toEqual({
			from: '2026-09-01',
			to: '2026-09-01'
		});
	});
});
