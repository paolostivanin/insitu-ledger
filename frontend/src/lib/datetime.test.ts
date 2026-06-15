import { afterAll, beforeAll, describe, it, expect } from 'vitest';
import { displayDate, extractTime, localDateInputValue, localMonthKey, tzOffsetFor } from './datetime';

const runtimeEnv = (
	globalThis as unknown as { process: { env: Record<string, string | undefined> } }
).process.env;
const originalTimezone = runtimeEnv.TZ;
beforeAll(() => {
	// Force a non-UTC zone so a regression to toISOString() cannot pass in UTC CI.
	runtimeEnv.TZ = 'Pacific/Kiritimati';
});
afterAll(() => {
	runtimeEnv.TZ = originalTimezone;
});

describe('displayDate', () => {
	it('strips +HH:MM offset', () => {
		expect(displayDate('2026-06-11T08:41:00+02:00')).toBe('2026-06-11 08:41');
	});
	it('strips -HH:MM offset', () => {
		expect(displayDate('2026-06-11T08:41:00-05:00')).toBe('2026-06-11 08:41');
	});
	it('strips Z suffix', () => {
		expect(displayDate('2026-06-11T08:41:00Z')).toBe('2026-06-11 08:41');
	});
	it('handles no-seconds offset', () => {
		expect(displayDate('2026-06-11T08:41+02:00')).toBe('2026-06-11 08:41');
	});
	it('handles naive datetime', () => {
		expect(displayDate('2026-06-11T08:41')).toBe('2026-06-11 08:41');
	});
	it('handles naive datetime with seconds', () => {
		expect(displayDate('2026-06-11T08:41:30')).toBe('2026-06-11 08:41');
	});
	it('returns date-only verbatim', () => {
		expect(displayDate('2026-06-11')).toBe('2026-06-11');
	});
	it('returns empty for empty input', () => {
		expect(displayDate('')).toBe('');
	});
});

describe('extractTime', () => {
	it('extracts HH:mm from RFC3339', () => {
		expect(extractTime('2026-06-11T08:41:00+02:00')).toBe('08:41');
	});
	it('extracts HH:mm from naive', () => {
		expect(extractTime('2026-06-11T08:41')).toBe('08:41');
	});
	it('returns empty for date-only', () => {
		expect(extractTime('2026-06-11')).toBe('');
	});
	it('returns empty for empty', () => {
		expect(extractTime('')).toBe('');
	});
});

describe('tzOffsetFor', () => {
	it('produces a [+-]HH:MM-shaped string', () => {
		const got = tzOffsetFor('2026-06-11T08:41');
		expect(got).toMatch(/^[+-]\d{2}:\d{2}$/);
	});
	it('falls back to current TZ for unparseable input', () => {
		const got = tzOffsetFor('garbage');
		expect(got).toMatch(/^[+-]\d{2}:\d{2}$/);
	});
});

// Boundary tests run in UTC+14 so the local day/month intentionally differs
// from UTC. A regression to toISOString().slice(...) therefore fails in CI.
describe('localDateInputValue', () => {
	it('returns YYYY-MM-DD shape', () => {
		const got = localDateInputValue(new Date(2026, 5, 11, 12, 0, 0));
		expect(got).toMatch(/^\d{4}-\d{2}-\d{2}$/);
	});
	it('zero-pads single-digit month and day', () => {
		expect(localDateInputValue(new Date(2026, 0, 3, 12, 0, 0))).toBe('2026-01-03');
	});
	it('uses local-calendar day for a late-evening Date', () => {
		// 23:30 local on Jun 11 is the local calendar day Jun 11, regardless
		// of whether UTC has already rolled over to Jun 12.
		expect(localDateInputValue(new Date(2026, 5, 11, 23, 30, 0))).toBe('2026-06-11');
	});
	it('uses local-calendar day for a just-past-midnight Date', () => {
		// 00:30 local on Jun 11 is the local calendar day Jun 11, regardless
		// of whether UTC is still on Jun 10.
		expect(localDateInputValue(new Date(2026, 5, 11, 0, 30, 0))).toBe('2026-06-11');
	});
	it('differs from UTC for an explicit instant near midnight', () => {
		expect(localDateInputValue(new Date('2026-06-11T10:30:00Z'))).toBe('2026-06-12');
	});
	it('does not shift across a year boundary', () => {
		expect(localDateInputValue(new Date(2027, 0, 1, 0, 5, 0))).toBe('2027-01-01');
		expect(localDateInputValue(new Date(2026, 11, 31, 23, 55, 0))).toBe('2026-12-31');
	});
});

describe('localMonthKey', () => {
	it('returns YYYY-MM shape', () => {
		const got = localMonthKey(new Date(2026, 5, 11, 12, 0, 0));
		expect(got).toMatch(/^\d{4}-\d{2}$/);
	});
	it('zero-pads single-digit month', () => {
		expect(localMonthKey(new Date(2026, 2, 11, 12, 0, 0))).toBe('2026-03');
	});
	it('keeps the local month on the first of the month near midnight', () => {
		// Local midnight on Jun 1 means we're in June, regardless of whether
		// UTC is still showing May 31.
		expect(localMonthKey(new Date(2026, 5, 1, 0, 30, 0))).toBe('2026-06');
	});
	it('keeps the local month on the last of the month near midnight', () => {
		// Local 23:30 on May 31 is still May, regardless of whether UTC has
		// already rolled into June.
		expect(localMonthKey(new Date(2026, 4, 31, 23, 30, 0))).toBe('2026-05');
	});
	it('differs from UTC at an explicit month-boundary instant', () => {
		expect(localMonthKey(new Date('2026-05-31T10:30:00Z'))).toBe('2026-06');
	});
});
