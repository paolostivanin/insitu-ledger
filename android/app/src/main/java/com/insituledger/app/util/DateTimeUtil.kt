package com.insituledger.app.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object DateTimeUtil {

    // Canonical on-device format the app writes back. Backend-side timestamps
    // can include seconds, milliseconds, or an offset; older payloads may be
    // date-only. Parser must accept all of these.
    private val WRITE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    fun formatLocalDateTime(value: LocalDateTime): String = value.format(WRITE_FORMAT)

    // Form dates are written at minute precision with the zone's offset, e.g.
    // "2026-11-20T09:00+01:00" ("…Z" in UTC). The backend's parseClientDate
    // accepts this shape ("2006-01-02T15:04Z07:00") alongside full RFC3339.
    private val FORM_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmXXX")

    fun parseFlexibleLocalDateTime(value: String, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime {
        val trimmed = value.trim()

        // ISO offset (e.g. 2026-04-20T12:30:00Z or +02:00) — convert to local.
        if (trimmed.endsWith("Z") || trimmed.matches(OFFSET_REGEX)) {
            return try {
                OffsetDateTime.parse(trimmed)
                    .atZoneSameInstant(zone)
                    .toLocalDateTime()
            } catch (_: Exception) {
                parseLenientLocal(trimmed)
            }
        }

        return parseLenientLocal(trimmed)
    }

    // Parse a form/entity date string of ANY legacy or new shape into an
    // OffsetDateTime, preserving the value's own wall-clock. Legacy naive
    // strings get the system zone's offset FOR THAT DATETIME (DST-correct) —
    // they NEVER fall back to "now", which would silently reset the date of a
    // pre-1.28 local-mode row when the user edits it. Only truly unparseable
    // garbage falls back to now (never throws — this is called from Compose
    // picker callbacks where an exception would crash the UI).
    fun parseFormDate(s: String): OffsetDateTime =
        try {
            OffsetDateTime.parse(s)
        } catch (_: Exception) {
            try {
                parseFlexibleLocalDateTime(s)
                    .atZone(ZoneId.systemDefault())
                    .toOffsetDateTime()
            } catch (_: Exception) {
                OffsetDateTime.now(ZoneId.systemDefault())
                    .truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
            }
        }

    /**
     * Strict counterpart of [parseFormDate] for save/routing decisions: accepts
     * the same shapes (RFC3339 with offset, legacy naive datetimes and bare
     * dates, the latter two read in [zone]) but returns null for anything else.
     * It never substitutes "now" — a garbage date must block the write.
     */
    fun parseFormDateOrNull(s: String, zone: ZoneId = ZoneId.systemDefault()): OffsetDateTime? {
        val trimmed = s.trim()
        if (trimmed.isEmpty()) return null
        return try {
            OffsetDateTime.parse(trimmed)
        } catch (_: Exception) {
            try {
                parseFlexibleLocalDateTime(trimmed, zone).atZone(zone).toOffsetDateTime()
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Compose a form date from a wall-clock date and time in [zone], at minute
     * precision with that moment's offset ("2026-11-20T09:00+01:00", "…Z" in
     * UTC). Via [ZonedDateTime.of]: a time inside a DST gap shifts forward by
     * the gap length (02:30 → 03:30), an overlap takes the earlier offset.
     */
    fun composeFormDate(date: LocalDate, time: LocalTime, zone: ZoneId): String =
        ZonedDateTime.of(date, time.truncatedTo(ChronoUnit.MINUTES), zone).format(FORM_FORMAT)

    /** True when [time] on [date] does not exist in [zone] (spring-forward gap). */
    fun isInDstGap(date: LocalDate, time: LocalTime, zone: ZoneId): Boolean =
        zone.rules.getValidOffsets(LocalDateTime.of(date, time)).isEmpty()

    /** Replace the date of a form date, keeping its time of day. See [rebuild]. */
    fun withDatePart(original: String, date: LocalDate, zone: ZoneId): String =
        rebuild(original, zone) { wall -> LocalDateTime.of(date, wall.toLocalTime()) }

    /** Replace the time of day of a form date, keeping its date. See [rebuild]. */
    fun withTimePart(original: String, time: LocalTime, zone: ZoneId): String =
        rebuild(original, zone) { wall -> LocalDateTime.of(wall.toLocalDate(), time) }

    // Picker edits keep the value's own wall clock. The offset is recomputed
    // for the new moment (so moving a November date into July yields +02:00 in
    // Rome) unless the original carried a foreign offset — an entry made while
    // travelling — which is kept as typed. Unparseable input starts from now.
    private inline fun rebuild(
        original: String,
        zone: ZoneId,
        change: (LocalDateTime) -> LocalDateTime
    ): String {
        val parsed = parseFormDateOrNull(original, zone)
            ?: OffsetDateTime.now(zone).truncatedTo(ChronoUnit.MINUTES)
        val wall = change(parsed.toLocalDateTime()).truncatedTo(ChronoUnit.MINUTES)
        val zoneOffsetAtOriginal = zone.rules.getOffset(parsed.toInstant())
        return if (parsed.offset != zoneOffsetAtOriginal) {
            OffsetDateTime.of(wall, parsed.offset).format(FORM_FORMAT)
        } else {
            composeFormDate(wall.toLocalDate(), wall.toLocalTime(), zone)
        }
    }

    // Render an ISO-8601 string as "YYYY-MM-DD HH:mm" using the local-time
    // portion only (strips any TZ offset or Z suffix, with or without seconds).
    // Falls back to input for date-only / unrecognized formats.
    fun displayDate(iso: String): String {
        if (iso.isEmpty()) return ""
        val t = iso.indexOf('T')
        if (t < 0) return iso // date-only
        var end = iso.length
        for (i in 11 until iso.length) {
            val c = iso[i]
            if (c == '+' || c == 'Z' || c == '-') {
                end = i
                break
            }
        }
        val core = iso.substring(0, end).replace('T', ' ')
        return if (core.length > 16) core.substring(0, 16) else core
    }

    // Extract just the "HH:mm" portion. Returns empty string for date-only inputs.
    fun extractTime(iso: String): String {
        if (iso.isEmpty() || !iso.contains('T')) return ""
        val afterT = iso.substring(11)
        var end = afterT.length
        for (i in afterT.indices) {
            val c = afterT[i]
            if (c == '+' || c == 'Z' || c == '-') {
                end = i
                break
            }
        }
        val time = afterT.substring(0, end)
        return if (time.length >= 5) time.substring(0, 5) else time
    }

    private fun parseLenientLocal(value: String): LocalDateTime {
        // Date-only fallback (e.g. 2026-04-20) → start of day.
        if (!value.contains('T') && !value.contains(' ')) {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay()
        }
        // Normalize "yyyy-MM-dd HH:mm" → "yyyy-MM-dd'T'HH:mm" so ISO_LOCAL_DATE_TIME accepts it.
        val normalized = value.replace(' ', 'T')
        return LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }

    private val OFFSET_REGEX = Regex(".*[+-]\\d{2}:?\\d{2}$")
}
