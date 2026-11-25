package com.insituledger.app.data.repository

import androidx.room.withTransaction
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

/** TransactionRepository.update: guard, balance, row and pending UPDATE are one commit. */
class TransactionRepositoryUpdateTest {
    private val db: AppDatabase = mockk()
    private val transactionDao: TransactionDao = mockk(relaxed = true)
    private val accountDao: AccountDao = mockk(relaxed = true)
    private val pendingOpDao: PendingOperationDao = mockk(relaxed = true)
    private val prefs: UserPreferences = mockk(relaxed = true)
    private val syncManager: SyncManager = mockk(relaxed = true)
    private var inTransaction = false

    private val existing = TransactionEntity(
        id = 42, accountId = 1, categoryId = 2, userId = 1, type = "expense", amount = 10.0,
        description = "Coffee", date = "2026-10-01T08:00:00+02:00"
    )

    @Before fun setUp() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        val block = slot<suspend () -> Any?>()
        coEvery { db.withTransaction(capture(block)) } coAnswers {
            inTransaction = true
            try { block.captured.invoke() } finally { inTransaction = false }
        }
        every { prefs.getSyncModeImmediate() } returns "webapp"
        coEvery { transactionDao.getById(42) } returns existing
        coEvery { transactionDao.upsert(any()) } coAnswers { assertTrue(inTransaction) }
        coEvery { accountDao.adjustBalance(any(), any()) } coAnswers { assertTrue(inTransaction) }
        coEvery { pendingOpDao.insert(any()) } coAnswers { assertTrue("op queued inside the transaction", inTransaction); 1L }
        every { syncManager.triggerImmediateSync() } answers { assertFalse("sync after commit", inTransaction) }
    }

    @After fun tearDown() { unmockkStatic("androidx.room.RoomDatabaseKt") }

    private fun repository() = TransactionRepository(
        db, transactionDao, accountDao, pendingOpDao, mockk(relaxed = true), Gson(), syncManager, prefs
    )

    private suspend fun update(id: Long = 42) = repository().update(
        id, 3, 2, "expense", 12.5, "EUR", "Coffee beans", null, "2026-10-01T08:00:00+02:00"
    )

    @Test fun `the update op is queued inside the transaction and sync runs after`() = runTest {
        val op = slot<PendingOperationEntity>()
        coEvery { pendingOpDao.insert(capture(op)) } coAnswers { assertTrue(inTransaction); 1L }

        update()

        coVerify { accountDao.adjustBalance(1, 10.0) }   // old expense reversed on the old account
        coVerify { accountDao.adjustBalance(3, -12.5) }  // new expense applied on the new account
        coVerify { transactionDao.upsert(match { it.id == 42L && it.accountId == 3L && it.amount == 12.5 }) }
        assertEquals("UPDATE", op.captured.operation)
        assertEquals(42L, op.captured.serverId)
        verify(exactly = 1) { syncManager.triggerImmediateSync() }
    }

    @Test fun `a missing row changes no balance and queues nothing`() = runTest {
        coEvery { transactionDao.getById(7) } returns null

        update(id = 7)

        coVerify(exactly = 0) { accountDao.adjustBalance(any(), any()) }
        coVerify(exactly = 0) { transactionDao.upsert(any()) }
        coVerify(exactly = 0) { pendingOpDao.insert(any()) }
        verify(exactly = 0) { syncManager.triggerImmediateSync() }
    }

    @Test fun `a soft-deleted row changes no balance and queues nothing`() = runTest {
        // Its balance effect was reversed when it was deleted; reversing again
        // would double-count.
        coEvery { transactionDao.getById(42) } returns existing.copy(deletedAt = "deleted")

        update()

        coVerify(exactly = 0) { accountDao.adjustBalance(any(), any()) }
        coVerify(exactly = 0) { transactionDao.upsert(any()) }
        coVerify(exactly = 0) { pendingOpDao.insert(any()) }
        verify(exactly = 0) { syncManager.triggerImmediateSync() }
    }

    @Test fun `a failure while queuing aborts before sync`() = runTest {
        coEvery { pendingOpDao.insert(any()) } coAnswers { assertTrue(inTransaction); throw IllegalStateException("disk full") }

        assertTrue(runCatching { update() }.exceptionOrNull() is IllegalStateException)

        verify(exactly = 0) { syncManager.triggerImmediateSync() }
        assertFalse(inTransaction)
    }

    @Test fun `local mode updates the row without an op or sync`() = runTest {
        every { prefs.getSyncModeImmediate() } returns "none"

        update()

        coVerify { transactionDao.upsert(any()) }
        coVerify(exactly = 0) { pendingOpDao.insert(any()) }
        verify(exactly = 0) { syncManager.triggerImmediateSync() }
    }

    // A purged local row's failed CREATE still names -9: a new row must not
    // reuse it, or a later Retry's remap would rename the new row.
    @Test fun newLocalIdSkipsIdsReferencedByPendingOps() = runTest {
        coEvery { transactionDao.getMinId() } returns -2L
        coEvery { pendingOpDao.getMinEntityId("transaction") } returns -9L
        every { prefs.userIdFlow } returns kotlinx.coroutines.flow.flowOf(1L)
        every { prefs.userNameFlow } returns kotlinx.coroutines.flow.flowOf("Me")

        val id = repository().create(1, 2, "expense", 3.0, "EUR", "Coffee", null, "2026-10-01T08:00:00+02:00")

        assertEquals(-10L, id)
        coVerify { transactionDao.upsert(match { it.id == -10L }) }
        coVerify { pendingOpDao.insert(match { it.operation == "CREATE" && it.entityId == -10L }) }
    }

    @Test fun newLocalIdStaysBelowTheTableWhenThatIsLower() = runTest {
        coEvery { transactionDao.getMinId() } returns -20L
        coEvery { pendingOpDao.getMinEntityId("transaction") } returns -9L
        every { prefs.userIdFlow } returns kotlinx.coroutines.flow.flowOf(1L)
        every { prefs.userNameFlow } returns kotlinx.coroutines.flow.flowOf("Me")

        assertEquals(-21L, repository().create(1, 2, "expense", 3.0, "EUR", null, null, "2026-10-01"))
    }
}
