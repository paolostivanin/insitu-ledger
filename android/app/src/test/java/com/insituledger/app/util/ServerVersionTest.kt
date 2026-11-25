package com.insituledger.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerVersionTest {
    @Test fun parsesNumericParts() {
        assertEquals(listOf(1, 27, 0), ServerVersion.parse("1.27.0"))
        assertEquals(listOf(1, 27, 0), ServerVersion.parse(" v1.27.0 "))
        assertEquals(listOf(1, 27, 0), ServerVersion.parse("1.27.0-rc1"))
        assertEquals(listOf(1, 27, 0), ServerVersion.parse("1.27.0+build.5"))
        assertEquals(listOf(0), ServerVersion.parse(ServerVersion.OLD))
        assertNull(ServerVersion.parse(""))
        assertNull(ServerVersion.parse("dev"))
        assertNull(ServerVersion.parse("1..2"))
    }

    @Test fun comparesNumericallyNotAsText() {
        assertTrue(ServerVersion.atLeast("1.27.0", "1.27.0"))
        assertTrue(ServerVersion.atLeast("1.27", "1.27.0"))
        assertTrue(ServerVersion.atLeast("1.100.0", "1.27.0"))
        assertTrue(ServerVersion.atLeast("2.0.0", "1.27.0"))
        assertFalse("text order would say yes", ServerVersion.atLeast("1.3.0", "1.27.0"))
        assertFalse(ServerVersion.atLeast("1.26.9", "1.27.0"))
        assertFalse(ServerVersion.atLeast("0.99", "1.27.0"))
        assertFalse(ServerVersion.atLeast(ServerVersion.OLD, "1.27.0"))
        assertFalse("unparsable never qualifies", ServerVersion.atLeast("dev", "1.27.0"))
    }
}
