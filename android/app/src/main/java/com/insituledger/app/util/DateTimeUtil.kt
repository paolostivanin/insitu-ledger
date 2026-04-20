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
