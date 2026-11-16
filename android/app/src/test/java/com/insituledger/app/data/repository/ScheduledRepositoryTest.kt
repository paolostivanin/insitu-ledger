package com.insituledger.app.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.AppDatabase
import com.insituledger.app.data.local.db.dao.AccountDao
import com.insituledger.app.data.local.db.dao.PendingOperationDao
import com.insituledger.app.data.local.db.dao.ScheduledTransactionDao
import com.insituledger.app.data.local.db.dao.TransactionDao
import com.insituledger.app.data.local.db.entity.PendingOperationEntity
import com.insituledger.app.data.local.db.entity.ScheduledTransactionEntity
import com.insituledger.app.data.sync.SyncManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

// Writing a schedule (or a transaction) and queueing its sync operation must
// commit together, with the sync triggered only after the commit.
class ScheduledRepositoryTest {
    private val db: AppDatabase = mockk()
    private val scheduled: ScheduledTransactionDao = mockk(relaxed = true)
    private val transactions: TransactionDao = mockk(relaxed = true)
    private val accounts: AccountDao = mockk(relaxed = true)
    private val pending: PendingOperationDao = mockk(relaxed = true)
    private val prefs: UserPreferences = mockk(relaxed = true)
    private val sync: SyncManager = mockk(relaxed = true)
    private var inTransaction = false
    private val inserted = mutableListOf<PendingOperationEntity>()

    @Before fun setup() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        val block = slot<suspend () -> Any?>()
        coEvery { db.withTransaction(capture(block)) } coAnswers {
            inTransaction = true
            try { block.captured.invoke() } finally { inTransaction = false }
        }
        every { prefs.userIdFlow } returns flowOf(4L)
        every { prefs.userNameFlow } returns flowOf("Anna")
        coEvery { scheduled.getMinId() } returns -3L
        coEvery { transactions.getMinId() } returns null
        coEvery { scheduled.upsert(any()) } coAnswers { assertTrue("upsert outside transaction", inTransaction) }
        coEvery { transactions.upsert(any()) } coAnswers { assertTrue("upsert outside transaction", inTransaction) }
        coEvery { accounts.adjustBalance(any(), any()) } coAnswers { assertTrue(inTransaction) }
        coEvery { pending.insert(any()) } coAnswers {
            assertTrue("pending op outside transaction", inTransaction)
            inserted += firstArg<PendingOperationEntity>()
            1L
        }
        every { sync.triggerImmediateSync() } answers { assertFalse("sync before commit", inTransaction) }
    }

    @After fun teardown() { unmockkStatic("androidx.room.RoomDatabaseKt") }

    private fun repository() = ScheduledRepository(scheduled, pending, mockk(), Gson(), sync, prefs, db)
    private fun transactionRepository() = TransactionRepository(db, transactions, accounts, pending, mockk(), Gson(), sync, prefs)

    private suspend fun create(repo: ScheduledRepository = repository()) = repo.create(
        accountId = 1, categoryId = 2, type = "expense", amount = 9.5, currency = "EUR",
        description = "Rent", note = null, rrule = "FREQ=MONTHLY",
        nextOccurrence = "2026-11-20T09:00+01:00", maxOccurrences = 12
    )

    @Test fun localModeUpsertsWithoutPendingOperationOrSync() = runTest {
        every { prefs.getSyncModeImmediate() } returns "none"
        val id = create()

        assertEquals(-4L, id)
        coVerify(exactly = 1) {
            scheduled.upsert(match {
                it.id == -4L && it.rrule == "FREQ=MONTHLY" && it.maxOccurrences == 12 &&
                    it.nextOccurrence == "2026-11-20T09:00+01:00" && it.isLocalOnly &&
                    it.createdByUserId == 4L && it.createdByName == "Anna"
            })
        }
        coVerify(exactly = 0) { pending.insert(any()) }
        verify(exactly = 0) { sync.triggerImmediateSync() }
    }

    @Test fun webappModeQueuesTheCreateInsideTheTransactionAndSyncsAfter() = runTest {
        every { prefs.getSyncModeImmediate() } returns "webapp"
        val id = create()

        assertEquals(1, inserted.size)
        val op = inserted.single()
        assertEquals("scheduled", op.entityType)
        assertEquals("CREATE", op.operation)
        assertEquals(id, op.entityId)
        assertTrue(op.clientId != null)
        val payload = JsonParser.parseString(op.payloadJson).asJsonObject
        assertEquals("FREQ=MONTHLY", payload["rrule"].asString)
        assertEquals("2026-11-20T09:00+01:00", payload["next_occurrence"].asString)
        assertEquals(12, payload["max_occurrences"].asInt)
        verify(exactly = 1) { sync.triggerImmediateSync() }
    }

    @Test fun failedPendingInsertPropagatesAndNeverSyncs() = runTest {
        every { prefs.getSyncModeImmediate() } returns "webapp"
        coEvery { pending.insert(any()) } throws IllegalStateException("disk full")

        try {
            create()
            fail("expected the failure to propagate (Room rolls the upsert back)")
        } catch (e: IllegalStateException) {
            assertEquals("disk full", e.message)
        }
        // The upsert ran inside the same transaction as the failed insert.
        coVerify(exactly = 1) { scheduled.upsert(any()) }
        verify(exactly = 0) { sync.triggerImmediateSync() }
    }

    @Test fun webappUpdateQueuesInsideTheTransactionAndSyncsAfter() = runTest {
        every { prefs.getSyncModeImmediate() } returns "webapp"
        coEvery { scheduled.getById(7) } returns ScheduledTransactionEntity(
            7, 1, 2, 4, "expense", 9.5, rrule = "FREQ=MONTHLY", nextOccurrence = "2026-11-20T09:00+01:00"
        )
        repository().update(
            7, 1, 2, "expense", 11.0, "EUR", null, null,
            "FREQ=WEEKLY", "2026-11-21T09:00+01:00", null, false
        )

        coVerify { scheduled.upsert(match { it.id == 7L && it.amount == 11.0 && it.rrule == "FREQ=WEEKLY" && !it.active }) }
        val op = inserted.single()
        assertEquals("UPDATE", op.operation)
        assertEquals(7L, op.serverId)
        val payload = JsonParser.parseString(op.payloadJson).asJsonObject
        assertEquals("FREQ=WEEKLY", payload["rrule"].asString)
        assertFalse(payload["active"].asBoolean)
        verify(exactly = 1) { sync.triggerImmediateSync() }
    }

    @Test fun updatingAMissingScheduleWritesNothing() = runTest {
        every { prefs.getSyncModeImmediate() } returns "webapp"
        coEvery { scheduled.getById(7) } returns null
        repository().update(7, 1, 2, "expense", 11.0, "EUR", null, null, "FREQ=WEEKLY", "2026-11-21T09:00+01:00")

        coVerify(exactly = 0) { scheduled.upsert(any()) }
        assertTrue(inserted.isEmpty())
        verify(exactly = 0) { sync.triggerImmediateSync() }
    }

    @Test fun transactionCreateQueuesInsideTheTransactionAndSyncsAfter() = runTest {
        every { prefs.getSyncModeImmediate() } returns "webapp"
        val id = transactionRepository().create(1, 2, "income", 3.0, "EUR", null, null, "2026-11-13T10:00+01:00")

        assertEquals(-1L, id)
        coVerify { accounts.adjustBalance(1, 3.0) }
        val op = inserted.single()
        assertEquals("transaction", op.entityType)
        assertEquals("CREATE", op.operation)
        assertEquals(-1L, op.entityId)
        assertEquals("2026-11-13T10:00+01:00", JsonParser.parseString(op.payloadJson).asJsonObject["date"].asString)
        verify(exactly = 1) { sync.triggerImmediateSync() }
    }

    @Test fun localTransactionCreateHasNoPendingOperation() = runTest {
        every { prefs.getSyncModeImmediate() } returns "none"
        transactionRepository().create(1, 2, "expense", 3.0, "EUR", null, null, "2026-11-13T10:00+01:00")

        coVerify(exactly = 1) { transactions.upsert(any()) }
        assertTrue(inserted.isEmpty())
        verify(exactly = 0) { sync.triggerImmediateSync() }
    }
}
