package com.insituledger.app.ui.common

import com.insituledger.app.domain.model.Account
import com.insituledger.app.domain.model.isOwnedBy
import org.junit.Assert.*
import org.junit.Test

class AttributionTest {
    @Test fun sharedEntriesIncludeSelfOtherAndUnknownCreators() {
        assertEquals("Added by Alice", entryAttribution(true, 1, "Alice", 1))
        assertEquals("Added by Bob", entryAttribution(true, 2, "Bob", 1))
        assertEquals("Added by you", entryAttribution(true, 1, " ", 1))
        assertEquals("Added by unknown", entryAttribution(true, null, null, null))
        assertNull(entryAttribution(false, 1, "Alice", 1))
    }
    @Test fun localAccountsAreOwnedBeforeLoginButSharedAccountsAreNot() {
        val local = Account(-1, 0, "Local", "EUR", 0.0, isLocalOnly = true)
        assertTrue(local.isOwnedBy(null))
        assertTrue(local.isOwnedBy(1))
        assertFalse(local.copy(id = 1, userId = 2, isLocalOnly = false, isShared = true).isOwnedBy(1))
        assertFalse(local.copy(id = 1, userId = 1, isLocalOnly = false).isOwnedBy(null))
    }
}
