package com.insituledger.app.ui.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * resolveRange decides what every figure on the Reports screen covers — the
 * summary cards, the category breakdown and the search totals. `today` is a
 * parameter precisely so these boundaries can be pinned rather than inferred
 * from whatever day the suite happens to run on.
 */
class ReportsResolveRangeTest {

    // A Thursday, deliberately: it distinguishes a Monday week start from a
    // Sunday one, and sits far enough into the year to catch a bad Jan 1.
    private val thursday = LocalDate.of(2026, 9, 10)

    private fun range(
        preset: DateRangePreset,
        from: String = "",
        to: String = "",
        weekStart: DayOfWeek = DayOfWeek.MONDAY,
        today: LocalDate = thursday
    ) = resolveRange(preset, from, to, weekStart, today)

    @Test
    fun `this year runs from January 1st to today, not to December 31st`() {
        val (from, to) = range(DateRangePreset.THIS_YEAR)

        assertEquals("2026-01-01", from)
        assertEquals("2026-09-10", to)
    }

    // The upper bound is today everywhere, so a transaction dated forward —
    // reachable by editing a date, CSV import or restore — is outside the
    // "this ..." presets by design and only shows under All Time.
    @Test
    fun `this year on January 1st is a single day, not the whole year`() {
        val (from, to) = range(DateRangePreset.THIS_YEAR, today = LocalDate.of(2026, 1, 1))

        assertEquals("2026-01-01", from)
        assertEquals("2026-01-01", to)
    }

    @Test
    fun `all time is unbounded at both ends`() {
        val (from, to) = range(DateRangePreset.ALL_TIME)

        assertNull(from)
        assertNull(to)
    }

    // Same bounds as ALL_TIME, which is why picking Custom before filling the
    // fields in widens the page rather than emptying it.
    @Test
    fun `custom with blank bounds is unbounded`() {
        val (from, to) = range(DateRangePreset.CUSTOM)

        assertNull(from)
        assertNull(to)
    }

    @Test
    fun `custom keeps one bound when only the other is set`() {
        assertEquals("2026-03-01" to null, range(DateRangePreset.CUSTOM, from = "2026-03-01"))
        assertEquals(null to "2026-03-31", range(DateRangePreset.CUSTOM, to = "2026-03-31"))
    }

    @Test
    fun `this week honours the configured week start`() {
        assertEquals("2026-09-07" to "2026-09-10", range(DateRangePreset.THIS_WEEK))
        assertEquals(
            "2026-09-06" to "2026-09-10",
            range(DateRangePreset.THIS_WEEK, weekStart = DayOfWeek.SUNDAY)
        )
    }

    @Test
    fun `last week is the seven days before this week started`() {
        assertEquals("2026-08-31" to "2026-09-06", range(DateRangePreset.LAST_WEEK))
        assertEquals(
            "2026-08-30" to "2026-09-05",
            range(DateRangePreset.LAST_WEEK, weekStart = DayOfWeek.SUNDAY)
        )
    }

    @Test
    fun `last month rolls back over January into the previous year`() {
        val (from, to) = range(DateRangePreset.LAST_MONTH, today = LocalDate.of(2026, 1, 15))

        assertEquals("2025-12-01", from)
        assertEquals("2025-12-31", to)
    }

    // Whole calendar months only — the current, incomplete month would drag
    // the average down and make the figure unreadable.
    @Test
    fun `last 3 months excludes the current month`() {
        val (from, to) = range(DateRangePreset.LAST_3_MONTHS)

        assertEquals("2026-06-01", from)
        assertEquals("2026-08-31", to)
    }

    @Test
    fun `last year is the whole previous calendar year`() {
        val (from, to) = range(DateRangePreset.LAST_YEAR)

        assertEquals("2025-01-01", from)
        assertEquals("2025-12-31", to)
    }

    @Test
    fun `this month starts on the 1st and stops at today`() {
        val (from, to) = range(DateRangePreset.THIS_MONTH)

        assertEquals("2026-09-01", from)
        assertEquals("2026-09-10", to)
    }
}
