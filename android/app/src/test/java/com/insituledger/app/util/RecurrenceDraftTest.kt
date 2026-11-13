package com.insituledger.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.util.Locale

class RecurrenceDraftTest {

    private val now = LocalDateTime.of(2026, 11, 13, 10, 0)
    private val start = LocalDateTime.of(2026, 11, 20, 9, 0)

    private val legacy = listOf(
        "FREQ=DAILY",
        "FREQ=WEEKLY",
        "FREQ=WEEKLY;INTERVAL=2",
        "FREQ=MONTHLY",
        "FREQ=MONTHLY;INTERVAL=3",
        "FREQ=YEARLY"
    )

    private fun dt(y: Int, m: Int, d: Int, h: Int = 9, min: Int = 0) = LocalDateTime.of(y, m, d, h, min)

    // --- wire strings ---

    @Test
    fun everyPresetMatchesTheLegacyWireString() {
        val keys = listOf("daily", "weekly", "biweekly", "monthly", "quarterly", "yearly")
        assertEquals(legacy, keys.map { RecurrenceDraft(presetKey = it).rrule() })
    }

    @Test
    fun defaultsAreMonthlyNeverWithCustomTwoMonths() {
        val d = RecurrenceDraft()
        assertEquals("FREQ=MONTHLY", d.rrule())
        assertEquals("2", d.customInterval)
        assertEquals(Freq.MONTHLY, d.customUnit)
        assertEquals(EndMode.NEVER, d.endMode)
        assertNull(d.maxOccurrences())
    }

    @Test
    fun customIntervalEncodes() {
        val d = RecurrenceDraft(presetKey = Rrule.CUSTOM_KEY, customInterval = "3", customUnit = Freq.WEEKLY)
        assertEquals("FREQ=WEEKLY;INTERVAL=3", d.rrule())
        assertEquals(Freq.WEEKLY to 3, d.freqInterval())
    }

    @Test
    fun customIntervalOfOneOmitsInterval() {
        val d = RecurrenceDraft(presetKey = Rrule.CUSTOM_KEY, customInterval = "1", customUnit = Freq.DAILY)
        assertEquals("FREQ=DAILY", d.rrule())
    }

    @Test
    fun endDateEncodesEndOfDayUntil() {
        val d = RecurrenceDraft(endMode = EndMode.DATE, endDate = "2026-12-31")
        assertEquals("FREQ=MONTHLY;UNTIL=20261231T235959Z", d.rrule())
        assertNull(d.maxOccurrences())
    }

    @Test
    fun countIsOnlyEmittedInCountMode() {
        assertEquals(12, RecurrenceDraft(endMode = EndMode.COUNT, count = "12").maxOccurrences())
        assertNull(RecurrenceDraft(endMode = EndMode.NEVER, count = "12").maxOccurrences())
        assertNull(RecurrenceDraft(endMode = EndMode.DATE, endDate = "2026-12-31", count = "12").maxOccurrences())
    }

    @Test
    fun lenientCountTreatsBlankAndZeroAsNever() {
        assertNull(RecurrenceDraft(endMode = EndMode.COUNT, count = "").maxOccurrencesLenient())
        assertNull(RecurrenceDraft(endMode = EndMode.COUNT, count = "0").maxOccurrencesLenient())
        assertEquals(1500, RecurrenceDraft(endMode = EndMode.COUNT, count = "1500").maxOccurrencesLenient())
        assertNull(RecurrenceDraft(endMode = EndMode.COUNT, count = "1500").maxOccurrences())
    }

    @Test
    fun fromStoredRoundTripsEveryLegacyString() {
        val all = legacy + listOf(
            "FREQ=DAILY;INTERVAL=45",
            "FREQ=MONTHLY;INTERVAL=3;UNTIL=20261231T235959Z",
            "FREQ=WEEKLY;INTERVAL=5;UNTIL=20270101T235959Z"
        )
        for (s in all) {
            assertEquals(s, RecurrenceDraft.fromStored(s, null).rrule())
            assertEquals(s, RecurrenceDraft.fromStored(s, 4).rrule())
        }
    }

    @Test
    fun fromStoredRestoresEndMode() {
        val count = RecurrenceDraft.fromStored("FREQ=WEEKLY", 12)
        assertEquals(EndMode.COUNT, count.endMode)
        assertEquals("12", count.count)
        val until = RecurrenceDraft.fromStored("FREQ=WEEKLY;UNTIL=20270630T235959Z", 5)
        assertEquals(EndMode.DATE, until.endMode)
        assertEquals("2027-06-30", until.endDate)
        assertNull(until.maxOccurrencesLenient()) // UNTIL wins over a stale max
        val custom = RecurrenceDraft.fromStored("FREQ=DAILY;INTERVAL=45", null)
        assertEquals(Rrule.CUSTOM_KEY, custom.presetKey)
        assertEquals("45", custom.customInterval)
        assertEquals(Freq.DAILY, custom.customUnit)
        assertEquals(EndMode.NEVER, custom.endMode)
    }

    // --- validation ---

    private fun strict(d: RecurrenceDraft, s: LocalDateTime = start) = d.validate(s, now, strict = true)

    @Test
    fun strictRejectsBadCounts() {
        for (bad in listOf("", "0", "1000", "1x")) {
            assertEquals(bad, RecurrenceDraft.MSG_COUNT, strict(RecurrenceDraft(endMode = EndMode.COUNT, count = bad)))
        }
        assertNull(strict(RecurrenceDraft(endMode = EndMode.COUNT, count = "999")))
        assertNull(strict(RecurrenceDraft(endMode = EndMode.COUNT, count = "1")))
    }

    @Test
    fun strictRejectsBadIntervals() {
        for (bad in listOf("", "0", "1000", " 3")) {
            val d = RecurrenceDraft(presetKey = Rrule.CUSTOM_KEY, customInterval = bad)
            assertEquals(bad, RecurrenceDraft.MSG_INTERVAL, strict(d))
        }
        assertNull(strict(RecurrenceDraft(presetKey = Rrule.CUSTOM_KEY, customInterval = "999")))
    }

    @Test
    fun strictRequiresAnEndDateNotBeforeTheStart() {
        assertEquals(RecurrenceDraft.MSG_PICK_END_DATE, strict(RecurrenceDraft(endMode = EndMode.DATE)))
        assertEquals(
            RecurrenceDraft.MSG_END_BEFORE_START,
            strict(RecurrenceDraft(endMode = EndMode.DATE, endDate = "2026-11-19"))
        )
        // Same day as the start is fine (UNTIL is end-of-day).
        assertNull(strict(RecurrenceDraft(endMode = EndMode.DATE, endDate = "2026-11-20")))
    }

    @Test
    fun pastStartIsValid() {
        assertNull(strict(RecurrenceDraft(presetKey = "weekly"), dt(2026, 11, 6, 10)))
    }

    @Test
    fun moreThan366PastEntriesIsAnError() {
        val daily = RecurrenceDraft(presetKey = "daily")
        // 366 entries: start 365 days before today's occurrence.
        assertNull(strict(daily, now.minusDays(365)))
        assertEquals(RecurrenceDraft.MSG_TOO_MANY_PAST, strict(daily, now.minusDays(366)))
        assertEquals(
            "That start date would add more than 366 past entries. Pick a later start date.",
            RecurrenceDraft.MSG_TOO_MANY_PAST
        )
        // A count that ends the series early keeps it valid.
        assertNull(strict(daily.copy(endMode = EndMode.COUNT, count = "10"), now.minusDays(1000)))
    }

    @Test
    fun lenientKeepsTheScheduledFormMessagesAndOrder() {
        val both = RecurrenceDraft(presetKey = Rrule.CUSTOM_KEY, customInterval = "0", endMode = EndMode.DATE)
        assertEquals("Pick an end date", both.validate(start, now, strict = false))
        assertEquals(
            "Repeat every must be a whole number between 1 and 999",
            both.copy(endDate = "2026-12-31").validate(start, now, strict = false)
        )
        // Lenient ignores count bounds, end-before-start and the past cap.
        assertNull(RecurrenceDraft(endMode = EndMode.COUNT, count = "").validate(start, now, strict = false))
        assertNull(RecurrenceDraft(endMode = EndMode.DATE, endDate = "2020-01-01").validate(start, now, strict = false))
        assertNull(RecurrenceDraft(presetKey = "daily").validate(now.minusYears(5), now, strict = false))
    }

    // --- occurrences ---

    @Test
    fun monthEndClampIsSticky() {
        val d = RecurrenceDraft(presetKey = "monthly")
        assertEquals(listOf(dt(2026, 1, 31), dt(2026, 2, 28), dt(2026, 3, 28)), d.upcoming(dt(2026, 1, 31), 3))
    }

    @Test
    fun leapDayYearlyClampsToFebruary28() {
        val d = RecurrenceDraft(presetKey = "yearly")
        assertEquals(listOf(dt(2028, 2, 29), dt(2029, 2, 28)), d.upcoming(dt(2028, 2, 29), 2))
    }

    @Test
    fun countLimitsUpcoming() {
        val d = RecurrenceDraft(presetKey = "weekly", endMode = EndMode.COUNT, count = "2")
        assertEquals(listOf(start, start.plusWeeks(1)), d.upcoming(start, 5))
    }

    @Test
    fun untilStopsAtTheEndDateInclusive() {
        val d = RecurrenceDraft(presetKey = "weekly", endMode = EndMode.DATE, endDate = "2026-12-04")
        assertEquals(listOf(dt(2026, 11, 20), dt(2026, 11, 27), dt(2026, 12, 4)), d.upcoming(start, 10))
    }

    @Test
    fun upcomingAfterSkipsPastEntries() {
        val d = RecurrenceDraft(presetKey = "weekly")
        assertEquals(
            listOf(dt(2026, 11, 20, 10), dt(2026, 11, 27, 10)),
            d.upcoming(dt(2026, 11, 6, 10), 2, after = now)
        )
    }

    @Test
    fun weeklyPastStartAddsSixAndThirteenNovember() {
        val d = RecurrenceDraft(presetKey = "weekly")
        assertEquals(
            listOf(dt(2026, 11, 6, 10), dt(2026, 11, 13, 10)),
            d.pastOccurrences(dt(2026, 11, 6, 10), now, cap = 400)
        )
    }

    @Test
    fun pastOccurrencesUseMinutePrecision() {
        val d = RecurrenceDraft(presetKey = "weekly")
        val nowWithSeconds = now.plusSeconds(30)
        assertEquals(2, d.pastOccurrences(dt(2026, 11, 6, 10), nowWithSeconds, cap = 400).size)
        assertEquals(1, d.pastOccurrences(dt(2026, 11, 6, 10, 1), nowWithSeconds, cap = 400).size)
    }

    @Test
    fun pastOccurrencesHonourCountAndUntilAndCap() {
        val weekly = RecurrenceDraft(presetKey = "weekly")
        val from = dt(2026, 10, 2, 10) // 7 weekly occurrences up to 13 Nov
        assertEquals(7, weekly.pastOccurrences(from, now, cap = 400).size)
        assertEquals(3, weekly.copy(endMode = EndMode.COUNT, count = "3").pastOccurrences(from, now, cap = 400).size)
        assertEquals(
            listOf(dt(2026, 10, 2, 10), dt(2026, 10, 9, 10)),
            weekly.copy(endMode = EndMode.DATE, endDate = "2026-10-12").pastOccurrences(from, now, cap = 400)
        )
        assertEquals(4, weekly.pastOccurrences(from, now, cap = 4).size)
    }

    @Test
    fun futureStartHasNoPastOccurrences() {
        assertTrue(RecurrenceDraft().pastOccurrences(start, now, cap = 400).isEmpty())
    }

    // --- text ---

    @Test
    fun summaries() {
        val en = Locale.ENGLISH
        assertEquals("Monthly", RecurrenceDraft().summary(start, en))
        assertEquals(
            "Every 3 weeks",
            RecurrenceDraft(presetKey = Rrule.CUSTOM_KEY, customInterval = "3", customUnit = Freq.WEEKLY).summary(start, en)
        )
        assertEquals("Biweekly", RecurrenceDraft(presetKey = Rrule.CUSTOM_KEY, customInterval = "2", customUnit = Freq.WEEKLY).summary(start, en))
        assertEquals("Monthly · 12 times", RecurrenceDraft(endMode = EndMode.COUNT, count = "12").summary(start, en))
        assertEquals("Weekly · once", RecurrenceDraft(presetKey = "weekly", endMode = EndMode.COUNT, count = "1").summary(start, en))
        assertEquals(
            "Monthly · until 31 Dec 2027",
            RecurrenceDraft(endMode = EndMode.DATE, endDate = "2027-12-31").summary(start, en)
        )
        assertEquals(
            "Monthly · until 31 Dec",
            RecurrenceDraft(endMode = EndMode.DATE, endDate = "2026-12-31").summary(start, en)
        )
    }

    @Test
    fun anchorHints() {
        val en = Locale.ENGLISH
        val fri = dt(2026, 11, 14).minusDays(1) // Friday 13 Nov
        assertEquals("on Fridays", RecurrenceDraft(presetKey = "weekly").anchorHint(fri, en))
        assertEquals("on Fridays", RecurrenceDraft(presetKey = "biweekly").anchorHint(fri, en))
        assertEquals("on day 14", RecurrenceDraft(presetKey = "monthly").anchorHint(dt(2026, 11, 14), en))
        assertEquals("on day 14", RecurrenceDraft(presetKey = "quarterly").anchorHint(dt(2026, 11, 14), en))
        assertEquals("every 14 Nov", RecurrenceDraft(presetKey = "yearly").anchorHint(dt(2026, 11, 14), en))
        assertEquals("", RecurrenceDraft(presetKey = "daily").anchorHint(dt(2026, 11, 14), en))
    }

    @Test
    fun sanitizeNumberKeepsThreeDigits() {
        assertEquals("12", RecurrenceDraft.sanitizeNumber("1a2"))
        assertEquals("100", RecurrenceDraft.sanitizeNumber("1000"))
    }
}
