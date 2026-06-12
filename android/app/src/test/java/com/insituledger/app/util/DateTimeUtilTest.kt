package com.insituledger.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

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
}
