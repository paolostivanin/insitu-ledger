package com.insituledger.app.ui.common

import com.insituledger.app.domain.model.ScheduledTransaction
import com.insituledger.app.domain.model.Transaction
import com.insituledger.app.util.Rrule

const val ONCE_LABEL = "Once"
const val RECURRING_LABEL = "Recurring"
const val OPEN_ENDED_MARK = "∞"

/**
 * The tonal badge on an auto-added entry: [text] as shown ("Monthly · 2/3",
 * "Weekly · ∞", "Once"), whether it came from a one-time schedule ([oneTime]:
 * clock icon instead of the repeat icon), and the [spoken] form for TalkBack
 * ("Recurring monthly, 2 of 3", "Recurring weekly, no end date", "Scheduled once").
 */
data class SeriesBadge(val text: String, val oneTime: Boolean, val spoken: String)

/**
 * Where an auto-added entry sits in its series; null for ordinary entries (no
 * `scheduledId`). Same rule as the web app (frontend/src/lib/series.ts):
 *
 * - one-time schedule (`FREQ=DAILY` with max 1, or `occurrenceTotal == 1`) → "Once"
 * - position and length known → "Monthly · 2/3"
 * - open-ended (no length; position stored, or the schedule has no max) → "Monthly · ∞"
 * - position unknown on a bounded series, or past a lowered length → "Monthly"
 *
 * The frequency comes from [schedule]'s rrule; when the schedule is gone
 * (purged after deletion or exhaustion) it reads "Recurring" instead.
 */
fun seriesBadge(transaction: Transaction, schedule: ScheduledTransaction?): SeriesBadge? {
    if (transaction.scheduledId == null) return null
    val total = transaction.occurrenceTotal
    val frequency = schedule?.let { Rrule.label(it.rrule, it.maxOccurrences) }
    if (total == 1 || frequency == Rrule.ONE_TIME_LABEL) {
        return SeriesBadge(ONCE_LABEL, oneTime = true, spoken = "Scheduled once")
    }
    val freq = frequency ?: RECURRING_LABEL
    val occurrence = transaction.occurrence
    // (shown, spoken) counter, or null when the position can't be stated.
    val counter: Pair<String, String>? = when {
        occurrence != null && total != null && occurrence <= total -> "$occurrence/$total" to "$occurrence of $total"
        // Past the stored length (max lowered mid-series): "4/2" would be nonsense.
        total != null -> null
        // New-style open-ended entry: position stored, no length.
        occurrence != null -> OPEN_ENDED_MARK to "no end date"
        // Backfilled history: no position, but the schedule says it never ends.
        schedule != null && schedule.maxOccurrences == null -> OPEN_ENDED_MARK to "no end date"
        else -> null
    }
    val spokenFreq = if (freq == RECURRING_LABEL) RECURRING_LABEL else "Recurring ${freq.lowercase()}"
    return SeriesBadge(
        text = counter?.let { "$freq · ${it.first}" } ?: freq,
        oneTime = false,
        spoken = counter?.let { "$spokenFreq, ${it.second}" } ?: spokenFreq
    )
}

/** [seriesBadge] with the schedule looked up in [schedules] (keyed by id). */
fun seriesBadge(transaction: Transaction, schedules: Map<Long, ScheduledTransaction>): SeriesBadge? =
    seriesBadge(transaction, transaction.scheduledId?.let(schedules::get))
