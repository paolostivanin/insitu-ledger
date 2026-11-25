package com.insituledger.app.data.repository

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import com.google.gson.Gson
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.AppDatabase
import com.insituledger.app.data.local.db.dao.AccountDao
import com.insituledger.app.data.local.db.dao.PendingOperationDao
import com.insituledger.app.data.local.db.dao.TransactionDao
import com.insituledger.app.data.local.db.entity.PendingOperationEntity
import com.insituledger.app.data.local.db.entity.TransactionEntity
import com.insituledger.app.data.sync.SyncManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** TransactionRepository.deleteMany (bulk delete) and filterIds (selection re-check). */
class TransactionRepositoryDeleteManyTest {
    private val db: AppDatabase = mockk()
    private val transactionDao: TransactionDao = mockk(relaxed = true)
    private val accountDao: AccountDao = mockk(relaxed = true)
    private val pendingOpDao: PendingOperationDao = mockk(relaxed = true)
    private val prefs: UserPreferences = mockk(relaxed = true)
    private val syncManager: SyncManager = mockk(relaxed = true)

    private var inTransaction = false
    private var transactionsRun = 0
    private val rows = linkedMapOf<Long, TransactionEntity>()
    private val ops = mutableListOf<PendingOperationEntity>()
    private val balance = linkedMapOf<Long, Double>()
    private var syncs = 0

    @Before fun setUp() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        val block = slot<suspend () -> Any?>()
        coEvery { db.withTransaction(capture(block)) } coAnswers {
            inTransaction = true
            transactionsRun++
            try { block.captured.invoke() } finally { inTransaction = false }
        }
        every { prefs.getSyncModeImmediate() } returns "webapp"
        coEvery { transactionDao.getByIds(any()) } answers {
            assertTrue("read inside the transaction", inTransaction)
            firstArg<List<Long>>().mapNotNull { rows[it] }
        }
        coEvery { transactionDao.upsert(any()) } answers {
            assertTrue("row written inside the transaction", inTransaction)
            val row = firstArg<TransactionEntity>()
            rows[row.id] = row
        }
        coEvery { accountDao.adjustBalance(any(), any()) } answers {
            assertTrue("balance moved inside the transaction", inTransaction)
            balance[firstArg()] = (balance[firstArg()] ?: 0.0) + secondArg<Double>()
        }
        coEvery { pendingOpDao.insert(any()) } answers {
            assertTrue("op queued inside the transaction", inTransaction)
            ops += firstArg<PendingOperationEntity>()
            ops.size.toLong()
        }
        every { syncManager.triggerImmediateSync() } answers {
            assertFalse("sync after the commit", inTransaction)
            syncs++
        }
    }

    @After fun tearDown() { unmockkStatic("androidx.room.RoomDatabaseKt") }

    private fun repository() = TransactionRepository(
        db, transactionDao, accountDao, pendingOpDao, mockk(relaxed = true), Gson(), syncManager, prefs
    )

    private fun row(id: Long, type: String = "expense", amount: Double = 10.0, accountId: Long = 1, deletedAt: String? = null) =
        TransactionEntity(
            id = id, accountId = accountId, categoryId = 2, userId = 1, type = type, amount = amount,
            description = "Row $id", date = "2026-10-01T08:00:00+02:00", deletedAt = deletedAt
        ).also { rows[id] = it }

    @Test fun deleteManyIsOneTransactionOneSync() = runTest {
        row(1, type = "expense", amount = 10.0, accountId = 1)
        row(2, type = "income", amount = 100.0, accountId = 1)
        row(-3, type = "expense", amount = 5.0, accountId = 2)

        val deleted = repository().deleteMany(listOf(1L, 2L, -3L))

        assertEquals(3, deleted)
        assertEquals("one transaction for every row", 1, transactionsRun)
        assertTrue(rows.values.all { it.deletedAt != null })
        // Each row's effect reversed: account 1 = +10 (expense) - 100 (income).
        assertEquals(-90.0, balance[1L]!!, 1e-9)
        assertEquals(5.0, balance[2L]!!, 1e-9)
        assertEquals(listOf(1L, 2L, -3L), ops.map { it.entityId })
        assertTrue(ops.all { it.entityType == "transaction" && it.operation == "DELETE" })
        assertEquals("a local row's DELETE waits for its CREATE", listOf(1L, 2L, null), ops.map { it.serverId })
        assertEquals("sync once, after the commit", 1, syncs)
    }

    @Test fun deleteManySkipsMissingAndDeleted() = runTest {
        row(1)
        row(2, deletedAt = "deleted") // its balance effect is already reversed
        // 3 doesn't exist

        val deleted = repository().deleteMany(listOf(1L, 2L, 3L, 1L))

        assertEquals(1, deleted)
        assertEquals(listOf(1L), ops.map { it.entityId })
        assertEquals(mapOf(1L to 10.0), balance)
        assertEquals(1, syncs)
    }

    @Test fun deleteManyWithNothingLiveDoesNotSync() = runTest {
        row(2, deletedAt = "deleted")

        assertEquals(0, repository().deleteMany(listOf(2L, 3L)))

        assertTrue(ops.isEmpty())
        assertTrue(balance.isEmpty())
        assertEquals(0, syncs)
    }

    @Test fun deleteManyInLocalModeQueuesNothing() = runTest {
        every { prefs.getSyncModeImmediate() } returns "none"
        row(1)

        assertEquals(1, repository().deleteMany(listOf(1L)))

        assertTrue(ops.isEmpty())
        assertEquals(0, syncs)
        verify(exactly = 0) { syncManager.triggerImmediateSync() }
        assertEquals("deleted", rows[1L]!!.deletedAt)
    }

    @Test fun deleteManyFailureRollsBackAndDoesNotSync() = runTest {
        row(1)
        row(2)
        coEvery { pendingOpDao.insert(any()) } answers {
            assertTrue(inTransaction)
            if (ops.size == 1) throw IllegalStateException("disk full")
            ops += firstArg<PendingOperationEntity>()
            1L
        }

        assertTrue(runCatching { repository().deleteMany(listOf(1L, 2L)) }.exceptionOrNull() is IllegalStateException)

        assertEquals(0, syncs)
        assertFalse(inTransaction)
    }

    @Test fun deleteManyReadsInChunksUnderTheBindLimit() = runTest {
        for (id in 1L..1200L) row(id)

        assertEquals(1200, repository().deleteMany((1L..1200L).toList()))

        coVerify(exactly = 2) { transactionDao.getByIds(match { it.size == 500 }) }
        coVerify(exactly = 1) { transactionDao.getByIds(match { it.size == 200 }) }
        assertEquals("one balance write per account", 1, balance.size)
        assertEquals(12_000.0, balance[1L]!!, 1e-6)
    }

    // ---- filterIds ----

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

    @Test fun filterIdsUsesTheListFilterAndKeepsOnlyMatches() = runTest {
        val queries = mutableListOf<SupportSQLiteQuery>()
        // The database still matches 1 and 3 (2 was renamed by a sync).
        coEvery { transactionDao.getIdsRaw(capture(queries)) } returns listOf(3L, 1L)
        val filter = TransactionFilter(search = "Paga", exactMatch = true, from = "2026-01-01")

        val kept = repository().filterIds(filter, listOf(1L, 2L, 3L))

        assertEquals("input order kept", listOf(1L, 3L), kept)
        val query = queries.single()
        val where = buildTransactionWhere(filter)
        assertEquals("SELECT id FROM transactions ${where.sql} AND id IN (?, ?, ?)", query.sql)
        val program = RecordingProgram()
        query.bindTo(program)
        assertEquals(where.args + listOf(1L, 2L, 3L), program.bound)
    }

    @Test fun filterIdsChunksTo500Ids() = runTest {
        val sizes = mutableListOf<Int>()
        coEvery { transactionDao.getIdsRaw(any()) } answers {
            val program = RecordingProgram()
            firstArg<SupportSQLiteQuery>().bindTo(program)
            sizes += program.bound.size
            program.bound.filterIsInstance<Long>()
        }

        val kept = repository().filterIds(TransactionFilter(), (1L..1001L).toList())

        assertEquals((1L..1001L).toList(), kept)
        assertEquals(listOf(500, 500, 1), sizes)
    }

    @Test fun filterIdsOfNothingRunsNoQuery() = runTest {
        assertTrue(repository().filterIds(TransactionFilter(), emptyList()).isEmpty())
        coVerify(exactly = 0) { transactionDao.getIdsRaw(any()) }
    }
}
