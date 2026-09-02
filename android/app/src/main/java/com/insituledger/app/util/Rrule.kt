package com.insituledger.app.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * RFC 5545 RRULE subset shared by the scheduled-transaction list, the form and
 * the materializing worker.
 *
 * Wire format: `FREQ=…[;INTERVAL=n][;UNTIL=…]`, stored verbatim in
 * scheduled_transactions.rrule. The TypeScript twin is frontend/src/lib/rrule.ts
 * and the authoritative date math is backend/internal/scheduler/scheduler.go —
 * keep the three in step.
 *
 * The one rule that must never be broken: [build] omits INTERVAL when it is 1,
 * so the six historical presets serialize byte-for-byte as they always have.
 * Clients built before v1.33.0 resolve an rrule by exact string match, and a
 * redundant `INTERVAL=1` would make them fall back to "monthly" — then
 * overwrite the real recurrence on save.
 */
enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

data class ParsedRrule(
    val freq: Freq,
    val interval: Int,
    /** UNTIL as "yyyy-MM-dd", or "" when the schedule has no end date. */
    val until: String
)

data class RrulePreset(
    val key: String,
    val label: String,
    val freq: Freq,
    val interval: Int
)

object Rrule {

    /** Matches the backend's validateRRule bound (helpers.go). */
    const val MAX_INTERVAL = 999
    const val CUSTOM_KEY = "custom"

    // Order is the dropdown order. Every entry must round-trip through [build]
    // to the exact string that shipped clients have always written.
    val PRESETS: List<RrulePreset> = listOf(
        RrulePreset("daily", "Daily", Freq.DAILY, 1),
        RrulePreset("weekly", "Weekly", Freq.WEEKLY, 1),
        RrulePreset("biweekly", "Biweekly", Freq.WEEKLY, 2),
        RrulePreset("monthly", "Monthly", Freq.MONTHLY, 1),
        RrulePreset("quarterly", "Quarterly", Freq.MONTHLY, 3),
        RrulePreset("yearly", "Yearly", Freq.YEARLY, 1)
    )

    private val UNITS = mapOf(
        Freq.DAILY to ("day" to "days"),
        Freq.WEEKLY to ("week" to "weeks"),
        Freq.MONTHLY to ("month" to "months"),
        Freq.YEARLY to ("year" to "years")
    )

    /**
     * Parse an rrule into its parts. Order-independent and deliberately
     * tolerant, mirroring the schedulers: unknown keys and malformed segments
     * are skipped, a missing or unrecognized FREQ falls back to MONTHLY, and a
     * non-positive or unparseable INTERVAL falls back to 1. Never throws — it is
     * given stored data that it has no way to reject.
     */
    fun parse(rrule: String): ParsedRrule {
        var freq = Freq.MONTHLY
        var interval = 1
        var until = ""

        for (part in rrule.split(";")) {
            val kv = part.split("=", limit = 2)
            if (kv.size != 2) continue
            when (kv[0]) {
                "FREQ" -> Freq.entries.find { it.name == kv[1] }?.let { freq = it }
                "INTERVAL" -> kv[1].toIntOrNull()?.let { if (it > 0) interval = it }
                "UNTIL" -> until = normalizeUntil(kv[1])
            }
        }

        return ParsedRrule(freq, interval, until)
    }

    /**
     * Serialize to the canonical wire form. [untilDate] is "yyyy-MM-dd" or "".
     * Key order is fixed at FREQ;INTERVAL;UNTIL and INTERVAL=1 is omitted, so
     * `build(parse(s))` returns `s` unchanged for every string a shipped client
     * has ever written.
     */
    fun build(freq: Freq, interval: Int, untilDate: String = ""): String {
        val sb = StringBuilder("FREQ=").append(freq.name)
        if (interval > 1) sb.append(";INTERVAL=").append(interval)
        if (untilDate.isNotEmpty()) {
            sb.append(";UNTIL=").append(untilDate.replace("-", "")).append("T235959Z")
        }
        return sb.toString()
    }

    /** The preset key for a freq/interval pair, or [CUSTOM_KEY] if it isn't one. */
    fun presetKey(freq: Freq, interval: Int): String =
        PRESETS.find { it.freq == freq && it.interval == interval }?.key ?: CUSTOM_KEY

    fun preset(key: String): RrulePreset? = PRESETS.find { it.key == key }

    /** Singular/plural unit noun, e.g. unitLabel(MONTHLY, 2) → "months". */
    fun unitLabel(freq: Freq, count: Int): String {
        val (one, many) = UNITS.getValue(freq)
        return if (count == 1) one else many
    }

    /**
     * Human label for a stored rrule, e.g. "Biweekly" or "Every 2 months". UNTIL
     * is ignored when preset-matching, so a schedule with an end date still
     * reads "Biweekly" instead of leaking the raw string.
     */
    fun label(rrule: String): String {
        val (freq, interval, _) = parse(rrule)
        val preset = PRESETS.find { it.freq == freq && it.interval == interval }
        if (preset != null) return preset.label
        return "Every $interval ${unitLabel(freq, interval)}"
    }

    /**
     * Add one recurrence step. java.time clamps the day of month to the target
     * month's last valid day (2026-01-31 + 1 month → 2026-02-28), which the Go
     * scheduler matches since v1.22.0 via addMonthsClamped.
     */
    fun advance(dateTime: LocalDateTime, freq: Freq, interval: Int): LocalDateTime =
        when (freq) {
            Freq.DAILY -> dateTime.plusDays(interval.toLong())
            Freq.WEEKLY -> dateTime.plusWeeks(interval.toLong())
            Freq.MONTHLY -> dateTime.plusMonths(interval.toLong())
            Freq.YEARLY -> dateTime.plusYears(interval.toLong())
        }

    /**
     * Advance a stored next_occurrence by one step of [rrule], returning the new
     * value and whether it is past the rule's UNTIL.
     *
     * Preserves the input's shape: a datetime comes back as RFC3339 with the
     * system zone's offset so the backend's TZ-aware comparison stays correct
     * after a local sync push; a date-only value stays date-only (TZ-agnostic by
     * design).
     */
    fun advanceDate(current: String, rrule: String): Pair<String, Boolean> {
        val hasTime = current.contains('T') || current.contains(' ')
        val dateTime = DateTimeUtil.parseFlexibleLocalDateTime(current)

        val (freq, interval, _) = parse(rrule)
        val next = advance(dateTime, freq, interval)

        // Re-read UNTIL from the raw string rather than the normalized parse, so
        // a time-of-day component in the rule is honoured to the second.
        val untilStr = rrule.split(";")
            .map { it.split("=", limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == "UNTIL" }
            ?.get(1)
        val pastUntil = untilStr?.let { parseUntil(it) }?.let { next.isAfter(it) } ?: false

        val nextStr = if (hasTime) {
            next.atZone(ZoneId.systemDefault())
                .toOffsetDateTime()
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        } else {
            next.toLocalDate().toString()
        }
        return nextStr to pastUntil
    }

    // RFC 5545 UNTIL: typical forms are 20261231T235959Z or 20261231; we also
    // tolerate the same set the backend tolerates so round-tripping is safe.
    fun parseUntil(s: String): LocalDateTime? {
        val patterns = listOf(
            "yyyyMMdd'T'HHmmss'Z'",
            "yyyyMMdd'T'HHmmss",
            "yyyyMMdd",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd"
        )
        for (p in patterns) {
            try {
                val fmt = DateTimeFormatter.ofPattern(p)
                return if (p == "yyyyMMdd" || p == "yyyy-MM-dd") {
                    LocalDate.parse(s, fmt).atTime(23, 59, 59)
                } else {
                    LocalDateTime.parse(s, fmt)
                }
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }

    // Normalize any UNTIL shape [parseUntil] accepts down to "yyyy-MM-dd".
    private fun normalizeUntil(raw: String): String =
        parseUntil(raw)?.toLocalDate()?.toString() ?: ""
}
