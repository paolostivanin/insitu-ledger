package com.insituledger.app.data.repository

import com.google.gson.Gson
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.AppDatabase
import com.insituledger.app.data.local.db.dao.AccountDao
import com.insituledger.app.data.local.db.dao.PendingOperationDao
import com.insituledger.app.data.local.db.dao.TransactionDao
import com.insituledger.app.data.remote.api.TransactionApi
import com.insituledger.app.data.sync.SyncManager
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * searchSummary is what the Reports screen totals a trip with. The repository's
 * only job is to escape the term and pass the date bounds through untouched —
 * the Reports date chips are useless if either half goes missing.
 */
class TransactionRepositorySearchSummaryTest {
    private val database: AppDatabase = mockk(relaxed = true)
    private val transactionDao: TransactionDao = mockk(relaxed = true)
    private val accountDao: AccountDao = mockk(relaxed = true)
    private val pendingOpDao: PendingOperationDao = mockk(relaxed = true)
    private val transactionApi: TransactionApi = mockk(relaxed = true)
    private val syncManager: SyncManager = mockk(relaxed = true)
    private val prefs: UserPreferences = mockk(relaxed = true)

    private fun repository() = TransactionRepository(
        database, transactionDao, accountDao, pendingOpDao,
        transactionApi, Gson(), syncManager, prefs
    )

    private suspend fun capture(
        query: String,
        from: String? = null,
        to: String? = null
    ): Triple<String, String?, String?> {
        val q = slot<String>()
        val f = slot<String?>()
        val t = slot<String?>()
        coEvery {
            transactionDao.searchSummary(capture(q), captureNullable(f), captureNullable(t))
        } returns emptyList()
        repository().searchSummary(query, from, to)
        return Triple(q.captured, f.captured, t.captured)
    }

    @Test
    fun `date bounds reach the query unchanged`() = runTest {
        val (q, from, to) = capture("valencia", "2026-01-01", "2026-09-10")

        assertEquals("valencia", q)
        assertEquals("2026-01-01", from)
        assertEquals("2026-09-10", to)
    }

    // All Time is the default and the escape hatch on the empty state; it has
    // to reach the DAO as two nulls so the predicates drop out entirely.
    @Test
    fun `an unbounded search passes nulls rather than empty strings`() = runTest {
        val (_, from, to) = capture("valencia")

        assertNull(from)
        assertNull(to)
    }

    @Test
    fun `the term is trimmed before matching`() = runTest {
        val (q, _, _) = capture("  valencia  ", "2026-01-01", "2026-09-10")

        assertEquals("valencia", q)
    }

    // % and _ are LIKE wildcards. A user typing "50%" means the character, not
    // "match anything" — without escaping the search silently totals the lot.
    @Test
    fun `wildcards in the term are escaped, not honoured`() = runTest {
        val (q, _, _) = capture(""" 50% _ a\b """)

        assertEquals("""50\% \_ a\\b""", q)
    }
}
