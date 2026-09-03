import { localDateInputValue } from '$lib/datetime';

export type PeriodPreset =
	| 'all'
	| 'this_week'
	| 'this_month'
	| 'this_year'
	| 'last_month'
	| 'last_year'
	| 'custom';

export const PERIOD_LABELS: Record<PeriodPreset, string> = {
	all: 'All time',
	this_week: 'This week',
	this_month: 'This month',
	this_year: 'This year',
	last_month: 'Last month',
	last_year: 'Last year',
	custom: 'Custom range'
};

// Weeks start Monday. Android has a user-configurable week start, but it lives
// in a local DataStore with no server or web counterpart, so ISO is the only
// defensible default here.
function startOfWeek(date: Date): Date {
	const d = new Date(date.getFullYear(), date.getMonth(), date.getDate());
	// getDay(): 0 = Sunday. Sunday is 6 days after the Monday that starts it.
	d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
	return d;
}

/**
 * Resolve a period preset to the bare `YYYY-MM-DD` from/to bounds the reports
 * API expects. Both bounds are inclusive; `''` means unbounded, which the API
 * client strips from the query string.
 *
 * Built from local-calendar parts via localDateInputValue rather than
 * toISOString — bare dates are TZ-agnostic by design, and UTC conversion would
 * slide the boundary by a day for anyone east or west of UTC.
 */
export function resolvePeriod(
	preset: PeriodPreset,
	customFrom = '',
	customTo = '',
	now: Date = new Date()
): { from: string; to: string } {
	const today = localDateInputValue(now);
	const year = now.getFullYear();
	const month = now.getMonth();

	switch (preset) {
		case 'this_week':
			return { from: localDateInputValue(startOfWeek(now)), to: today };
		case 'this_month':
			return { from: localDateInputValue(new Date(year, month, 1)), to: today };
		case 'this_year':
			return { from: localDateInputValue(new Date(year, 0, 1)), to: today };
		case 'last_month':
			// Day 0 of month M is the last day of M-1, so this handles the
			// January rollover and short months without special-casing.
			return {
				from: localDateInputValue(new Date(year, month - 1, 1)),
				to: localDateInputValue(new Date(year, month, 0))
			};
		case 'last_year':
			return {
				from: localDateInputValue(new Date(year - 1, 0, 1)),
				to: localDateInputValue(new Date(year - 1, 11, 31))
			};
		case 'custom':
			return { from: customFrom, to: customTo };
		case 'all':
		default:
			return { from: '', to: '' };
	}
}
