package com.insituledger.app.data.sync

import androidx.room.withTransaction
import com.insituledger.app.data.local.db.AppDatabase
import com.insituledger.app.data.local.db.dao.AccountDao
import com.insituledger.app.data.local.db.dao.ScheduledTransactionDao
import com.insituledger.app.data.local.db.dao.TransactionDao
import com.insituledger.app.data.local.db.entity.ScheduledTransactionEntity
import com.insituledger.app.data.local.db.entity.TransactionEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

// Drives ScheduledMaterializer against an in-memory stand-in for the three
// DAOs. withTransaction runs the block and rolls the fake tables back if it
// throws, like Room.
class ScheduledMaterializerTest {
    private val db: AppDatabase = mockk()
    private val scheduledDao: ScheduledTransactionDao = mockk()
    private val transactionDao: TransactionDao = mockk()
    private val accountDao: AccountDao = mockk(relaxed = true)

    // 2026-11-13 10:00 in Rome.
    private val clock = Clock.fixed(Instant.parse("2026-11-13T09:00:00Z"), ZoneId.of("Europe/Rome"))

    private val rows = linkedMapOf<Long, ScheduledTransactionEntity>()
    private val created = mutableListOf<TransactionEntity>()
    private val errors = mutableListOf<Throwable>()

    @Before fun setup() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        val block = slot<suspend () -> Any?>()
        coEvery { db.withTransaction(capture(block)) } coAnswers {
            val rowsBefore = LinkedHashMap(rows)
            val createdBefore = created.toList()
            try {
                block.captured.invoke()
            } catch (e: Throwable) {
                rows.clear(); rows.putAll(rowsBefore)
                created.clear(); created.addAll(createdBefore)
                throw e
            }
        }
        // Mirrors the DAO: active, not deleted, SUBSTR(next_occurrence, 1, 16) <= now.
        coEvery { scheduledDao.getDue(any()) } answers {
            val now = firstArg<String>()
            rows.values.filter { it.active && it.deletedAt == null && it.nextOccurrence.take(16) <= now }
        }
        coEvery { scheduledDao.getById(any()) } answers { rows[firstArg()] }
        coEvery { scheduledDao.upsert(any()) } answers {
            val e = firstArg<ScheduledTransactionEntity>()
            rows[e.id] = e
        }
        coEvery { transactionDao.getMinId() } answers { created.minOfOrNull { it.id } }
        coEvery { transactionDao.upsert(any()) } answers { created += firstArg<TransactionEntity>() }
    }

    @After fun teardown() { unmockkStatic("androidx.room.RoomDatabaseKt") }

    private fun materializer() = ScheduledMaterializer(
        db, scheduledDao, transactionDao, accountDao, clock,
        log = { _, error -> if (error != null) errors += error }
    )

    private fun schedule(
        id: Long = -1,
        next: String = "2026-10-30T10:00+01:00",
        rrule: String = "FREQ=WEEKLY",
        amount: Double = 10.0,
        accountId: Long = 1,
        max: Int? = null,
        count: Int = 0
    ) = ScheduledTransactionEntity(
        id = id, accountId = accountId, categoryId = 2, userId = 0, type = "expense", amount = amount,
        rrule = rrule, nextOccurrence = next, maxOccurrences = max, occurrenceCount = count
    ).also { rows[id] = it }

    @Test fun nothingDueDoesNothing() = runTest {
        schedule(next = "2026-11-20T09:00+01:00")
        assertEquals(0, materializer().run())
        assertTrue(created.isEmpty())
    }

    @Test fun threeOverdueWeeksAreCaughtUpInOneRun() = runTest {
        schedule()
        assertEquals(3, materializer().run())

        assertEquals(
            listOf("2026-10-30T10:00+01:00", "2026-11-06T10:00:00+01:00", "2026-11-13T10:00:00+01:00"),
            created.map { it.date }
        )
        assertEquals(listOf(-1L, -2L, -3L), created.map { it.id })
        val row = rows.getValue(-1)
        assertEquals("2026-11-20T10:00:00+01:00", row.nextOccurrence)
        assertEquals(3, row.occurrenceCount)
        assertTrue(row.active)
        coVerify(exactly = 3) { accountDao.adjustBalance(1, -10.0) }
    }

    @Test fun countExhaustedMidCatchUpDeactivates() = runTest {
        schedule(max = 2)
        assertEquals(2, materializer().run())

        assertEquals(2, created.size)
        val row = rows.getValue(-1)
        assertFalse(row.active)
        assertNotNull(row.deletedAt)
        assertEquals(2, row.occurrenceCount)
    }

    @Test fun untilEndsMidCatchUp() = runTest {
        schedule(rrule = "FREQ=WEEKLY;UNTIL=20261108T235959Z")
        assertEquals(2, materializer().run())
        assertFalse(rows.getValue(-1).active)
    }

    @Test fun rowChangedSinceTheQueryIsSkipped() = runTest {
        val stale = schedule(next = "2026-11-13T09:00+01:00")
        // Another run (or an edit) already moved it on.
        rows[-1] = stale.copy(nextOccurrence = "2026-11-20T09:00+01:00", occurrenceCount = 1)
        coEvery { scheduledDao.getDue(any()) } returnsMany listOf(listOf(stale), emptyList())

        assertEquals(0, materializer().run())
        assertTrue(created.isEmpty())
        assertEquals("2026-11-20T09:00+01:00", rows.getValue(-1).nextOccurrence)
        assertEquals(1, rows.getValue(-1).occurrenceCount)
    }

    @Test fun deletedOrPausedRowIsSkipped() = runTest {
        val stale = schedule(next = "2026-11-13T09:00+01:00")
        coEvery { scheduledDao.getDue(any()) } returnsMany listOf(listOf(stale), emptyList())

        rows[-1] = stale.copy(deletedAt = "deleted")
        assertEquals(0, materializer().run())
        assertTrue(created.isEmpty())

        coEvery { scheduledDao.getDue(any()) } returnsMany listOf(listOf(stale), emptyList())
        rows[-1] = stale.copy(active = false)
        assertEquals(0, materializer().run())
        assertTrue(created.isEmpty())
    }

    @Test fun editedAmountIsNotReverted() = runTest {
        val stale = schedule(next = "2026-11-13T09:00+01:00", amount = 10.0)
        rows[-1] = stale.copy(amount = 20.0, description = "Edited")
        coEvery { scheduledDao.getDue(any()) } returnsMany listOf(listOf(stale), emptyList())

        assertEquals(1, materializer().run())
        assertEquals(20.0, created.single().amount, 0.0)
        assertEquals("Edited", created.single().description)
        val row = rows.getValue(-1)
        assertEquals(20.0, row.amount, 0.0)
        assertEquals("Edited", row.description)
        assertEquals("2026-11-20T09:00:00+01:00", row.nextOccurrence)
        coVerify(exactly = 1) { accountDao.adjustBalance(1, -20.0) }
    }

    @Test fun oneFailingRowDoesNotAbortTheOthers() = runTest {
        schedule(id = -1, accountId = 99, next = "2026-11-13T09:00+01:00")
        schedule(id = -2, accountId = 1, next = "2026-11-06T09:00+01:00")
        coEvery { accountDao.adjustBalance(99, any()) } throws IllegalStateException("boom")

        assertEquals(2, materializer().run())

        // The failing row rolled back and was not retried forever.
        assertTrue(created.all { it.accountId == 1L })
        assertEquals("2026-11-13T09:00+01:00", rows.getValue(-1).nextOccurrence)
        assertEquals(0, rows.getValue(-1).occurrenceCount)
        assertEquals("2026-11-20T09:00:00+01:00", rows.getValue(-2).nextOccurrence)
        assertEquals(1, errors.size)
        assertEquals("boom", errors.single().message)
    }

    @Test fun oneTimeScheduleMaterializesOnceAndEnds() = runTest {
        schedule(next = "2026-11-13T09:00+01:00", rrule = "FREQ=DAILY", max = 1)
        assertEquals(1, materializer().run())
        assertEquals(1, created.size)
        assertFalse(rows.getValue(-1).active)
        assertNull(rows.values.firstOrNull { it.active })
    }
}
