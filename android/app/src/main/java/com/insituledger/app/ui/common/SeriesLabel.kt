package com.insituledger.app.ui.common

import com.insituledger.app.domain.model.ScheduledTransaction
import com.insituledger.app.domain.model.Transaction
import com.insituledger.app.util.Rrule

const val SCHEDULED_LABEL = "Scheduled"
const val RECURRING_LABEL = "Recurring"

/**
 * Where an auto-added entry sits in its series, for the row's supporting line;
 * null for ordinary entries (no `scheduledId`). Same rule as the web app:
 *
 * - one-time schedule (`FREQ=DAILY` with max 1, or `occurrenceTotal == 1`) → "Scheduled"
 * - position and length known → "Monthly · 2 of 3"
 * - open-ended, position unknown, or position past the length → "Monthly"
 *
 * The frequency comes from [schedule]'s rrule; when the schedule is gone
 * (purged after deletion or exhaustion) it reads "Recurring" instead.
 */
fun seriesLabel(transaction: Transaction, schedule: ScheduledTransaction?): String? {
    if (transaction.scheduledId == null) return null
    val total = transaction.occurrenceTotal
    if (total == 1) return SCHEDULED_LABEL
    val frequency = schedule?.let { Rrule.label(it.rrule, it.maxOccurrences) } ?: RECURRING_LABEL
    if (frequency == Rrule.ONE_TIME_LABEL) return SCHEDULED_LABEL
    val occurrence = transaction.occurrence
    // Past the stored length (max_occurrences lowered mid-series): "4 of 2"
    // would be nonsense, so fall back to the bare frequency.
    return if (occurrence != null && total != null && occurrence <= total) "$frequency · $occurrence of $total" else frequency
}

/** [seriesLabel] with the schedule looked up in [schedules] (keyed by id). */
fun seriesLabel(transaction: Transaction, schedules: Map<Long, ScheduledTransaction>): String? =
    seriesLabel(transaction, transaction.scheduledId?.let(schedules::get))
