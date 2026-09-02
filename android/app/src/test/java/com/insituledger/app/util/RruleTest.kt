package com.insituledger.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class RruleTest {

    companion object {
        // Every rrule string a shipped client has ever written. The TypeScript
        // twin (frontend/src/lib/rrule.test.ts) has this same table verbatim —
        // keep them in step.
        private val LEGACY_WIRE_STRINGS = listOf(
            "FREQ=DAILY",
            "FREQ=WEEKLY",
            "FREQ=WEEKLY;INTERVAL=2",
            "FREQ=MONTHLY",
            "FREQ=MONTHLY;INTERVAL=3",
            "FREQ=YEARLY"
        )
    }

    // --- parse ---

    @Test
    fun parsesEveryPreset() {
        assertEquals(ParsedRrule(Freq.DAILY, 1, ""), Rrule.parse("FREQ=DAILY"))
        assertEquals(ParsedRrule(Freq.WEEKLY, 1, ""), Rrule.parse("FREQ=WEEKLY"))
        assertEquals(ParsedRrule(Freq.WEEKLY, 2, ""), Rrule.parse("FREQ=WEEKLY;INTERVAL=2"))
        assertEquals(ParsedRrule(Freq.MONTHLY, 1, ""), Rrule.parse("FREQ=MONTHLY"))
        assertEquals(ParsedRrule(Freq.MONTHLY, 3, ""), Rrule.parse("FREQ=MONTHLY;INTERVAL=3"))
        assertEquals(ParsedRrule(Freq.YEARLY, 1, ""), Rrule.parse("FREQ=YEARLY"))
    }

    @Test
    fun parsesCustomInterval() {
        assertEquals(ParsedRrule(Freq.MONTHLY, 2, ""), Rrule.parse("FREQ=MONTHLY;INTERVAL=2"))
        assertEquals(45, Rrule.parse("FREQ=DAILY;INTERVAL=45").interval)
    }

    @Test
    fun normalizesEveryAcceptedUntilShape() {
        assertEquals("2026-12-31", Rrule.parse("FREQ=MONTHLY;UNTIL=20261231T235959Z").until)
        assertEquals("2026-12-31", Rrule.parse("FREQ=MONTHLY;UNTIL=20261231T235959").until)
        assertEquals("2026-12-31", Rrule.parse("FREQ=MONTHLY;UNTIL=20261231").until)
        assertEquals("2026-12-31", Rrule.parse("FREQ=MONTHLY;UNTIL=2026-12-31").until)
        assertEquals("2026-12-31", Rrule.parse("FREQ=MONTHLY;UNTIL=2026-12-31T23:59:59").until)
    }

    @Test
    fun ignoresUnrecognizedUntil() {
        assertEquals("", Rrule.parse("FREQ=MONTHLY;UNTIL=nonsense").until)
    }

    @Test
    fun parseIsOrderIndependent() {
        assertEquals(ParsedRrule(Freq.WEEKLY, 2, ""), Rrule.parse("INTERVAL=2;FREQ=WEEKLY"))
        assertEquals(
            ParsedRrule(Freq.DAILY, 4, "2026-12-31"),
            Rrule.parse("UNTIL=20261231T235959Z;FREQ=DAILY;INTERVAL=4")
        )
    }

    @Test
    fun fallsBackToIntervalOneForBadValues() {
        assertEquals(1, Rrule.parse("FREQ=DAILY;INTERVAL=0").interval)
        assertEquals(1, Rrule.parse("FREQ=DAILY;INTERVAL=abc").interval)
        assertEquals(1, Rrule.parse("FREQ=DAILY;INTERVAL=-2").interval)
        assertEquals(1, Rrule.parse("FREQ=DAILY;INTERVAL=").interval)
    }

    @Test
    fun skipsUnknownKeysAndMalformedSegments() {
        assertEquals(
            ParsedRrule(Freq.WEEKLY, 3, ""),
            Rrule.parse("FREQ=WEEKLY;BYDAY=MO;;INTERVAL=3;junk")
        )
    }

    @Test
    fun fallsBackToMonthlyForMissingOrGarbageFreq() {
        assertEquals(ParsedRrule(Freq.MONTHLY, 1, ""), Rrule.parse(""))
        assertEquals(ParsedRrule(Freq.MONTHLY, 2, ""), Rrule.parse("INTERVAL=2"))
        assertEquals(ParsedRrule(Freq.MONTHLY, 1, ""), Rrule.parse("FREQ=HOURLY"))
        assertEquals(ParsedRrule(Freq.MONTHLY, 1, ""), Rrule.parse("total garbage"))
    }

    // --- build ---

    @Test
    fun buildMatchesLegacyWireStrings() {
        // A refactor must not silently change the wire format: these are the
        // exact strings every shipped client has written since v1.0.
        assertEquals(LEGACY_WIRE_STRINGS, Rrule.PRESETS.map { Rrule.build(it.freq, it.interval) })
    }

    @Test
    fun buildOmitsIntervalOne() {
        // Non-negotiable: pre-v1.33.0 clients match the rrule by exact string,
        // so "FREQ=WEEKLY;INTERVAL=1" would degrade to "monthly" and overwrite
        // the real recurrence on save.
        assertEquals("FREQ=WEEKLY", Rrule.build(Freq.WEEKLY, 1))
        assertEquals("FREQ=DAILY", Rrule.build(Freq.DAILY, 1))
    }

    @Test
    fun buildEmitsCustomInterval() {
        assertEquals("FREQ=MONTHLY;INTERVAL=2", Rrule.build(Freq.MONTHLY, 2))
        assertEquals("FREQ=DAILY;INTERVAL=45", Rrule.build(Freq.DAILY, 45))
        assertEquals("FREQ=YEARLY;INTERVAL=999", Rrule.build(Freq.YEARLY, Rrule.MAX_INTERVAL))
    }

    @Test
    fun buildKeepsKeyOrderAndCompactUntil() {
        assertEquals("FREQ=MONTHLY;UNTIL=20261231T235959Z", Rrule.build(Freq.MONTHLY, 1, "2026-12-31"))
        assertEquals(
            "FREQ=WEEKLY;INTERVAL=3;UNTIL=20261231T235959Z",
            Rrule.build(Freq.WEEKLY, 3, "2026-12-31")
        )
        assertEquals("FREQ=MONTHLY", Rrule.build(Freq.MONTHLY, 1, ""))
    }

    @Test
    fun roundTripsEveryKnownString() {
        // The regression guard for the data-loss bug: before v1.33.0 the form
        // reverse-mapped an rrule by exact equality, so anything unrecognized
        // became "monthly" and saving silently rewrote the real recurrence.
        val cases = LEGACY_WIRE_STRINGS +
            LEGACY_WIRE_STRINGS.map { "$it;UNTIL=20261231T235959Z" } +
            listOf(
                "FREQ=MONTHLY;INTERVAL=2",
                "FREQ=DAILY;INTERVAL=45",
                "FREQ=WEEKLY;INTERVAL=3;UNTIL=20261231T235959Z",
                "FREQ=YEARLY;INTERVAL=999"
            )
        for (s in cases) {
            val p = Rrule.parse(s)
            assertEquals(s, Rrule.build(p.freq, p.interval, p.until))
        }
    }

    // --- label / presetKey ---

    @Test
    fun labelsEveryPreset() {
        assertEquals("Daily", Rrule.label("FREQ=DAILY"))
        assertEquals("Weekly", Rrule.label("FREQ=WEEKLY"))
        assertEquals("Biweekly", Rrule.label("FREQ=WEEKLY;INTERVAL=2"))
        assertEquals("Monthly", Rrule.label("FREQ=MONTHLY"))
        assertEquals("Quarterly", Rrule.label("FREQ=MONTHLY;INTERVAL=3"))
        assertEquals("Yearly", Rrule.label("FREQ=YEARLY"))
    }

    @Test
    fun labelIgnoresUntilWhenPresetMatching() {
        // Pre-v1.33.0 this leaked the raw string for every ending schedule.
        assertEquals("Biweekly", Rrule.label("FREQ=WEEKLY;INTERVAL=2;UNTIL=20261231T235959Z"))
        assertEquals("Daily", Rrule.label("FREQ=DAILY;UNTIL=20261231T235959Z"))
    }

    @Test
    fun labelsCustomIntervals() {
        assertEquals("Every 2 months", Rrule.label("FREQ=MONTHLY;INTERVAL=2"))
        assertEquals("Every 10 days", Rrule.label("FREQ=DAILY;INTERVAL=10"))
        assertEquals("Every 5 weeks", Rrule.label("FREQ=WEEKLY;INTERVAL=5"))
        assertEquals("Every 2 years", Rrule.label("FREQ=YEARLY;INTERVAL=2"))
    }

    @Test
    fun labelNeverLeaksRawString() {
        assertEquals("Daily", Rrule.label("FREQ=DAILY;INTERVAL=1"))
        assertEquals("Monthly", Rrule.label("total garbage"))
    }

    @Test
    fun presetKeyResolvesPairs() {
        assertEquals("daily", Rrule.presetKey(Freq.DAILY, 1))
        assertEquals("weekly", Rrule.presetKey(Freq.WEEKLY, 1))
        assertEquals("biweekly", Rrule.presetKey(Freq.WEEKLY, 2))
        assertEquals("monthly", Rrule.presetKey(Freq.MONTHLY, 1))
        assertEquals("quarterly", Rrule.presetKey(Freq.MONTHLY, 3))
        assertEquals("yearly", Rrule.presetKey(Freq.YEARLY, 1))
    }

    @Test
    fun presetKeyReturnsCustomOtherwise() {
        assertEquals(Rrule.CUSTOM_KEY, Rrule.presetKey(Freq.MONTHLY, 2))
        assertEquals(Rrule.CUSTOM_KEY, Rrule.presetKey(Freq.WEEKLY, 3))
        assertEquals(Rrule.CUSTOM_KEY, Rrule.presetKey(Freq.YEARLY, 2))
        assertEquals(Rrule.CUSTOM_KEY, Rrule.presetKey(Freq.DAILY, 45))
    }

    @Test
    fun unitLabelIsSingularForOne() {
        assertEquals("day", Rrule.unitLabel(Freq.DAILY, 1))
        assertEquals("days", Rrule.unitLabel(Freq.DAILY, 2))
        assertEquals("week", Rrule.unitLabel(Freq.WEEKLY, 1))
        assertEquals("weeks", Rrule.unitLabel(Freq.WEEKLY, 3))
        assertEquals("month", Rrule.unitLabel(Freq.MONTHLY, 1))
        assertEquals("months", Rrule.unitLabel(Freq.MONTHLY, 2))
        assertEquals("year", Rrule.unitLabel(Freq.YEARLY, 1))
        assertEquals("years", Rrule.unitLabel(Freq.YEARLY, 4))
    }

    // --- advance ---

    @Test
    fun advanceClampsMonthEnd() {
        // Same table as TestAdvanceDateMonthEndClamps in the Go scheduler —
        // this is the cross-platform parity check.
        val cases = listOf(
            Triple("2026-01-31T09:00", Freq.MONTHLY to 1, "2026-02-28T09:00"),
            Triple("2024-01-31T09:00", Freq.MONTHLY to 1, "2024-02-29T09:00"),
            Triple("2026-01-31T09:00", Freq.MONTHLY to 3, "2026-04-30T09:00"),
            Triple("2026-03-31T09:00", Freq.MONTHLY to 1, "2026-04-30T09:00"),
            Triple("2026-05-15T09:00", Freq.MONTHLY to 1, "2026-06-15T09:00"),
            Triple("2026-12-31T09:00", Freq.MONTHLY to 1, "2027-01-31T09:00"),
            Triple("2026-11-30T09:00", Freq.MONTHLY to 2, "2027-01-30T09:00")
        )
        for ((input, rule, want) in cases) {
            val got = Rrule.advance(LocalDateTime.parse(input), rule.first, rule.second)
            assertEquals(input, LocalDateTime.parse(want), got)
        }
    }

    @Test
    fun advanceClampsYearlyLeapDay() {
        assertEquals(
            LocalDateTime.parse("2025-02-28T09:00"),
            Rrule.advance(LocalDateTime.parse("2024-02-29T09:00"), Freq.YEARLY, 1)
        )
        assertEquals(
            LocalDateTime.parse("2028-02-29T09:00"),
            Rrule.advance(LocalDateTime.parse("2024-02-29T09:00"), Freq.YEARLY, 4)
        )
    }

    @Test
    fun advanceClampDrifts() {
        // Accepted behaviour, locked so nobody "fixes" it by accident: the clamp
        // is sticky, so Jan 31 → Feb 28 → Mar 28, not back to Mar 31. True RFC
        // 5545 semantics would need an anchor day-of-month column. See TODO.
        val feb = Rrule.advance(LocalDateTime.parse("2026-01-31T09:00"), Freq.MONTHLY, 1)
        assertEquals(LocalDateTime.parse("2026-02-28T09:00"), feb)
        assertEquals(LocalDateTime.parse("2026-03-28T09:00"), Rrule.advance(feb, Freq.MONTHLY, 1))
    }

    @Test
    fun advanceDateHonoursInterval() {
        // Regression for the form preview, which used to advance by the UI key
        // and so ignored INTERVAL entirely.
        assertEquals("2026-01-11", Rrule.advanceDate("2026-01-01", "FREQ=DAILY;INTERVAL=10").first)
        assertEquals("2026-02-05", Rrule.advanceDate("2026-01-01", "FREQ=WEEKLY;INTERVAL=5").first)
        assertEquals("2026-08-01", Rrule.advanceDate("2026-01-01", "FREQ=MONTHLY;INTERVAL=7").first)
        assertEquals("2028-01-01", Rrule.advanceDate("2026-01-01", "FREQ=YEARLY;INTERVAL=2").first)
    }

    @Test
    fun advanceDatePreservesDateOnlyShape() {
        val (next, _) = Rrule.advanceDate("2026-01-31", "FREQ=MONTHLY")
        assertEquals("2026-02-28", next)
    }

    @Test
    fun advanceDateEmitsOffsetForDatetimeInput() {
        val (next, _) = Rrule.advanceDate("2026-01-31T09:00", "FREQ=MONTHLY")
        // RFC3339 with the system zone's offset; assert the local part and that
        // an offset (or Z) is present, so the test is TZ-independent.
        assertTrue(next, next.startsWith("2026-02-28T09:00"))
        assertTrue(next, next.indexOf('+', 11) >= 0 || next.indexOf('-', 11) >= 0 || next.endsWith("Z"))
    }

    @Test
    fun advanceDateUntilBoundary() {
        // Landing exactly on UNTIL keeps the schedule alive; passing it ends it.
        val (onUntil, past1) = Rrule.advanceDate("2026-11-30", "FREQ=MONTHLY;UNTIL=20261231T235959Z")
        assertEquals("2026-12-30", onUntil)
        assertFalse(past1)

        val (_, past2) = Rrule.advanceDate("2026-12-30", "FREQ=MONTHLY;UNTIL=20261231T235959Z")
        assertTrue(past2)
    }

    @Test
    fun advanceDateWithoutUntilNeverExpires() {
        val (_, pastUntil) = Rrule.advanceDate("2026-01-01", "FREQ=DAILY")
        assertFalse(pastUntil)
    }

    @Test
    fun parseUntilRejectsGarbage() {
        assertNull(Rrule.parseUntil("nonsense"))
        assertEquals(LocalDateTime.parse("2026-12-31T23:59:59"), Rrule.parseUntil("20261231"))
    }
}
