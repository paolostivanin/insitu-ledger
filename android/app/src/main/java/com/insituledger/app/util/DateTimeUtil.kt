package com.insituledger.app.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object DateTimeUtil {

    // Canonical on-device format the app writes back. Backend-side timestamps
    // can include seconds, milliseconds, or an offset; older payloads may be
    // date-only. Parser must accept all of these.
    private val WRITE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    fun formatLocalDateTime(value: LocalDateTime): String = value.format(WRITE_FORMAT)

    fun parseFlexibleLocalDateTime(value: String): LocalDateTime {
        val trimmed = value.trim()

        // ISO offset (e.g. 2026-04-20T12:30:00Z or +02:00) — convert to local.
        if (trimmed.endsWith("Z") || trimmed.matches(OFFSET_REGEX)) {
            return try {
                OffsetDateTime.parse(trimmed)
                    .atZoneSameInstant(ZoneId.systemDefault())
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
