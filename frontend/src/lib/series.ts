// Series marker for transactions a scheduler generated. The Kotlin twin lives
// in the Android transaction row — both follow the same display rule, so keep
// them in step.

import type { ScheduledTransaction, Transaction } from './api/client';
import { parseRrule, rruleLabel } from './rrule';

/** Shown in place of the frequency when the source schedule isn't known. */
export const RECURRING_FALLBACK = 'Recurring';

type SeriesFields = Pick<Transaction, 'scheduled_id' | 'occurrence' | 'occurrence_total'>;
type ScheduleFields = Pick<ScheduledTransaction, 'rrule' | 'max_occurrences'>;

/** True when the transaction was added by a schedule rather than by hand. */
export function isScheduled(tx: SeriesFields): boolean {
	return tx.scheduled_id != null;
}

/**
 * Secondary-line label for a scheduler-generated transaction, or null for an
 * ordinary one:
 * - one-time schedule (FREQ=DAILY capped at 1, or occurrence_total 1) → 'Scheduled'
 * - position known → '<Frequency> · <occurrence> of <occurrence_total>' (unless the
 *   cap was lowered below the position, then just '<Frequency>')
 * - open-ended → '<Frequency>'
 * <Frequency> comes from the schedule's rrule, or reads 'Recurring' while the
 * schedule is still loading or once it has been deleted.
 */
export function seriesLabel(tx: SeriesFields, schedule: ScheduleFields | undefined): string | null {
	if (!isScheduled(tx)) return null;
	const oneTime =
		tx.occurrence_total === 1 ||
		(schedule != null &&
			schedule.max_occurrences === 1 &&
			parseRrule(schedule.rrule).freq === 'DAILY' &&
			parseRrule(schedule.rrule).interval === 1);
	if (oneTime) return 'Scheduled';
	const frequency = schedule ? rruleLabel(schedule.rrule) : RECURRING_FALLBACK;
	// "4 of 2" can happen when the cap is lowered mid-series; show just the frequency then.
	if (tx.occurrence != null && tx.occurrence_total != null && tx.occurrence <= tx.occurrence_total) {
		return `${frequency} · ${tx.occurrence} of ${tx.occurrence_total}`;
	}
	return frequency;
}

/**
 * Index a schedule list by id for seriesLabel lookups. Tolerates a missing
 * list so a failed or absent schedule fetch just leaves the 'Recurring' fallback.
 */
export function indexSchedules<T extends { id: number }>(list: readonly T[] | null | undefined): Map<number, T> {
	return new Map((list ?? []).map(s => [s.id, s]));
}
