package com.insituledger.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

class DateTimeUtilTest {

    @Test
    fun parsesAppCanonicalFormat() {
        val parsed = DateTimeUtil.parseFlexibleLocalDateTime("2026-04-20T15:30")
        assertEquals(LocalDateTime.of(2026, 4, 20, 15, 30), parsed)
    }

    @Test
    fun parsesIsoLocalDateTimeWithSeconds() {
        val parsed = DateTimeUtil.parseFlexibleLocalDateTime("2026-04-20T15:30:45")
        assertEquals(LocalDateTime.of(2026, 4, 20, 15, 30, 45), parsed)
    }

    @Test
    fun parsesIsoLocalDateTimeWithMillis() {
        val parsed = DateTimeUtil.parseFlexibleLocalDateTime("2026-04-20T15:30:45.123")
        assertEquals(LocalDateTime.of(2026, 4, 20, 15, 30, 45, 123_000_000), parsed)
    }

    @Test
    fun parsesSpaceSeparatedFormat() {
        val parsed = DateTimeUtil.parseFlexibleLocalDateTime("2026-04-20 15:30:45")
        assertEquals(LocalDateTime.of(2026, 4, 20, 15, 30, 45), parsed)
    }

    @Test
    fun parsesDateOnlyAsStartOfDay() {
        val parsed = DateTimeUtil.parseFlexibleLocalDateTime("2026-04-20")
        assertEquals(LocalDateTime.of(2026, 4, 20, 0, 0), parsed)
    }

    @Test
    fun roundTripsCanonicalFormat() {
        val src = LocalDateTime.of(2026, 4, 20, 15, 30)
        val s = DateTimeUtil.formatLocalDateTime(src)
        assertEquals("2026-04-20T15:30", s)
        assertEquals(src, DateTimeUtil.parseFlexibleLocalDateTime(s))
    }

    @Test
    fun displayDateStripsOffsetWithSeconds() {
        assertEquals("2026-06-11 08:41", DateTimeUtil.displayDate("2026-06-11T08:41:00+02:00"))
    }

    @Test
    fun displayDateStripsNegativeOffset() {
        assertEquals("2026-06-11 08:41", DateTimeUtil.displayDate("2026-06-11T08:41:00-05:00"))
    }

    @Test
    fun displayDateStripsZ() {
        assertEquals("2026-06-11 08:41", DateTimeUtil.displayDate("2026-06-11T08:41:00Z"))
    }

    @Test
    fun displayDateHandlesNoSecondsOffset() {
        assertEquals("2026-06-11 08:41", DateTimeUtil.displayDate("2026-06-11T08:41+02:00"))
    }

    @Test
    fun displayDateHandlesNaive() {
        assertEquals("2026-06-11 08:41", DateTimeUtil.displayDate("2026-06-11T08:41"))
    }

    @Test
    fun displayDateHandlesNaiveWithSeconds() {
        assertEquals("2026-06-11 08:41", DateTimeUtil.displayDate("2026-06-11T08:41:30"))
    }

    @Test
    fun displayDateReturnsDateOnlyVerbatim() {
        assertEquals("2026-06-11", DateTimeUtil.displayDate("2026-06-11"))
    }

    @Test
    fun displayDateHandlesEmpty() {
        assertEquals("", DateTimeUtil.displayDate(""))
    }

    @Test
    fun extractTimeFromRFC3339() {
        assertEquals("08:41", DateTimeUtil.extractTime("2026-06-11T08:41:00+02:00"))
    }

    @Test
    fun extractTimeFromNaive() {
        assertEquals("08:41", DateTimeUtil.extractTime("2026-06-11T08:41"))
    }

    @Test
    fun extractTimeFromDateOnlyIsEmpty() {
        assertEquals("", DateTimeUtil.extractTime("2026-06-11"))
    }

    // Regression guard: legacy naive input must keep its own date, NOT silently
    // reset to "today" by falling back to OffsetDateTime.now(). A pre-1.28 row
    // edited after the upgrade would otherwise be rewritten with today's date.
    @Test
    fun parseFormDateKeepsLegacyNaiveDate() {
        val parsed = DateTimeUtil.parseFormDate("2024-03-15T10:30")
        assertEquals(LocalDate.of(2024, 3, 15), parsed.toLocalDate())
        assertEquals(10, parsed.hour)
        assertEquals(30, parsed.minute)
    }

    @Test
    fun parseFormDateRoundTripsRFC3339() {
        val parsed = DateTimeUtil.parseFormDate("2026-06-11T08:41:00+03:00")
        assertEquals(LocalDate.of(2026, 6, 11), parsed.toLocalDate())
        assertEquals(8, parsed.hour)
        assertEquals(41, parsed.minute)
        assertEquals(3 * 3600, parsed.offset.totalSeconds)
    }

    // parseFormDate is called from Compose picker callbacks — it must NEVER
    // throw. Truly unparseable garbage falls back to "now" (parseable legacy
    // shapes keep their own date, covered above).
    @Test
    fun parseFormDateGarbageFallsBackWithoutThrowing() {
        assertNotNull(DateTimeUtil.parseFormDate("garbage"))
        assertNotNull(DateTimeUtil.parseFormDate(""))
        assertNotNull(DateTimeUtil.parseFormDate("2026-13-99T99:99"))
    }

    // --- composeFormDate / DST ---

    private val rome = ZoneId.of("Europe/Rome")

    private fun compose(date: String, time: String, zone: ZoneId = rome) =
        DateTimeUtil.composeFormDate(LocalDate.parse(date), LocalTime.parse(time), zone)

    @Test
    fun composeUsesWinterAndSummerOffsets() {
        assertEquals("2026-11-20T09:00+01:00", compose("2026-11-20", "09:00"))
        assertEquals("2026-07-14T09:00+02:00", compose("2026-07-14", "09:00"))
    }

    @Test
    fun composeShiftsAGapTimeForward() {
        assertEquals("2026-03-29T03:30+02:00", compose("2026-03-29", "02:30"))
        assertTrue(DateTimeUtil.isInDstGap(LocalDate.parse("2026-03-29"), LocalTime.parse("02:30"), rome))
        assertFalse(DateTimeUtil.isInDstGap(LocalDate.parse("2026-03-29"), LocalTime.parse("03:30"), rome))
    }

    @Test
    fun composeTakesTheEarlierOffsetInAnOverlap() {
        assertEquals("2026-10-25T02:30+02:00", compose("2026-10-25", "02:30"))
        assertFalse(DateTimeUtil.isInDstGap(LocalDate.parse("2026-10-25"), LocalTime.parse("02:30"), rome))
    }

    @Test
    fun composeHandlesOtherZones() {
        assertEquals("2026-03-08T03:30-04:00", compose("2026-03-08", "02:30", ZoneId.of("America/New_York")))
        // Lord Howe springs forward by 30 minutes at 02:00 → 02:15 becomes 02:45.
        assertEquals("2026-10-04T02:45+11:00", compose("2026-10-04", "02:15", ZoneId.of("Australia/Lord_Howe")))
        assertEquals("2026-11-20T09:00Z", compose("2026-11-20", "09:00", ZoneId.of("UTC")))
    }

    @Test
    fun composeDropsSeconds() {
        assertEquals("2026-11-20T09:00+01:00", compose("2026-11-20", "09:00:59"))
    }

    @Test
    fun withDatePartRecomputesTheOffsetAcrossDst() {
        assertEquals(
            "2026-07-14T09:00+02:00",
            DateTimeUtil.withDatePart("2026-11-20T09:00+01:00", LocalDate.parse("2026-07-14"), rome)
        )
        assertEquals(
            "2026-07-14T09:00+02:00",
            DateTimeUtil.withDatePart("2026-11-20T09:00:00+01:00", LocalDate.parse("2026-07-14"), rome)
        )
    }

    @Test
    fun withDatePartKeepsAForeignOffset() {
        assertEquals(
            "2026-07-14T09:00+05:30",
            DateTimeUtil.withDatePart("2026-11-20T09:00+05:30", LocalDate.parse("2026-07-14"), rome)
        )
    }

    @Test
    fun withTimePartRecomputesAndShiftsGapTimes() {
        assertEquals(
            "2026-03-29T03:30+02:00",
            DateTimeUtil.withTimePart("2026-03-29T01:00+01:00", LocalTime.parse("02:30"), rome)
        )
        assertEquals(
            "2026-11-20T18:45+05:30",
            DateTimeUtil.withTimePart("2026-11-20T09:00+05:30", LocalTime.parse("18:45"), rome)
        )
    }

    @Test
    fun withDatePartReadsLegacyNaiveValuesInTheZone() {
        assertEquals(
            "2026-07-14T15:30+02:00",
            DateTimeUtil.withDatePart("2026-01-20T15:30", LocalDate.parse("2026-07-14"), rome)
        )
    }

    @Test
    fun parseFormDateOrNullIsStrict() {
        assertNull(DateTimeUtil.parseFormDateOrNull("garbage"))
        assertNull(DateTimeUtil.parseFormDateOrNull(""))
        assertNull(DateTimeUtil.parseFormDateOrNull("2026-13-99T99:99"))
        assertEquals(
            OffsetDateTime.parse("2026-11-20T09:00+01:00"),
            DateTimeUtil.parseFormDateOrNull("2026-11-20T09:00+01:00")
        )
        assertEquals(
            OffsetDateTime.parse("2026-07-14T15:30+02:00"),
            DateTimeUtil.parseFormDateOrNull("2026-07-14T15:30", rome)
        )
        assertEquals(
            OffsetDateTime.parse("2026-07-14T00:00+02:00"),
            DateTimeUtil.parseFormDateOrNull("2026-07-14", rome)
        )
    }
}
