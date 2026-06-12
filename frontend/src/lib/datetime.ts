// Datetime utilities for v1.18.0 TZ-aware wire format.
//
// Wire format: RFC3339 with offset (e.g. "2026-06-11T08:41:00+02:00"), or
// bare "YYYY-MM-DD" for date-only entries. We never display the offset to the
// user — the typed wall-clock is stable across viewers.

// Compute "+HH:MM" / "-HH:MM" for the JS Date's local timezone at the given
// reference instant (so DST transitions when back/future-dating are correct).
export function tzOffsetFor(naiveLocal: string): string {
	const d = new Date(naiveLocal);
	const ref = isNaN(d.getTime()) ? new Date() : d;
	const tz = -ref.getTimezoneOffset(); // minutes east of UTC
	const sign = tz >= 0 ? '+' : '-';
	const off = Math.abs(tz);
	const hh = String(Math.floor(off / 60)).padStart(2, '0');
	const mm = String(off % 60).padStart(2, '0');
	return `${sign}${hh}:${mm}`;
}

// Build the canonical wire string from a naive "YYYY-MM-DDTHH:mm".
// Adds seconds + the offset in effect at that local instant.
export function toIsoOffset(naiveLocal: string): string {
	return `${naiveLocal}:00${tzOffsetFor(naiveLocal)}`;
}

// Render an ISO-8601 string as "YYYY-MM-DD HH:mm" using the local-time
// portion only (strips any TZ offset or Z suffix, with or without seconds).
// Falls back to the input for date-only / unrecognized formats.
export function displayDate(iso: string): string {
	if (!iso) return '';
	const t = iso.indexOf('T');
	if (t < 0) return iso; // date-only
	let end = iso.length;
	for (let i = 11; i < iso.length; i++) {
		const c = iso[i];
		if (c === '+' || c === 'Z' || c === '-') {
			end = i;
			break;
		}
	}
	const core = iso.slice(0, end).replace('T', ' ');
	return core.length > 16 ? core.slice(0, 16) : core;
}

// Extract just the "HH:mm" portion. Returns empty string for date-only inputs.
export function extractTime(iso: string): string {
	if (!iso || !iso.includes('T')) return '';
	const afterT = iso.slice(11);
	let end = afterT.length;
	for (let i = 0; i < afterT.length; i++) {
		const c = afterT[i];
		if (c === '+' || c === 'Z' || c === '-') {
			end = i;
			break;
		}
	}
	const time = afterT.slice(0, end);
	return time.length >= 5 ? time.slice(0, 5) : time;
}
