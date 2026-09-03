package com.insituledger.app.data.repository

import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import com.google.gson.Gson
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.AppDatabase
import com.insituledger.app.data.local.db.dao.AccountDao
import com.insituledger.app.data.local.db.dao.PendingOperationDao
import com.insituledger.app.data.local.db.dao.TransactionDao
import com.insituledger.app.data.remote.api.TransactionApi
import com.insituledger.app.data.sync.SyncManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * getSorted builds its SQL by hand, so these tests read the query it actually
 * hands to Room rather than trusting the shape of the builder.
 */
class TransactionRepositoryGetSortedTest {
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

    // SimpleSQLiteQuery keeps its bind args private; the only way out is to let
    // it bind them to a program.
    private class RecordingProgram : SupportSQLiteProgram {
        val bound = mutableListOf<Any?>()
        private fun set(index: Int, value: Any?) {
            while (bound.size < index) bound.add(null)
            bound[index - 1] = value
        }
        override fun bindNull(index: Int) = set(index, null)
        override fun bindLong(index: Int, value: Long) = set(index, value)
        override fun bindDouble(index: Int, value: Double) = set(index, value)
        override fun bindString(index: Int, value: String) = set(index, value)
        override fun bindBlob(index: Int, value: ByteArray) = set(index, value)
        override fun clearBindings() = bound.clear()
        override fun close() {}
    }

    private suspend fun capture(build: suspend TransactionRepository.() -> Unit): Pair<String, List<Any?>> {
        val slot = slot<SupportSQLiteQuery>()
        every { transactionDao.getSorted(capture(slot)) } returns flowOf(emptyList())
        repository().build()
        val program = RecordingProgram()
        slot.captured.bindTo(program)
        return slot.captured.sql to program.bound
    }

    @Test
    fun `search stacks with the date and category filters instead of replacing them`() = runTest {
        val (sql, args) = capture {
            getSorted(
                from = "2026-08-01", to = "2026-08-31", categoryId = 3L,
                search = "valencia", sortBy = "amount", sortDir = "asc",
                limit = 100, offset = 0
            ).first()
        }

        assertTrue("date filter kept: $sql", sql.contains("AND date >= ?"))
        assertTrue("to filter kept: $sql", sql.contains("AND SUBSTR(date, 1, 10) <= ?"))
        assertTrue(
            "category filter kept and still expands to children: $sql",
            sql.contains("AND category_id IN (SELECT id FROM categories WHERE id = ? OR parent_id = ?)")
        )
        assertTrue("search predicate added: $sql", sql.contains("""AND description LIKE ? ESCAPE '\'"""))
        assertTrue("chosen sort applied: $sql", sql.contains("ORDER BY amount ASC, id DESC"))
        assertTrue("still paginated: $sql", sql.contains("LIMIT ? OFFSET ?"))

        assertEquals(
            listOf<Any?>("2026-08-01", "2026-08-31", 3L, 3L, "%valencia%", 100L, 0L),
            args
        )
    }

    @Test
    fun `no search term adds no predicate`() = runTest {
        val (sql, args) = capture { getSorted(limit = 100, offset = 0).first() }

        assertFalse("no LIKE when unsearched: $sql", sql.contains("LIKE"))
        assertEquals(listOf<Any?>(100L, 0L), args)
    }

    @Test
    fun `a blank search term is treated as no search`() = runTest {
        val (sql, _) = capture { getSorted(search = "   ", limit = 100, offset = 0).first() }

        assertFalse("whitespace is not a search: $sql", sql.contains("LIKE"))
    }

    // % and _ are LIKE wildcards. A user typing "50%" means the character, not
    // "match anything" — without escaping the query silently returns everything.
    @Test
    fun `wildcards in the search term are escaped, not honoured`() = runTest {
        val (_, args) = capture { getSorted(search = " 50% _ a\\b ", limit = 100, offset = 0).first() }

        assertEquals("""%50\% \_ a\\b%""", args[0])
    }
}
