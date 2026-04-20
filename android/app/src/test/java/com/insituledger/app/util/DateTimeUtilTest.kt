package com.insituledger.app.util

import org.junit.Assert.assertEquals
import org.junit.Test
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
}
