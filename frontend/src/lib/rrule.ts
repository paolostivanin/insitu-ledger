// RFC 5545 RRULE subset shared by the scheduled-transaction list and form.
//
// Wire format: `FREQ=…[;INTERVAL=n][;UNTIL=…]`, stored verbatim in
// scheduled_transactions.rrule. The Kotlin twin is android/…/util/Rrule.kt and
// the authoritative date math is backend/internal/scheduler/scheduler.go —
// keep the three in step.
//
// The one rule that must never be broken: buildRrule omits INTERVAL when it is
// 1, so the six historical presets serialize byte-for-byte as they always have.
// Clients built before v1.22.0 resolve an rrule by exact string match, and a
// redundant `INTERVAL=1` would make them fall back to "monthly" — then
// overwrite the real recurrence on save.

export type Freq = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'YEARLY';

export type PresetKey = 'daily' | 'weekly' | 'biweekly' | 'monthly' | 'quarterly' | 'yearly';

export type FrequencyKey = PresetKey | 'custom';

export const CUSTOM_KEY = 'custom';

// Matches the backend's validateRRule bound (helpers.go).
export const MAX_INTERVAL = 999;

export interface ParsedRrule {
	freq: Freq;
	interval: number;
	/** UNTIL as 'YYYY-MM-DD', or '' when the schedule has no end date. */
	until: string;
}

export interface RrulePreset {
	key: PresetKey;
	label: string;
	freq: Freq;
	interval: number;
}

// Order is the dropdown order. Every entry must round-trip through
// buildRrule to the exact string that shipped clients have always written.
export const PRESETS: readonly RrulePreset[] = [
	{ key: 'daily', label: 'Daily', freq: 'DAILY', interval: 1 },
	{ key: 'weekly', label: 'Weekly', freq: 'WEEKLY', interval: 1 },
	{ key: 'biweekly', label: 'Biweekly', freq: 'WEEKLY', interval: 2 },
	{ key: 'monthly', label: 'Monthly', freq: 'MONTHLY', interval: 1 },
	{ key: 'quarterly', label: 'Quarterly', freq: 'MONTHLY', interval: 3 },
	{ key: 'yearly', label: 'Yearly', freq: 'YEARLY', interval: 1 }
];

const UNITS: Record<Freq, [string, string]> = {
	DAILY: ['day', 'days'],
	WEEKLY: ['week', 'weeks'],
	MONTHLY: ['month', 'months'],
	YEARLY: ['year', 'years']
};

const FREQS: Freq[] = ['DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY'];

// Normalize any UNTIL shape the scheduler accepts down to 'YYYY-MM-DD':
// 20261231T235959Z, 20261231T235959, 20261231, 2026-12-31, 2026-12-31T23:59:59.
function normalizeUntil(raw: string): string {
	const core = raw.split('T')[0];
	let ymd = '';
	if (/^\d{8}$/.test(core)) {
		ymd = `${core.slice(0, 4)}-${core.slice(4, 6)}-${core.slice(6, 8)}`;
	} else if (/^\d{4}-\d{2}-\d{2}$/.test(core)) {
		ymd = core;
	}
	// Reject impossible dates (2026-13-45) so they can't reach a date input.
	// The Kotlin twin gets this from java.time refusing to parse them.
	return ymd && !isNaN(Date.parse(`${ymd}T00:00:00Z`)) ? ymd : '';
}

/**
 * Parse an rrule into its parts. Order-independent and deliberately tolerant,
 * mirroring the schedulers: unknown keys and malformed segments are skipped, a
 * missing or unrecognized FREQ falls back to MONTHLY, and a non-positive or
 * unparseable INTERVAL falls back to 1. Never throws — it is given stored data
 * that it has no way to reject.
 */
export function parseRrule(rrule: string): ParsedRrule {
	let freq: Freq = 'MONTHLY';
	let interval = 1;
	let until = '';

	for (const part of (rrule || '').split(';')) {
		const eq = part.indexOf('=');
		if (eq < 0) continue;
		const key = part.slice(0, eq);
		const value = part.slice(eq + 1);
		switch (key) {
			case 'FREQ':
				if ((FREQS as string[]).includes(value)) freq = value as Freq;
				break;
			case 'INTERVAL': {
				// parseInt, not the Go digit-scan, so 'abc' and '-2' both land on
				// 1. They differ only on values the backend now rejects outright.
				const n = parseInt(value, 10);
				if (Number.isFinite(n) && n > 0) interval = n;
				break;
			}
			case 'UNTIL':
				until = normalizeUntil(value);
				break;
		}
	}

	return { freq, interval, until };
}

/**
 * Serialize to the canonical wire form. `untilDate` is 'YYYY-MM-DD' or ''.
 * Key order is fixed at FREQ;INTERVAL;UNTIL and INTERVAL=1 is omitted, so
 * `buildRrule(...parseRrule(s))` returns `s` unchanged for every string a
 * shipped client has ever written.
 */
export function buildRrule(freq: Freq, interval: number, untilDate = ''): string {
	let out = `FREQ=${freq}`;
	if (interval > 1) out += `;INTERVAL=${interval}`;
	if (untilDate) out += `;UNTIL=${untilDate.replace(/-/g, '')}T235959Z`;
	return out;
}

/** The preset key for a freq/interval pair, or 'custom' if it isn't a preset. */
export function presetKeyFor(freq: Freq, interval: number): FrequencyKey {
	return PRESETS.find(p => p.freq === freq && p.interval === interval)?.key ?? CUSTOM_KEY;
}

/** Singular/plural unit noun, e.g. unitLabel('MONTHLY', 2) → 'months'. */
export function unitLabel(freq: Freq, count: number): string {
	const [one, many] = UNITS[freq];
	return count === 1 ? one : many;
}

/**
 * Human label for a stored rrule, e.g. 'Biweekly' or 'Every 2 months'. UNTIL is
 * ignored when preset-matching, so a schedule with an end date still reads
 * 'Biweekly' instead of leaking the raw string.
 */
export function rruleLabel(rrule: string): string {
	const { freq, interval } = parseRrule(rrule);
	const preset = PRESETS.find(p => p.freq === freq && p.interval === interval);
	if (preset) return preset.label;
	return `Every ${interval} ${unitLabel(freq, interval)}`;
}
