package com.insituledger.app.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** How a recurrence ends. Maps to max_occurrences (COUNT) or UNTIL (DATE). */
enum class EndMode { NEVER, COUNT, DATE }

/**
 * The editable shape of a recurrence, shared by the inline schedule sheet on
 * the transaction form and the Scheduled form. Pure: primitive and enum fields
 * only, so it is trivially testable and safe to keep in UI state.
 *
 * User-typed numbers stay Strings (like amount) so a half-typed field can be
 * shown and rejected instead of silently coerced.
 *
 * Wire output goes through [Rrule.build], so every preset serializes
 * byte-for-byte the way shipped clients always have.
 */
data class RecurrenceDraft(
    /** An [Rrule.PRESETS] key, or [Rrule.CUSTOM_KEY]. */
    val presetKey: String = "monthly",
    /** Only read when [presetKey] is [Rrule.CUSTOM_KEY]. */
    val customInterval: String = "2",
    val customUnit: Freq = Freq.MONTHLY,
    val endMode: EndMode = EndMode.NEVER,
    /** Only read when [endMode] is COUNT. */
    val count: String = "",
    /** "yyyy-MM-dd", only read when [endMode] is DATE. */
    val endDate: String = ""
) {

    val isCustom: Boolean get() = presetKey == Rrule.CUSTOM_KEY

    /**
     * The {freq, interval} pair this draft describes. A bad custom interval is
     * coerced to 1 like [Rrule.parse] does; [validate] is what rejects it.
     */
    fun freqInterval(): Pair<Freq, Int> = if (isCustom) {
        customUnit to (customInterval.toIntOrNull()?.takeIf { it > 0 } ?: 1)
    } else {
        Rrule.preset(presetKey)?.let { it.freq to it.interval } ?: (Freq.MONTHLY to 1)
    }

    /** Canonical wire string. UNTIL is end-of-day so the chosen date is inclusive. */
    fun rrule(): String {
        val (freq, interval) = freqInterval()
        return Rrule.build(freq, interval, if (endMode == EndMode.DATE) endDate else "")
    }

    /** max_occurrences for a strictly validated draft: 1–[MAX_COUNT] or null. */
    fun maxOccurrences(): Int? =
        if (endMode == EndMode.COUNT) count.toIntOrNull()?.takeIf { it in 1..MAX_COUNT } else null

    /** The Scheduled form's historical rule: a blank or non-positive count means "never". */
    fun maxOccurrencesLenient(): Int? =
        if (endMode == EndMode.COUNT) count.toIntOrNull()?.takeIf { it > 0 } else null

    /**
     * A field-specific error message, or null when the draft can be saved.
     *
     * Lenient mode reproduces the Scheduled form's pre-existing checks, messages
     * and order exactly. Strict mode (the inline sheet) also bounds the count,
     * requires the end date not to precede the start date, and caps the
     * catch-up a past start would trigger at [MAX_PAST_OCCURRENCES].
     */
    fun validate(start: LocalDateTime, now: LocalDateTime, strict: Boolean): String? {
        if (!strict) {
            if (endMode == EndMode.DATE && endDate.isBlank()) return MSG_PICK_END_DATE
            if (isCustom) {
                val interval = customInterval.toIntOrNull()
                if (interval == null || interval < 1 || interval > Rrule.MAX_INTERVAL) return MSG_INTERVAL
            }
            return null
        }

        if (isCustom) {
            val interval = customInterval.takeIf { s -> s.isNotEmpty() && s.all { it.isDigit() } }?.toIntOrNull()
            if (interval == null || interval < 1 || interval > Rrule.MAX_INTERVAL) return MSG_INTERVAL
        }
        when (endMode) {
            EndMode.NEVER -> Unit
            EndMode.COUNT -> {
                val n = count.takeIf { s -> s.isNotEmpty() && s.all { it.isDigit() } }?.toIntOrNull()
                if (n == null || n < 1 || n > MAX_COUNT) return MSG_COUNT
            }
            EndMode.DATE -> {
                if (endDate.isBlank()) return MSG_PICK_END_DATE
                val end = parseEndDate() ?: return MSG_PICK_END_DATE
                // Both schedulers only test UNTIL after materializing the first
                // occurrence, so an earlier end date would still create one entry.
                if (end.isBefore(start.toLocalDate())) return MSG_END_BEFORE_START
            }
        }
        if (pastOccurrences(start, now, MAX_PAST_OCCURRENCES + 1).size > MAX_PAST_OCCURRENCES) {
            return MSG_TOO_MANY_PAST
        }
        return null
    }

    /**
     * The first [n] occurrences from [start] (or, with [after], the first [n]
     * strictly after it, at minute precision), honouring COUNT and UNTIL.
     *
     * Steps by repeated [Rrule.advance] from the previous value, exactly like
     * both schedulers, so month-end clamping is sticky: 31 Jan → 28 Feb → 28 Mar.
     */
    fun upcoming(start: LocalDateTime, n: Int, after: LocalDateTime? = null): List<LocalDateTime> {
        if (n <= 0) return emptyList()
        val threshold = after?.truncatedTo(ChronoUnit.MINUTES)
        val out = mutableListOf<LocalDateTime>()
        walk(start) { occurrence ->
            if (threshold == null || occurrence.truncatedTo(ChronoUnit.MINUTES).isAfter(threshold)) {
                out += occurrence
            }
            out.size < n
        }
        return out
    }

    /**
     * Occurrences at or before [now] (minute precision, matching the worker's
     * SUBSTR(next_occurrence, 1, 16) due check), honouring COUNT and UNTIL.
     * These are what the scheduler will create right away. At most [cap].
     */
    fun pastOccurrences(start: LocalDateTime, now: LocalDateTime, cap: Int): List<LocalDateTime> {
        if (cap <= 0) return emptyList()
        val threshold = now.truncatedTo(ChronoUnit.MINUTES)
        val out = mutableListOf<LocalDateTime>()
        walk(start) { occurrence ->
            if (occurrence.truncatedTo(ChronoUnit.MINUTES).isAfter(threshold)) return@walk false
            out += occurrence
            out.size < cap
        }
        return out
    }

    /**
     * "Monthly", "Every 3 weeks", optionally followed by " · 12 times" or
     * " · until 31 Dec 2027" (the year is omitted when it matches [start]'s).
     */
    fun summary(start: LocalDateTime, locale: Locale): String {
        val (freq, interval) = freqInterval()
        val base = Rrule.preset(Rrule.presetKey(freq, interval))?.label
            ?: "Every $interval ${Rrule.unitLabel(freq, interval)}"
        val suffix = when (endMode) {
            EndMode.NEVER -> ""
            EndMode.COUNT -> when (val n = maxOccurrencesLenient()) {
                null -> ""
                1 -> " · once"
                else -> " · $n times"
            }
            EndMode.DATE -> parseEndDate()?.let { end ->
                val pattern = if (end.year == start.year) "d MMM" else "d MMM yyyy"
                " · until " + end.format(DateTimeFormatter.ofPattern(pattern, locale))
            } ?: ""
        }
        return base + suffix
    }

    /**
     * Which day the series lands on: "on Fridays" (weekly), "on day 14"
     * (monthly), "every 14 Nov" (yearly), "" (daily).
     */
    fun anchorHint(start: LocalDateTime, locale: Locale): String = when (freqInterval().first) {
        Freq.DAILY -> ""
        Freq.WEEKLY -> "on " + start.dayOfWeek.getDisplayName(TextStyle.FULL, locale) + "s"
        Freq.MONTHLY -> "on day ${start.dayOfMonth}"
        Freq.YEARLY -> "every " + start.format(DateTimeFormatter.ofPattern("d MMM", locale))
    }

    private fun parseEndDate(): LocalDate? =
        try { LocalDate.parse(endDate) } catch (_: Exception) { null }

    // Visits occurrences in order until [visit] returns false or the series
    // ends. COUNT is the lenient value (what the scheduler would store) and
    // UNTIL is end-of-day inclusive; like both schedulers, UNTIL is only
    // checked after the first occurrence.
    private inline fun walk(start: LocalDateTime, visit: (LocalDateTime) -> Boolean) {
        val (freq, interval) = freqInterval()
        val countCap = maxOccurrencesLenient()
        val until = if (endMode == EndMode.DATE) parseEndDate()?.atTime(23, 59, 59) else null
        var current = start
        var produced = 0
        var steps = 0
        while (steps < MAX_WALK_STEPS) {
            if (produced > 0 && until != null && current.isAfter(until)) return
            produced++
            if (!visit(current)) return
            if (countCap != null && produced >= countCap) return
            current = Rrule.advance(current, freq, interval)
            steps++
        }
    }

    companion object {
        const val MAX_COUNT = 999
        const val MAX_PAST_OCCURRENCES = 366

        // Guards [walk] against a start date decades in the past.
        private const val MAX_WALK_STEPS = 100_000

        const val MSG_PICK_END_DATE = "Pick an end date"
        const val MSG_INTERVAL = "Repeat every must be a whole number between 1 and ${Rrule.MAX_INTERVAL}"
        const val MSG_COUNT = "Enter a number of times between 1 and $MAX_COUNT"
        const val MSG_END_BEFORE_START = "The end date can't be before the start date"
        const val MSG_TOO_MANY_PAST =
            "That start date would add more than $MAX_PAST_OCCURRENCES past entries. Pick a later start date."

        /** Digits only, bounded to the width of [Rrule.MAX_INTERVAL] / [MAX_COUNT]. */
        fun sanitizeNumber(raw: String): String =
            raw.filter { it.isDigit() }.take(Rrule.MAX_INTERVAL.toString().length)

        /**
         * Rebuild a draft from a stored schedule. Parses the rrule into parts
         * instead of matching it whole, so an unrecognized rule never degrades
         * to "monthly". `fromStored(s, max).rrule() == s` for every string a
         * shipped client has written.
         */
        fun fromStored(rrule: String, maxOccurrences: Int?): RecurrenceDraft {
            val parsed = Rrule.parse(rrule)
            val key = Rrule.presetKey(parsed.freq, parsed.interval)
            val defaults = RecurrenceDraft()
            return RecurrenceDraft(
                presetKey = key,
                customInterval = if (key == Rrule.CUSTOM_KEY) parsed.interval.toString() else defaults.customInterval,
                customUnit = if (key == Rrule.CUSTOM_KEY) parsed.freq else defaults.customUnit,
                endMode = when {
                    parsed.until.isNotEmpty() -> EndMode.DATE
                    maxOccurrences != null -> EndMode.COUNT
                    else -> EndMode.NEVER
                },
                count = maxOccurrences?.toString() ?: "",
                endDate = parsed.until
            )
        }
    }
}
