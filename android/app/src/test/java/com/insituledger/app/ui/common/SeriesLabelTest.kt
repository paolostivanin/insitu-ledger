package com.insituledger.app.ui.common

import com.insituledger.app.domain.model.ScheduledTransaction
import com.insituledger.app.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesLabelTest {
    private val plain = Transaction(1, 1, 1, 1, "expense", 950.0, "EUR", "Rent", null, "2026-11-01")

    private fun entry(scheduledId: Long? = 7, occurrence: Int? = null, total: Int? = null) =
        plain.copy(scheduledId = scheduledId, occurrence = occurrence, occurrenceTotal = total)

    private fun schedule(rrule: String = "FREQ=MONTHLY", max: Int? = null, id: Long = 7) = ScheduledTransaction(
        id = id, accountId = 1, categoryId = 1, userId = 1, type = "expense", amount = 950.0,
        currency = "EUR", description = "Rent", note = null, rrule = rrule,
        nextOccurrence = "2026-12-01", active = true, maxOccurrences = max
    )

    @Test fun `ordinary entries have no label`() {
        assertNull(seriesLabel(plain, schedule()))
        assertNull(seriesLabel(plain, null))
        assertNull(seriesLabel(plain, mapOf(7L to schedule())))
    }

    @Test fun `bounded series shows frequency and position`() {
        assertEquals("Monthly · 2 of 3", seriesLabel(entry(occurrence = 2, total = 3), schedule(max = 3)))
        assertEquals("Every 2 months · 1 of 6", seriesLabel(entry(occurrence = 1, total = 6), schedule("FREQ=MONTHLY;INTERVAL=2", 6)))
    }

    @Test fun `open-ended series shows only the frequency`() {
        assertEquals("Monthly", seriesLabel(entry(occurrence = 5), schedule()))
        assertEquals("Biweekly", seriesLabel(entry(occurrence = 2), schedule("FREQ=WEEKLY;INTERVAL=2")))
        // The position is stored, but the series has no length to count against.
        assertEquals("Weekly", seriesLabel(entry(occurrence = 9, total = null), schedule("FREQ=WEEKLY", max = 12)))
    }

    @Test fun `one-time schedules read Scheduled`() {
        // By the stored series length...
        assertEquals("Scheduled", seriesLabel(entry(occurrence = 1, total = 1), schedule("FREQ=DAILY", 1)))
        assertEquals("Scheduled", seriesLabel(entry(occurrence = 1, total = 1), schedule("FREQ=MONTHLY", 1)))
        assertEquals("Scheduled", seriesLabel(entry(occurrence = 1, total = 1), null))
        // ...or by the schedule's shape (FREQ=DAILY, interval 1, max 1) for backfilled history.
        assertEquals("Scheduled", seriesLabel(entry(), schedule("FREQ=DAILY", 1)))
    }

    @Test fun `a backfilled entry from a capped non-daily schedule keeps its frequency`() {
        assertEquals("Monthly", seriesLabel(entry(), schedule("FREQ=MONTHLY", 1)))
        // Daily but not every day, or not capped at one: a real series.
        assertEquals("Every 2 days", seriesLabel(entry(), schedule("FREQ=DAILY;INTERVAL=2", 1)))
        assertEquals("Daily", seriesLabel(entry(), schedule("FREQ=DAILY", 2)))
    }

    @Test fun `a missing schedule only replaces the frequency word`() {
        assertEquals("Recurring · 2 of 3", seriesLabel(entry(occurrence = 2, total = 3), null))
        assertEquals("Recurring", seriesLabel(entry(occurrence = 4), null))
        assertEquals("Recurring", seriesLabel(entry(), null))
        // Same while schedules are still loading (empty lookup) or the id isn't in it.
        assertEquals("Recurring", seriesLabel(entry(), emptyMap()))
        assertEquals("Recurring · 1 of 2", seriesLabel(entry(occurrence = 1, total = 2), mapOf(8L to schedule(id = 8))))
    }

    @Test fun `a position past the series length shows only the frequency`() {
        // max_occurrences was lowered mid-series: never "4 of 2".
        assertEquals("Monthly", seriesLabel(entry(occurrence = 4, total = 2), schedule(max = 2)))
        assertEquals("Recurring", seriesLabel(entry(occurrence = 4, total = 2), null))
        // The last entry of a series is still counted.
        assertEquals("Monthly · 2 of 2", seriesLabel(entry(occurrence = 2, total = 2), schedule(max = 2)))
    }

    @Test fun `the lookup overload resolves the schedule by id`() {
        val schedules = mapOf(7L to schedule("FREQ=YEARLY", 3), 8L to schedule("FREQ=WEEKLY", id = 8))
        assertEquals("Yearly · 3 of 3", seriesLabel(entry(occurrence = 3, total = 3), schedules))
        assertEquals("Weekly", seriesLabel(entry(scheduledId = 8, occurrence = 1), schedules))
    }

    @Test fun `the series label leads the supporting line`() {
        assertEquals("Monthly · 2 of 3 · Rent · 10:00", supportingText("Monthly · 2 of 3", "Rent · 10:00"))
        assertEquals("Rent · 10:00", supportingText(null, "Rent · 10:00"))
        assertEquals("Scheduled", supportingText("Scheduled", ""))
    }
}
