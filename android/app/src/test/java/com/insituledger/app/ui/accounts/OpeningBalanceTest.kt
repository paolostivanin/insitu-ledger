package com.insituledger.app.ui.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpeningBalanceTest {

    @Test
    fun `blank opening balance saves as zero`() {
        assertEquals(0.0, parseOpeningBalance("")!!, 0.0)
        assertEquals(0.0, parseOpeningBalance("   ")!!, 0.0)
    }

    @Test
    fun `dot and comma decimals parse`() {
        assertEquals(120.5, parseOpeningBalance("120.50")!!, 0.0001)
        assertEquals(120.5, parseOpeningBalance("120,50")!!, 0.0001)
        assertEquals(-40.0, parseOpeningBalance("-40")!!, 0.0001)
    }

    @Test
    fun `garbage is rejected instead of silently becoming zero`() {
        assertNull(parseOpeningBalance("abc"))
        assertNull(parseOpeningBalance("1.2.3"))
    }
}
