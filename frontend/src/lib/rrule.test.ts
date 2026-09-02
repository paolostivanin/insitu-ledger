import { describe, it, expect } from 'vitest';
import {
	MAX_INTERVAL,
	PRESETS,
	buildRrule,
	parseRrule,
	presetKeyFor,
	rruleLabel,
	unitLabel
} from './rrule';

// Every rrule string a shipped client has ever written. The Kotlin twin
// (android/…/util/Rrule.kt) has this same table verbatim — keep them in step.
const LEGACY_WIRE_STRINGS = [
	'FREQ=DAILY',
	'FREQ=WEEKLY',
	'FREQ=WEEKLY;INTERVAL=2',
	'FREQ=MONTHLY',
	'FREQ=MONTHLY;INTERVAL=3',
	'FREQ=YEARLY'
];

describe('parseRrule', () => {
	it('parses every preset', () => {
		expect(parseRrule('FREQ=DAILY')).toEqual({ freq: 'DAILY', interval: 1, until: '' });
		expect(parseRrule('FREQ=WEEKLY')).toEqual({ freq: 'WEEKLY', interval: 1, until: '' });
		expect(parseRrule('FREQ=WEEKLY;INTERVAL=2')).toEqual({
			freq: 'WEEKLY',
			interval: 2,
			until: ''
		});
		expect(parseRrule('FREQ=MONTHLY')).toEqual({ freq: 'MONTHLY', interval: 1, until: '' });
		expect(parseRrule('FREQ=MONTHLY;INTERVAL=3')).toEqual({
			freq: 'MONTHLY',
			interval: 3,
			until: ''
		});
		expect(parseRrule('FREQ=YEARLY')).toEqual({ freq: 'YEARLY', interval: 1, until: '' });
	});
	it('parses a custom interval', () => {
		expect(parseRrule('FREQ=MONTHLY;INTERVAL=2')).toEqual({
			freq: 'MONTHLY',
			interval: 2,
			until: ''
		});
		expect(parseRrule('FREQ=DAILY;INTERVAL=45').interval).toBe(45);
	});
	it('normalizes every accepted UNTIL shape', () => {
		expect(parseRrule('FREQ=MONTHLY;UNTIL=20261231T235959Z').until).toBe('2026-12-31');
		expect(parseRrule('FREQ=MONTHLY;UNTIL=20261231T235959').until).toBe('2026-12-31');
		expect(parseRrule('FREQ=MONTHLY;UNTIL=20261231').until).toBe('2026-12-31');
		expect(parseRrule('FREQ=MONTHLY;UNTIL=2026-12-31').until).toBe('2026-12-31');
		expect(parseRrule('FREQ=MONTHLY;UNTIL=2026-12-31T23:59:59').until).toBe('2026-12-31');
	});
	it('ignores an unrecognized UNTIL rather than emitting garbage', () => {
		expect(parseRrule('FREQ=MONTHLY;UNTIL=nonsense').until).toBe('');
	});
	it('is order-independent', () => {
		expect(parseRrule('INTERVAL=2;FREQ=WEEKLY')).toEqual({
			freq: 'WEEKLY',
			interval: 2,
			until: ''
		});
		expect(parseRrule('UNTIL=20261231T235959Z;FREQ=DAILY;INTERVAL=4')).toEqual({
			freq: 'DAILY',
			interval: 4,
			until: '2026-12-31'
		});
	});
	it('falls back to interval 1 for non-positive or unparseable values', () => {
		expect(parseRrule('FREQ=DAILY;INTERVAL=0').interval).toBe(1);
		expect(parseRrule('FREQ=DAILY;INTERVAL=abc').interval).toBe(1);
		expect(parseRrule('FREQ=DAILY;INTERVAL=-2').interval).toBe(1);
		expect(parseRrule('FREQ=DAILY;INTERVAL=').interval).toBe(1);
	});
	it('skips unknown keys and malformed segments', () => {
		expect(parseRrule('FREQ=WEEKLY;BYDAY=MO;;INTERVAL=3;junk')).toEqual({
			freq: 'WEEKLY',
			interval: 3,
			until: ''
		});
	});
	it('falls back to monthly for missing, unknown or garbage input', () => {
		expect(parseRrule('')).toEqual({ freq: 'MONTHLY', interval: 1, until: '' });
		expect(parseRrule('INTERVAL=2')).toEqual({ freq: 'MONTHLY', interval: 2, until: '' });
		expect(parseRrule('FREQ=HOURLY')).toEqual({ freq: 'MONTHLY', interval: 1, until: '' });
		expect(parseRrule('total garbage')).toEqual({ freq: 'MONTHLY', interval: 1, until: '' });
	});
});

describe('buildRrule', () => {
	it('emits the legacy wire string for every preset', () => {
		const built = PRESETS.map(p => buildRrule(p.freq, p.interval));
		expect(built).toEqual(LEGACY_WIRE_STRINGS);
	});
	it('omits INTERVAL when it is 1', () => {
		// Non-negotiable: pre-v1.22.0 clients match the rrule by exact string,
		// so `FREQ=WEEKLY;INTERVAL=1` would degrade to "monthly" and overwrite
		// the real recurrence on save.
		expect(buildRrule('WEEKLY', 1)).toBe('FREQ=WEEKLY');
		expect(buildRrule('DAILY', 1)).toBe('FREQ=DAILY');
	});
	it('emits INTERVAL for custom values', () => {
		expect(buildRrule('MONTHLY', 2)).toBe('FREQ=MONTHLY;INTERVAL=2');
		expect(buildRrule('DAILY', 45)).toBe('FREQ=DAILY;INTERVAL=45');
		expect(buildRrule('YEARLY', MAX_INTERVAL)).toBe(`FREQ=YEARLY;INTERVAL=${MAX_INTERVAL}`);
	});
	it('appends UNTIL in the compact end-of-day form', () => {
		expect(buildRrule('MONTHLY', 1, '2026-12-31')).toBe('FREQ=MONTHLY;UNTIL=20261231T235959Z');
	});
	it('keeps key order FREQ;INTERVAL;UNTIL', () => {
		expect(buildRrule('WEEKLY', 3, '2026-12-31')).toBe(
			'FREQ=WEEKLY;INTERVAL=3;UNTIL=20261231T235959Z'
		);
	});
	it('ignores an empty until', () => {
		expect(buildRrule('MONTHLY', 1, '')).toBe('FREQ=MONTHLY');
	});
});

describe('round trip', () => {
	// The regression guard for the data-loss bug: before v1.22.0 the form
	// reverse-mapped an rrule by exact equality, so anything unrecognized became
	// "monthly" and saving silently rewrote the user's real recurrence.
	const cases = [
		...LEGACY_WIRE_STRINGS,
		...LEGACY_WIRE_STRINGS.map(s => `${s};UNTIL=20261231T235959Z`),
		'FREQ=MONTHLY;INTERVAL=2',
		'FREQ=DAILY;INTERVAL=45',
		'FREQ=WEEKLY;INTERVAL=3;UNTIL=20261231T235959Z',
		`FREQ=YEARLY;INTERVAL=${MAX_INTERVAL}`
	];
	for (const s of cases) {
		it(`preserves ${s}`, () => {
			const p = parseRrule(s);
			expect(buildRrule(p.freq, p.interval, p.until)).toBe(s);
		});
	}
});

describe('rruleLabel', () => {
	it('labels every preset', () => {
		expect(rruleLabel('FREQ=DAILY')).toBe('Daily');
		expect(rruleLabel('FREQ=WEEKLY')).toBe('Weekly');
		expect(rruleLabel('FREQ=WEEKLY;INTERVAL=2')).toBe('Biweekly');
		expect(rruleLabel('FREQ=MONTHLY')).toBe('Monthly');
		expect(rruleLabel('FREQ=MONTHLY;INTERVAL=3')).toBe('Quarterly');
		expect(rruleLabel('FREQ=YEARLY')).toBe('Yearly');
	});
	it('ignores UNTIL when preset-matching', () => {
		// Pre-v1.22.0 this leaked the raw string for every ending schedule.
		expect(rruleLabel('FREQ=WEEKLY;INTERVAL=2;UNTIL=20261231T235959Z')).toBe('Biweekly');
		expect(rruleLabel('FREQ=DAILY;UNTIL=20261231T235959Z')).toBe('Daily');
	});
	it('describes custom intervals', () => {
		expect(rruleLabel('FREQ=MONTHLY;INTERVAL=2')).toBe('Every 2 months');
		expect(rruleLabel('FREQ=DAILY;INTERVAL=10')).toBe('Every 10 days');
		expect(rruleLabel('FREQ=WEEKLY;INTERVAL=5')).toBe('Every 5 weeks');
		expect(rruleLabel('FREQ=YEARLY;INTERVAL=2')).toBe('Every 2 years');
	});
	it('treats a redundant INTERVAL=1 as the preset', () => {
		expect(rruleLabel('FREQ=DAILY;INTERVAL=1')).toBe('Daily');
	});
	it('never leaks a raw string for garbage input', () => {
		expect(rruleLabel('total garbage')).toBe('Monthly');
	});
});

describe('presetKeyFor', () => {
	it('resolves preset pairs', () => {
		expect(presetKeyFor('DAILY', 1)).toBe('daily');
		expect(presetKeyFor('WEEKLY', 1)).toBe('weekly');
		expect(presetKeyFor('WEEKLY', 2)).toBe('biweekly');
		expect(presetKeyFor('MONTHLY', 1)).toBe('monthly');
		expect(presetKeyFor('MONTHLY', 3)).toBe('quarterly');
		expect(presetKeyFor('YEARLY', 1)).toBe('yearly');
	});
	it('returns custom for everything else', () => {
		expect(presetKeyFor('MONTHLY', 2)).toBe('custom');
		expect(presetKeyFor('WEEKLY', 3)).toBe('custom');
		expect(presetKeyFor('YEARLY', 2)).toBe('custom');
		expect(presetKeyFor('DAILY', 45)).toBe('custom');
	});
});

describe('unitLabel', () => {
	it('is singular for 1 and plural otherwise', () => {
		expect(unitLabel('DAILY', 1)).toBe('day');
		expect(unitLabel('DAILY', 2)).toBe('days');
		expect(unitLabel('WEEKLY', 1)).toBe('week');
		expect(unitLabel('WEEKLY', 3)).toBe('weeks');
		expect(unitLabel('MONTHLY', 1)).toBe('month');
		expect(unitLabel('MONTHLY', 2)).toBe('months');
		expect(unitLabel('YEARLY', 1)).toBe('year');
		expect(unitLabel('YEARLY', 4)).toBe('years');
	});
});
