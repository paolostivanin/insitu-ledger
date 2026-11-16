package com.insituledger.app.ui.common

import com.insituledger.app.domain.model.ScheduledTransaction
import com.insituledger.app.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private fun text(tx: Transaction, s: ScheduledTransaction?) = seriesBadge(tx, s)?.text

    @Test fun `ordinary entries have no badge`() {
        assertNull(seriesBadge(plain, schedule()))
        assertNull(seriesBadge(plain, null))
        assertNull(seriesBadge(plain, mapOf(7L to schedule())))
    }

    @Test fun `bounded series shows frequency and a compact counter`() {
        val badge = seriesBadge(entry(occurrence = 2, total = 3), schedule(max = 3))!!
        assertEquals("Monthly · 2/3", badge.text)
        assertEquals("Recurring monthly, 2 of 3", badge.spoken)
        assertFalse(badge.oneTime)
        assertEquals("Every 2 months · 1/6", text(entry(occurrence = 1, total = 6), schedule("FREQ=MONTHLY;INTERVAL=2", 6)))
    }

    @Test fun `open-ended series shows the infinity mark`() {
        val badge = seriesBadge(entry(occurrence = 5), schedule())!!
        assertEquals("Monthly · ∞", badge.text)
        assertEquals("Recurring monthly, no end date", badge.spoken)
        assertEquals("Biweekly · ∞", text(entry(occurrence = 2), schedule("FREQ=WEEKLY;INTERVAL=2")))
    }

    @Test fun `backfilled history uses the schedule to tell open-ended from bounded`() {
        // No stored position: open-ended schedule → ∞, bounded schedule → frequency only.
        assertEquals("Monthly · ∞", text(entry(), schedule()))
        assertEquals("Monthly", text(entry(), schedule(max = 12)))
    }

    @Test fun `one-time schedules read Once with the clock icon`() {
        val byLength = seriesBadge(entry(occurrence = 1, total = 1), null)!!
        assertEquals("Once", byLength.text)
        assertTrue(byLength.oneTime)
        assertEquals("Scheduled once", byLength.spoken)
        // By the schedule's rule: daily, interval 1, capped at 1.
        assertEquals("Once", text(entry(), schedule("FREQ=DAILY", max = 1)))
        // A capped daily series of more than one, or a monthly cap of 1 by rrule alone, is not one-time.
        assertEquals("Daily", text(entry(), schedule("FREQ=DAILY", max = 3)))
        assertEquals("Monthly", text(entry(), schedule("FREQ=MONTHLY", max = 1)))
    }

    @Test fun `unknown schedule falls back to Recurring and keeps the counter`() {
        assertEquals("Recurring · 2/3", text(entry(occurrence = 2, total = 3), null))
        assertEquals("Recurring, 2 of 3", seriesBadge(entry(occurrence = 2, total = 3), null)!!.spoken)
        assertEquals("Recurring · ∞", text(entry(occurrence = 4), null))
        assertEquals("Recurring", text(entry(), null))
    }

    @Test fun `position past a lowered length shows only the frequency`() {
        assertEquals("Monthly", text(entry(occurrence = 4, total = 2), schedule(max = 2)))
        assertEquals("Recurring", text(entry(occurrence = 4, total = 2), null))
    }

    @Test fun `the lookup overload resolves the schedule by id`() {
        val schedules = mapOf(7L to schedule("FREQ=YEARLY", 3), 8L to schedule("FREQ=WEEKLY", id = 8))
        assertEquals("Yearly · 3/3", seriesBadge(entry(occurrence = 3, total = 3), schedules)?.text)
        assertEquals("Weekly · ∞", seriesBadge(entry(scheduledId = 8, occurrence = 1), schedules)?.text)
    }
}
