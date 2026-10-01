package com.insituledger.app.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.AppDatabase
import com.insituledger.app.data.local.db.dao.*
import com.insituledger.app.data.local.db.entity.ScheduledTransactionEntity
import com.insituledger.app.data.local.db.entity.TransactionEntity
import com.insituledger.app.data.sync.SyncManager
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DeletionRepositoryTest {
    private val db: AppDatabase = mockk()
    private val transactions: TransactionDao = mockk(relaxed = true)
    private val schedules: ScheduledTransactionDao = mockk(relaxed = true)
    private val accounts: AccountDao = mockk(relaxed = true)
    private val pending: PendingOperationDao = mockk(relaxed = true)
    private val prefs: UserPreferences = mockk()
    private val sync: SyncManager = mockk(relaxed = true)
    private var inTransaction = false

    @Before fun setup() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        val block = slot<suspend () -> Any?>()
        coEvery { db.withTransaction(capture(block)) } coAnswers {
            inTransaction = true
            try { block.captured.invoke() } finally { inTransaction = false }
        }
        every { prefs.getSyncModeImmediate() } returns "webapp"
        coEvery { transactions.getById(1) } returns TransactionEntity(1, 2, 3, 4, "expense", 5.0, date = "2026-01-01")
        coEvery { schedules.getById(1) } returns ScheduledTransactionEntity(1, 2, 3, 4, "expense", 5.0, rrule = "FREQ=MONTHLY", nextOccurrence = "2026-01-01")
        coEvery { transactions.upsert(any()) } coAnswers { assertTrue(inTransaction) }
        coEvery { schedules.upsert(any()) } coAnswers { assertTrue(inTransaction) }
        coEvery { accounts.adjustBalance(any(), any()) } coAnswers { assertTrue(inTransaction) }
        coEvery { pending.insert(any()) } coAnswers { assertTrue(inTransaction); 1L }
        every { sync.triggerImmediateSync() } answers { assertFalse(inTransaction) }
    }

    @After fun teardown() { unmockkStatic("androidx.room.RoomDatabaseKt") }
    private fun transactions() = TransactionRepository(db, transactions, accounts, pending, mockk(), Gson(), sync, prefs)
    private fun schedules() = ScheduledRepository(schedules, pending, mockk(), Gson(), sync, prefs, db)

    @Test fun transactionDeletionCommitsBalanceTombstoneAndPendingOperationTogether() = runTest {
        transactions().delete(1)
        coVerify { accounts.adjustBalance(2, 5.0) }
        coVerify { transactions.upsert(match { it.id == 1L && it.deletedAt != null }) }
        coVerify { pending.insert(match { it.entityType == "transaction" && it.operation == "DELETE" && it.serverId == 1L }) }
        verify(exactly = 1) { sync.triggerImmediateSync() }
    }

    @Test fun scheduledDeletionCommitsTombstoneAndPendingOperationTogether() = runTest {
        schedules().delete(1)
        coVerify { schedules.upsert(match { it.id == 1L && it.deletedAt != null }) }
        coVerify { pending.insert(match { it.entityType == "scheduled" && it.operation == "DELETE" && it.serverId == 1L }) }
        verify(exactly = 1) { sync.triggerImmediateSync() }
    }

    @Test fun cancellationWhileQueuingAbortsTheTransactionAndDoesNotStartSync() = runTest {
        coEvery { pending.insert(any()) } coAnswers { assertTrue(inTransaction); throw CancellationException("navigation cancelled") }
        for (delete in listOf<suspend () -> Unit>({ transactions().delete(1) }, { schedules().delete(1) })) {
            assertTrue(runCatching { delete() }.exceptionOrNull() is CancellationException)
        }
        verify(exactly = 0) { sync.triggerImmediateSync() }
        assertFalse(inTransaction)
    }

    @Test fun repeatedTransactionDeleteDoesNotReverseTheBalanceAgain() = runTest {
        val row = transactions.getById(1)!!
        coEvery { transactions.getById(1) } returns row.copy(deletedAt = "deleted")
        transactions().delete(1)
        coVerify(exactly = 0) { accounts.adjustBalance(any(), any()) }
        coVerify(exactly = 0) { pending.insert(any()) }
        verify(exactly = 0) { sync.triggerImmediateSync() }
    }
}
