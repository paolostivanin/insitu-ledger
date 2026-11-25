package com.insituledger.app.data.local.db

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalIdsTest {
    @Test fun belowTheTableThePendingQueueAndZero() {
        assertEquals(-1L, nextLocalId(null, null))
        assertEquals(-1L, nextLocalId(42L, null)) // only synced rows
        assertEquals(-1L, nextLocalId(42L, 7L))
        assertEquals(-4L, nextLocalId(-3L, null))
        assertEquals(-4L, nextLocalId(-3L, 10L))
        // A purged row's leftover op names -9: skip past it.
        assertEquals(-10L, nextLocalId(-3L, -9L))
        assertEquals(-10L, nextLocalId(null, -9L))
        assertEquals(-10L, nextLocalId(-9L, -2L))
    }
}
