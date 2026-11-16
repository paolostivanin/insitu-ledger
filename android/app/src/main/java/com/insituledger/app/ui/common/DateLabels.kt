package com.insituledger.app.ui.common

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Human date labels. Stored datetimes are rendered by their own wall-clock
 * (the offset is display-irrelevant by design, see DateTimeUtil.displayDate),
 * so these take the "yyyy-MM-dd" prefix of the stored string.
 */
object DateLabels {
    // Resolved on use so a locale change while the app runs is honoured.
    private val dayInYear get() = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())
    private val dayWithYear get() = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())
    private val shortInYear get() = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    private val shortWithYear get() = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

    fun parseDay(stored: String): LocalDate? =
        try { LocalDate.parse(stored.take(10)) } catch (_: Exception) { null }

    /** "HH:mm" of a stored datetime, or null for date-only values. */
    fun time(stored: String): String? =
        if (stored.length >= 16 && stored[10] == 'T') stored.substring(11, 16) else null

    /** "Today", "Yesterday", "Wed, 11 Nov", or "Wed, 11 Nov 2025". */
    fun day(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        today.plusDays(1) -> "Tomorrow"
        else -> if (date.year == today.year) dayInYear.format(date) else dayWithYear.format(date)
    }

    /** "13 Nov" in the current year, "13 Nov 2025" otherwise. */
    fun short(date: LocalDate, today: LocalDate = LocalDate.now()): String =
        if (date.year == today.year) shortInYear.format(date) else shortWithYear.format(date)

    /** "Today · 14:05" style label for a stored datetime. */
    fun dayAndTime(stored: String, today: LocalDate = LocalDate.now()): String {
        val day = parseDay(stored) ?: return stored
        val t = time(stored)
        return if (t != null) "${day(day, today)} · $t" else day(day, today)
    }
}
