import { describe, it, expect } from 'vitest';
import { displayDate, extractTime, tzOffsetFor } from './datetime';

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
