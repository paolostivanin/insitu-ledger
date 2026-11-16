// Series badge for transactions a scheduler generated. The Kotlin twin is
// android/.../ui/common/SeriesLabel.kt (seriesBadge) — both follow the same
// display rule, so keep them in step.

import type { ScheduledTransaction, Transaction } from './api/client';
import { parseRrule, rruleLabel } from './rrule';

/** Shown in place of the frequency when the source schedule isn't known. */
export const RECURRING_FALLBACK = 'Recurring';
export const ONCE_LABEL = 'Once';
export const OPEN_ENDED_MARK = '∞';

type SeriesFields = Pick<Transaction, 'scheduled_id' | 'occurrence' | 'occurrence_total'>;
type ScheduleFields = Pick<ScheduledTransaction, 'rrule' | 'max_occurrences'>;

/** What the badge shows: 'Monthly · 2/3', 'Weekly · ∞', 'Once'; spoken = screen-reader text. */
export interface SeriesBadge {
	text: string;
	oneTime: boolean;
	spoken: string;
}

/** True when the transaction was added by a schedule rather than by hand. */
export function isScheduled(tx: SeriesFields): boolean {
	return tx.scheduled_id != null;
}

/**
 * Badge for a scheduler-generated transaction, or null for an ordinary one:
 * - one-time schedule (FREQ=DAILY interval 1 capped at 1, or occurrence_total 1) → 'Once'
 * - position and length known → '<Frequency> · <occurrence>/<occurrence_total>'
 * - open-ended (position stored without a length, or the schedule has no max) → '<Frequency> · ∞'
 * - position unknown on a bounded series, or past a lowered length → '<Frequency>'
 * <Frequency> comes from the schedule's rrule, or reads 'Recurring' while the
 * schedule is still loading or once it has been deleted.
 */
export function seriesBadge(tx: SeriesFields, schedule: ScheduleFields | undefined): SeriesBadge | null {
	if (!isScheduled(tx)) return null;
	const total = tx.occurrence_total ?? null;
	const occurrence = tx.occurrence ?? null;
	const ruleOneTime =
		schedule != null &&
		schedule.max_occurrences === 1 &&
		parseRrule(schedule.rrule).freq === 'DAILY' &&
		parseRrule(schedule.rrule).interval === 1;
	if (total === 1 || ruleOneTime) return { text: ONCE_LABEL, oneTime: true, spoken: 'Scheduled once' };

	const frequency = schedule ? rruleLabel(schedule.rrule) : RECURRING_FALLBACK;
	let counter: [string, string] | null = null;
	if (occurrence != null && total != null) {
		// "4/2" can happen when the cap is lowered mid-series; show just the frequency then.
		if (occurrence <= total) counter = [`${occurrence}/${total}`, `${occurrence} of ${total}`];
	} else if (total == null && (occurrence != null || (schedule != null && schedule.max_occurrences == null))) {
		counter = [OPEN_ENDED_MARK, 'no end date'];
	}
	const spokenFrequency =
		frequency === RECURRING_FALLBACK ? RECURRING_FALLBACK : `Recurring ${frequency.toLowerCase()}`;
	return {
		text: counter ? `${frequency} · ${counter[0]}` : frequency,
		oneTime: false,
		spoken: counter ? `${spokenFrequency}, ${counter[1]}` : spokenFrequency
	};
}

/**
 * Index a schedule list by id for seriesBadge lookups. Tolerates a missing
 * list so a failed or absent schedule fetch just leaves the 'Recurring' fallback.
 */
export function indexSchedules<T extends { id: number }>(list: readonly T[] | null | undefined): Map<number, T> {
	return new Map((list ?? []).map(s => [s.id, s]));
}
