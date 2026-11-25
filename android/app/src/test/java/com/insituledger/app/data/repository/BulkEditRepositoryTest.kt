package com.insituledger.app.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.AppDatabase
import com.insituledger.app.data.local.db.dao.AccountDao
import com.insituledger.app.data.local.db.dao.CategoryDao
import com.insituledger.app.data.local.db.dao.PendingOperationDao
import com.insituledger.app.data.local.db.dao.ScheduledTransactionDao
import com.insituledger.app.data.local.db.dao.TransactionDao
import com.insituledger.app.data.local.db.entity.AccountEntity
import com.insituledger.app.data.local.db.entity.CategoryEntity
import com.insituledger.app.data.local.db.entity.PendingOperationEntity
import com.insituledger.app.data.local.db.entity.ScheduledTransactionEntity
import com.insituledger.app.data.local.db.entity.TransactionEntity
import com.insituledger.app.data.sync.SyncManager
import com.insituledger.app.domain.model.Category
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Bulk edit is a multi-row write, so every test checks what reached Room and
 * the pending-op queue, not just the returned counts. The DAOs are in-memory
 * maps; writes assert they happen inside withTransaction and sync asserts it
 * happens after.
 */
class BulkEditRepositoryTest {
    private val db: AppDatabase = mockk()
    private val transactionDao: TransactionDao = mockk(relaxed = true)
    private val scheduledDao: ScheduledTransactionDao = mockk(relaxed = true)
    private val accountDao: AccountDao = mockk(relaxed = true)
    private val categoryDao: CategoryDao = mockk(relaxed = true)
    private val pendingOpDao: PendingOperationDao = mockk(relaxed = true)
    private val prefs: UserPreferences = mockk(relaxed = true)
    private val syncManager: SyncManager = mockk(relaxed = true)
    private val gson = Gson()

    private var inTransaction = false
    private var transactionsRun = 0

    private val rows = linkedMapOf<Long, TransactionEntity>()
    private val schedules = linkedMapOf<Long, ScheduledTransactionEntity>()
    private val accounts = linkedMapOf<Long, AccountEntity>()
    private val categories = linkedMapOf<Long, CategoryEntity>()

    private val upsertedRows = mutableListOf<TransactionEntity>()
    private val upsertedSchedules = mutableListOf<ScheduledTransactionEntity>()
    private val ops = mutableListOf<PendingOperationEntity>()
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
        every { prefs.userIdFlow } returns flowOf(ME)
        every { prefs.serverVersionFlow } returns flowOf("1.27.0")

        coEvery { transactionDao.getByIds(any()) } answers {
            assertTrue("rows are read inside the transaction", inTransaction)
            firstArg<List<Long>>().mapNotNull { rows[it] }
        }
        coEvery { transactionDao.upsert(any()) } answers {
            assertTrue("row written inside the transaction", inTransaction)
            val row = firstArg<TransactionEntity>()
            upsertedRows += row
            rows[row.id] = row
        }
        coEvery { scheduledDao.getById(any()) } answers { schedules[firstArg()] }
        coEvery { scheduledDao.upsert(any()) } answers {
            assertTrue("schedule written inside the transaction", inTransaction)
            val s = firstArg<ScheduledTransactionEntity>()
            upsertedSchedules += s
            schedules[s.id] = s
        }
        coEvery { accountDao.getById(any()) } answers { accounts[firstArg()] }
        coEvery { categoryDao.getById(any()) } answers { categories[firstArg()] }
        coEvery { pendingOpDao.insert(any()) } answers {
            assertTrue("op queued inside the transaction", inTransaction)
            ops += firstArg<PendingOperationEntity>()
            ops.size.toLong()
        }
        every { syncManager.triggerImmediateSync() } answers {
            assertFalse("sync triggered after the commit", inTransaction)
            syncs++
        }

        // Owner ME: account 1 (synced) and account -1 (offline, user id 0).
        // Owner OTHER: account 2, shared with me.
        accounts[1] = AccountEntity(id = 1, userId = ME, name = "Main")
        accounts[-1] = AccountEntity(id = -1, userId = 0, name = "Cash", isLocalOnly = true)
        accounts[2] = AccountEntity(id = 2, userId = OTHER, name = "Family", isShared = true)
        categories[10] = CategoryEntity(id = 10, userId = ME, name = "Salary", type = "income")
        categories[11] = CategoryEntity(id = 11, userId = ME, name = "Bonus", type = "income")
        categories[12] = CategoryEntity(id = 12, userId = ME, name = "Food", type = "expense")
        categories[13] = CategoryEntity(id = 13, userId = OTHER, name = "Their salary", type = "income")
        categories[14] = CategoryEntity(id = 14, userId = ME, name = "Old", type = "income", deletedAt = "2026-01-01")
        categories[-5] = CategoryEntity(id = -5, userId = 0, name = "Offline", type = "income", isLocalOnly = true)
    }

    @After fun tearDown() { unmockkStatic("androidx.room.RoomDatabaseKt") }

    private fun repository() = BulkEditRepository(
        db, transactionDao, scheduledDao, accountDao, categoryDao, pendingOpDao, gson, syncManager, prefs
    )

    private fun row(
        id: Long,
        description: String? = "Stipendio",
        categoryId: Long = 10,
        type: String = "income",
        accountId: Long = 1,
        userId: Long = ME,
        scheduledId: Long? = null,
        deletedAt: String? = null
    ) = TransactionEntity(
        id = id, accountId = accountId, categoryId = categoryId, userId = userId, type = type,
        amount = 1500.0 + id, currency = "EUR", description = description, note = "note $id",
        date = "2026-10-27T09:00:00+01:00", createdAt = "2026-10-27T09:00:01+01:00",
        updatedAt = "2026-10-28T10:00:00+01:00", deletedAt = deletedAt, syncVersion = 40 + id,
        isLocalOnly = id < 0, createdByUserId = ME, createdByName = "Me",
        scheduledId = scheduledId, occurrence = scheduledId?.let { 3 }, occurrenceTotal = scheduledId?.let { 12 }
    ).also { rows[id] = it }

    private fun schedule(
        id: Long,
        type: String = "income",
        accountId: Long = 1,
        active: Boolean = true,
        deletedAt: String? = null,
        description: String? = "Stipendio",
        categoryId: Long = 10
    ) = ScheduledTransactionEntity(
        id = id, accountId = accountId, categoryId = categoryId, userId = ME, type = type, amount = 1500.0,
        currency = "EUR", description = description, note = "monthly", rrule = "FREQ=MONTHLY;BYMONTHDAY=27",
        nextOccurrence = "2026-11-27T09:00:00+01:00", active = active, maxOccurrences = 12, occurrenceCount = 3,
        createdAt = "2026-08-01", updatedAt = "2026-10-27", deletedAt = deletedAt, syncVersion = 77,
        createdByUserId = ME, createdByName = "Me"
    ).also { schedules[id] = it }

    private fun payload(op: PendingOperationEntity) = JsonParser.parseString(op.payloadJson).asJsonObject

    private fun assertNothingWritten() {
        assertTrue("no rows written: $upsertedRows", upsertedRows.isEmpty())
        assertTrue("no schedules written: $upsertedSchedules", upsertedSchedules.isEmpty())
        assertTrue("no ops queued: $ops", ops.isEmpty())
        assertEquals("no sync", 0, syncs)
    }

    private fun applied(result: BulkEditResult): BulkEditCounts {
        if (result !is BulkEditResult.Applied) fail("expected Applied, got $result")
        return (result as BulkEditResult.Applied).counts
    }

    // ---- Transactionality and sync ----

    @Test fun `writes and ops happen inside one transaction and sync runs once after it`() = runTest {
        row(1, description = "Stipendio Lisa")
        row(2, description = "stipendio")
        row(3, description = "Stipendio")

        val counts = applied(repository().apply(BulkEditRequest(ids = listOf(1, 2, 3), description = "Stipendio")))

        assertEquals(BulkEditCounts(updated = 2, unchanged = 1, skipped = 0), counts)
        assertEquals("one transaction for validation and writes", 1, transactionsRun)
        assertEquals(listOf(1L, 2L), upsertedRows.map { it.id })
        // Webapp mode: a PATCH for every live row, unchanged ones included.
        assertEquals(listOf(1L, 2L, 3L), ops.map { it.entityId })
        assertEquals(1, syncs)
    }

    @Test fun `an exception while queuing aborts before sync`() = runTest {
        row(1, description = "Old")
        coEvery { pendingOpDao.insert(any()) } answers {
            assertTrue(inTransaction)
            throw IllegalStateException("disk full")
        }

        val error = runCatching { repository().apply(BulkEditRequest(listOf(1), description = "New")) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(0, syncs)
        assertFalse(inTransaction)
    }

    // ---- The PATCH op ----

    @Test fun `rename-only patch carries only the description`() = runTest {
        row(42, description = "Stipendio Lisa")

        repository().apply(BulkEditRequest(ids = listOf(42), description = "Stipendio"))

        val op = ops.single()
        assertEquals("transaction", op.entityType)
        assertEquals("PATCH", op.operation)
        assertEquals(42L, op.entityId)
        assertEquals(42L, op.serverId)
        assertNull("PATCH ops need no client id", op.clientId)
        assertEquals(setOf("description"), payload(op).keySet())
        assertEquals("Stipendio", payload(op).get("description").asString)
    }

    @Test fun `category-only patch carries only the category`() = runTest {
        row(42, categoryId = 10)

        repository().apply(BulkEditRequest(ids = listOf(42), categoryId = 11))

        val op = ops.single()
        assertEquals(setOf("category_id"), payload(op).keySet())
        assertEquals(11L, payload(op).get("category_id").asLong)
    }

    @Test fun `patch with both fields carries both, the requested values, for every changed row`() = runTest {
        row(1, description = "Stipendio", categoryId = 10) // only the category changes
        row(2, description = "Paga", categoryId = 11)      // only the name changes

        repository().apply(BulkEditRequest(ids = listOf(1, 2), description = "Stipendio", categoryId = 11))

        assertEquals(2, ops.size)
        for (op in ops) {
            assertEquals(setOf("description", "category_id"), payload(op).keySet())
            assertEquals("Stipendio", payload(op).get("description").asString)
            assertEquals(11L, payload(op).get("category_id").asLong)
        }
    }

    @Test fun `negative id queues a patch with no server id`() = runTest {
        row(-7, description = "Offline", accountId = -1, userId = 0)

        repository().apply(BulkEditRequest(ids = listOf(-7), description = "Stipendio"))

        val op = ops.single()
        assertEquals(-7L, op.entityId)
        assertNull("deferred until its CREATE remaps entity_id", op.serverId)
    }

    // ---- Row preservation ----

    @Test fun `upsert is the row copy with only description and category changed`() = runTest {
        val original = row(5, description = "Stipendio Lisa", categoryId = 10, scheduledId = 70)

        repository().apply(BulkEditRequest(ids = listOf(5), description = "Stipendio", categoryId = 11))

        assertEquals(original.copy(description = "Stipendio", categoryId = 11), upsertedRows.single())
        // Spelled out for the columns corruption would hurt most.
        val saved = upsertedRows.single()
        assertEquals(original.amount, saved.amount, 0.0)
        assertEquals(original.date, saved.date)
        assertEquals(original.note, saved.note)
        assertEquals(original.accountId, saved.accountId)
        assertEquals(original.type, saved.type)
        assertEquals(70L, saved.scheduledId)
        assertEquals(3, saved.occurrence)
        assertEquals(12, saved.occurrenceTotal)
        assertEquals(original.createdByUserId, saved.createdByUserId)
        assertEquals(original.syncVersion, saved.syncVersion)
        assertNull(saved.deletedAt)
    }

    @Test fun `balances are never touched`() = runTest {
        row(1, description = "A")
        row(2, description = "B", categoryId = 11)

        repository().apply(BulkEditRequest(ids = listOf(1, 2), description = "C", categoryId = 10))

        coVerify(exactly = 0) { accountDao.adjustBalance(any(), any()) }
    }

    @Test fun `description is trimmed in the row and the payload`() = runTest {
        row(1, description = "Paga")

        repository().apply(BulkEditRequest(ids = listOf(1), description = "  Stipendio \t"))

        assertEquals("Stipendio", upsertedRows.single().description)
        assertEquals("Stipendio", payload(ops.single()).get("description").asString)
    }

    // ---- Skipped and unchanged rows ----

    // Was "missing, deleted and unchanged rows get no write and no op": an
    // unchanged row now still gets its PATCH (the local copy may be stale).
    @Test fun `missing and deleted rows get no write and no op, unchanged rows no write`() = runTest {
        row(1, description = "Stipendio")                         // unchanged
        row(2, description = "Paga", deletedAt = "2026-10-01")    // deleted
        row(3, description = "Paga")                              // changes
        // 4 doesn't exist

        val counts = applied(repository().apply(BulkEditRequest(ids = listOf(1, 2, 3, 4), description = "Stipendio")))

        assertEquals(BulkEditCounts(updated = 1, unchanged = 1, skipped = 2), counts)
        assertEquals(listOf(3L), upsertedRows.map { it.id })
        assertEquals(listOf(1L, 3L), ops.map { it.entityId })
        assertEquals("deleted row not resurrected", "2026-10-01", rows[2]!!.deletedAt)
    }

    // Was "nothing changed means no op and no sync". Room is still left alone,
    // but webapp mode sends the PATCH: the server's copy may differ.
    @Test fun `nothing changed locally writes no row but still queues the patch`() = runTest {
        row(1, description = "Stipendio", categoryId = 10)

        val counts = applied(repository().apply(BulkEditRequest(ids = listOf(1), description = "Stipendio", categoryId = 10)))

        assertEquals(BulkEditCounts(updated = 0, unchanged = 1, skipped = 0), counts)
        assertTrue(upsertedRows.isEmpty())
        assertEquals(listOf(1L), ops.map { it.entityId })
        assertEquals(1, syncs)
    }

    @Test fun `no live rows applies nothing with zero counts`() = runTest {
        row(1, deletedAt = "deleted")

        val counts = applied(repository().apply(BulkEditRequest(ids = listOf(1, 99), categoryId = 11)))

        assertEquals(BulkEditCounts(updated = 0, unchanged = 0, skipped = 2), counts)
        assertNothingWritten()
    }

    @Test fun `duplicate ids are counted and written once`() = runTest {
        row(1, description = "Paga")

        val counts = applied(repository().apply(BulkEditRequest(ids = listOf(1, 1, 1), description = "Stipendio")))

        assertEquals(BulkEditCounts(updated = 1, unchanged = 0, skipped = 0), counts)
        assertEquals(1, upsertedRows.size)
        assertEquals(1, ops.size)
    }

    // ---- Local mode ----

    @Test fun `local mode writes rows but queues no ops and never syncs`() = runTest {
        every { prefs.getSyncModeImmediate() } returns "none"
        every { prefs.userIdFlow } returns flowOf(null)
        accounts[-1] = AccountEntity(id = -1, userId = 0, name = "Cash", isLocalOnly = true)
        row(-3, description = "Paga", accountId = -1, userId = 0, categoryId = -5)

        // A negative (local) category is fine without sync.
        val counts = applied(repository().apply(BulkEditRequest(ids = listOf(-3), description = "Stipendio", categoryId = -5)))

        assertEquals(1, counts.updated)
        assertEquals("Stipendio", upsertedRows.single().description)
        assertTrue(ops.isEmpty())
        assertEquals(0, syncs)
        verify(exactly = 0) { syncManager.triggerImmediateSync() }
    }

    // ---- Ownership ----

    @Test fun `account user id 0 is normalised to the current user`() = runTest {
        // One row on a synced account (user 1), one on an offline account (user 0):
        // the same owner, so a category change is allowed.
        row(1, description = "Paga", accountId = 1)
        row(-2, description = "Paga", accountId = -1, userId = 0)

        val counts = applied(repository().apply(BulkEditRequest(ids = listOf(1, -2), categoryId = 11)))

        assertEquals(2, counts.updated)
        assertEquals(listOf(11L, 11L), upsertedRows.map { it.categoryId })
    }

    @Test fun `category user id 0 is normalised to the current user`() = runTest {
        every { prefs.getSyncModeImmediate() } returns "none"
        row(1, description = "Paga", accountId = 1)

        val counts = applied(repository().apply(BulkEditRequest(ids = listOf(1), categoryId = -5)))

        assertEquals(1, counts.updated)
    }

    @Test fun `rename across owners is allowed`() = runTest {
        row(1, description = "Paga", accountId = 1)
        row(2, description = "Paga", accountId = 2, userId = OTHER, categoryId = 13)

        val counts = applied(repository().apply(BulkEditRequest(ids = listOf(1, 2), description = "Stipendio")))

        assertEquals(2, counts.updated)
    }

    // ---- Rejections: nothing written, nothing queued, no sync ----

    @Test fun `every rejection writes nothing and does not sync`() = runTest {
        row(1, description = "Paga", type = "income", categoryId = 10, scheduledId = 70)
        row(2, description = "Paga", type = "income", accountId = 2, userId = OTHER, categoryId = 13)
        row(3, description = "Spesa", type = "expense", categoryId = 12)
        schedule(70, type = "expense", categoryId = 12)
        schedule(71, type = "income", accountId = 2)
        row(4, description = "Paga", type = "income", categoryId = 10, scheduledId = 71)

        val cases: List<Pair<BulkEditRequest, String>> = listOf(
            BulkEditRequest(ids = emptyList(), description = "X") to BulkEditMessages.IDS_REQUIRED,
            BulkEditRequest(ids = listOf(1)) to BulkEditMessages.NOTHING_TO_UPDATE,
            BulkEditRequest(ids = (1L..5001L).toList(), description = "X") to BulkEditMessages.TOO_MANY_IDS,
            BulkEditRequest(ids = listOf(1), description = "   ") to BulkEditMessages.DESCRIPTION_EMPTY,
            BulkEditRequest(ids = listOf(1), description = "a".repeat(501)) to BulkEditMessages.DESCRIPTION_TOO_LONG,
            BulkEditRequest(ids = listOf(1), description = "è".repeat(250) + "a") to BulkEditMessages.DESCRIPTION_TOO_LONG,
            BulkEditRequest(ids = listOf(1), categoryId = 0) to BulkEditMessages.INVALID_CATEGORY,
            BulkEditRequest(ids = listOf(1), categoryId = -5) to BulkEditMessages.CATEGORY_NOT_SYNCED,
            BulkEditRequest(ids = listOf(1, 2), categoryId = 11) to BulkEditMessages.MULTIPLE_OWNERS,
            BulkEditRequest(ids = listOf(1), categoryId = 999) to BulkEditMessages.CATEGORY_NOT_FOUND,
            BulkEditRequest(ids = listOf(1), categoryId = 14) to BulkEditMessages.CATEGORY_NOT_FOUND,
            BulkEditRequest(ids = listOf(1), categoryId = 13) to BulkEditMessages.CATEGORY_NOT_FOUND,
            BulkEditRequest(ids = listOf(1, 3), categoryId = 11) to BulkEditMessages.MIXED_TYPES,
            BulkEditRequest(ids = listOf(1), categoryId = 12) to BulkEditMessages.TYPE_MISMATCH,
            BulkEditRequest(ids = listOf(1), description = "Stipendio", categoryId = 11, updateSchedules = true)
                to BulkEditMessages.SCHEDULE_TYPE_MISMATCH,
            BulkEditRequest(ids = listOf(4), categoryId = 11, updateSchedules = true)
                to BulkEditMessages.SCHEDULE_OTHER_OWNER
        )
        for ((request, message) in cases) {
            val result = repository().apply(request)
            assertEquals("for $request", BulkEditResult.Rejected(message), result)
            assertNothingWritten()
        }
        // Every row still holds its original value.
        assertEquals(listOf("Paga", "Paga", "Spesa", "Paga"), listOf(1L, 2L, 3L, 4L).map { rows[it]!!.description })
    }

    @Test fun `exactly 500 bytes is accepted, ASCII and multi-byte`() = runTest {
        row(1, description = "Paga")
        row(2, description = "Paga")

        applied(repository().apply(BulkEditRequest(ids = listOf(1), description = "a".repeat(500))))
        applied(repository().apply(BulkEditRequest(ids = listOf(2), description = "è".repeat(250))))

        assertEquals("a".repeat(500), rows[1]!!.description)
        assertEquals("è".repeat(250), rows[2]!!.description)
    }

    @Test fun `exactly 5000 ids is accepted`() = runTest {
        row(1, description = "Paga")

        val counts = applied(repository().apply(BulkEditRequest(ids = (1L..5000L).toList(), description = "Stipendio")))

        assertEquals(BulkEditCounts(updated = 1, unchanged = 0, skipped = 4999), counts)
        // Read in chunks that stay under SQLite's bound-variable limit.
        coVerify(exactly = 10) { transactionDao.getByIds(match { it.size == 500 }) }
    }

    // ---- Linked schedules ----

    @Test fun `schedule flag is off by default`() = runTest {
        row(1, description = "Paga", scheduledId = 70)
        schedule(70, description = "Paga")

        val counts = applied(repository().apply(BulkEditRequest(ids = listOf(1), description = "Stipendio")))

        assertEquals(0, counts.schedulesUpdated)
        assertTrue(upsertedSchedules.isEmpty())
        assertEquals(listOf("transaction"), ops.map { it.entityType })
        assertEquals("Paga", schedules[70]!!.description)
    }

    @Test fun `only live schedules are touched and their series fields are preserved`() = runTest {
        row(1, description = "Paga", scheduledId = 70)
        row(2, description = "Paga", scheduledId = 71)
        row(3, description = "Paga", scheduledId = 72)
        row(4, description = "Paga", scheduledId = 70) // same schedule twice
        row(5, description = "Paga", scheduledId = 73) // schedule purged from Room
        val live = schedule(70, description = "Paga")
        val paused = schedule(71, description = "Paga", active = false)
        schedule(72, description = "Paga", deletedAt = "2026-10-01") // finished

        val counts = applied(repository().apply(
            BulkEditRequest(ids = listOf(1, 2, 3, 4, 5), description = "Stipendio", categoryId = 11, updateSchedules = true)
        ))

        assertEquals(5, counts.updated)
        assertEquals(2, counts.schedulesUpdated)
        assertEquals(0, counts.schedulesUnchanged)
        assertEquals("finished and missing schedules are skipped", 2, counts.schedulesSkipped)
        assertEquals(
            listOf(
                live.copy(description = "Stipendio", categoryId = 11),
                paused.copy(description = "Stipendio", categoryId = 11)
            ),
            upsertedSchedules
        )
        val saved = upsertedSchedules.first()
        assertEquals(live.nextOccurrence, saved.nextOccurrence)
        assertEquals(live.rrule, saved.rrule)
        assertEquals(live.amount, saved.amount, 0.0)
        assertEquals(live.occurrenceCount, saved.occurrenceCount)
        assertEquals(live.maxOccurrences, saved.maxOccurrences)
        assertEquals(live.active, saved.active)
        assertFalse("paused stays paused", upsertedSchedules[1].active)
        assertEquals("2026-10-01", schedules[72]!!.deletedAt)

        val scheduleOps = ops.filter { it.entityType == "scheduled" }
        assertEquals(listOf(70L, 71L), scheduleOps.map { it.entityId })
        assertTrue(scheduleOps.all { it.operation == "PATCH" && it.serverId == it.entityId })
        assertEquals(setOf("description", "category_id"), payload(scheduleOps.first()).keySet())
        assertEquals(1, syncs)
    }

    @Test fun `schedule rename alone needs no type check and a negative schedule defers`() = runTest {
        row(1, description = "Paga", scheduledId = -4)
        schedule(-4, type = "expense", description = "Paga")

        val counts = applied(repository().apply(BulkEditRequest(ids = listOf(1), description = "Stipendio", updateSchedules = true)))

        assertEquals(1, counts.schedulesUpdated)
        val op = ops.single { it.entityType == "scheduled" }
        assertEquals(-4L, op.entityId)
        assertNull(op.serverId)
        assertEquals(setOf("description"), payload(op).keySet())
    }

    // Was "unchanged schedule gets no write and no op": the PATCH now goes
    // out anyway, the schedule row is still not rewritten.
    @Test fun `unchanged schedule gets no write but still a patch`() = runTest {
        row(1, description = "Paga", scheduledId = 70)
        schedule(70, description = "Stipendio")

        val counts = applied(repository().apply(BulkEditRequest(ids = listOf(1), description = "Stipendio", updateSchedules = true)))

        assertEquals(1, counts.schedulesUnchanged)
        assertTrue(upsertedSchedules.isEmpty())
        assertEquals(listOf("transaction", "scheduled"), ops.map { it.entityType })
        assertEquals(70L, ops.last().entityId)
    }

    // ---- Every live row is patched in webapp mode ----

    @Test fun patchQueuedForLocallyUnchangedRowsInWebappMode() = runTest {
        // The local copies already say "Stipendio" (maybe stale: renamed on
        // the web since the last pull). The server must still be told.
        row(1, description = "Stipendio", scheduledId = 70)
        row(2, description = "Stipendio")
        row(-3, description = "Stipendio", accountId = -1, userId = 0)
        row(4, description = "Paga")
        schedule(70, description = "Stipendio")

        val counts = applied(repository().apply(
            BulkEditRequest(ids = listOf(1, 2, -3, 4), description = "Stipendio", updateSchedules = true)
        ))

        assertEquals(BulkEditCounts(updated = 1, unchanged = 3, skipped = 0, schedulesUnchanged = 1), counts)
        assertEquals("only the changed row is rewritten", listOf(4L), upsertedRows.map { it.id })
        assertTrue(upsertedSchedules.isEmpty())
        assertEquals(
            listOf("transaction" to 1L, "transaction" to 2L, "transaction" to -3L, "transaction" to 4L, "scheduled" to 70L),
            ops.map { it.entityType to it.entityId }
        )
        assertTrue(ops.all { it.operation == "PATCH" })
        // Byte-identical payloads, so the push loop sends them as one batch.
        assertEquals(1, ops.map { it.payloadJson }.distinct().size)
        assertEquals(listOf(1L, 2L, null, 4L, 70L), ops.map { it.serverId })
        assertEquals(1, syncs)
    }

    @Test fun noOpsInLocalMode() = runTest {
        every { prefs.getSyncModeImmediate() } returns "none"
        // Local mode never asks the server, so no version is needed either.
        every { prefs.serverVersionFlow } returns flowOf(null)
        row(1, description = "Stipendio", scheduledId = 70)
        row(2, description = "Paga")
        schedule(70, description = "Paga")

        val counts = applied(repository().apply(
            BulkEditRequest(ids = listOf(1, 2), description = "Stipendio", updateSchedules = true)
        ))

        assertEquals(BulkEditCounts(updated = 1, unchanged = 1, skipped = 0, schedulesUpdated = 1), counts)
        assertEquals(listOf(2L), upsertedRows.map { it.id })
        assertEquals(listOf(70L), upsertedSchedules.map { it.id })
        assertTrue(ops.isEmpty())
        assertEquals(0, syncs)
        assertNull(repository().summarize(listOf(1, 2)).serverBlockedReason)
    }

    // ---- Server version gate (webapp mode) ----

    @Test fun versionGateRejectsUnknownAndOldServer() = runTest {
        row(1, description = "Paga")
        val cases = listOf(
            null to BulkEditMessages.SERVER_VERSION_UNKNOWN, // never synced with this server
            com.insituledger.app.util.ServerVersion.OLD to BulkEditMessages.SERVER_TOO_OLD, // /api/health had no version
            "1.26.2" to BulkEditMessages.SERVER_TOO_OLD,
            "1.9.9" to BulkEditMessages.SERVER_TOO_OLD,
            "garbage" to BulkEditMessages.SERVER_TOO_OLD
        )
        for ((version, message) in cases) {
            every { prefs.serverVersionFlow } returns flowOf(version)

            assertEquals("for $version", BulkEditResult.Rejected(message), repository().apply(BulkEditRequest(listOf(1), description = "Stipendio")))
            assertNothingWritten()
            assertEquals("the sheet shows the same reason", message, repository().summarize(listOf(1)).serverBlockedReason)
        }
        assertEquals("Paga", rows[1]!!.description)
    }

    @Test fun versionGateAllows1_27AndNewer() = runTest {
        for ((i, version) in listOf("1.27.0", "1.27.3", "1.28.0", "2.0", "v1.27.0").withIndex()) {
            val id = i + 1L
            row(id, description = "Paga")
            every { prefs.serverVersionFlow } returns flowOf(version)

            val counts = applied(repository().apply(BulkEditRequest(listOf(id), description = "Stipendio")))

            assertEquals("for $version", 1, counts.updated)
            assertNull(repository().summarize(listOf(id)).serverBlockedReason)
        }
        assertEquals(5, ops.size)
    }

    // ---- Summary and category options ----

    @Test fun `summarize reports live rows, types, owners and live linked schedules`() = runTest {
        row(1, type = "income", categoryId = 10, scheduledId = 70)
        row(2, type = "income", categoryId = 10, accountId = -1, userId = 0, scheduledId = 72)
        row(3, deletedAt = "deleted", type = "expense", categoryId = 12)
        schedule(70)
        schedule(72, deletedAt = "finished")

        val summary = repository().summarize(listOf(1, 2, 3, 99))

        assertEquals(4, summary.requested)
        assertEquals(2, summary.liveCount)
        assertEquals(setOf("income"), summary.types)
        assertEquals(setOf(ME), summary.owners)
        assertEquals(10L, summary.commonCategoryId)
        assertEquals(listOf(70L), summary.linkedScheduleIds)
        assertNull(summary.categoryBlockedReason)
        assertNothingWritten()
    }

    @Test fun `mixed selections block the category with a reason`() = runTest {
        row(1, type = "income")
        row(2, type = "expense", categoryId = 12)
        row(3, type = "income", accountId = 2, userId = OTHER, categoryId = 13)

        val mixedTypes = repository().summarize(listOf(1, 2))
        val mixedOwners = repository().summarize(listOf(1, 3))

        assertTrue(mixedTypes.categoryBlockedReason!!.contains("mix income and expense"))
        assertTrue(mixedOwners.categoryBlockedReason!!.contains("different owners"))
        val all = categories.values.map { Category(it.id, it.userId, it.parentId, it.name, it.type, null, null) }
        assertTrue(bulkCategoryOptions(all, mixedTypes, syncEnabled = true).isEmpty())
        assertTrue(bulkCategoryOptions(all, mixedOwners, syncEnabled = true).isEmpty())
    }

    @Test fun `category options are the owner's, of the selection type, synced in webapp mode`() = runTest {
        row(1, type = "income")
        val summary = repository().summarize(listOf(1))
        val all = categories.values.filter { it.deletedAt == null }
            .map { Category(it.id, it.userId, it.parentId, it.name, it.type, null, null) }

        assertEquals(listOf(10L, 11L), bulkCategoryOptions(all, summary, syncEnabled = true).map { it.id })
        assertEquals(listOf(10L, 11L, -5L), bulkCategoryOptions(all, summary, syncEnabled = false).map { it.id })
    }

    private companion object {
        const val ME = 1L
        const val OTHER = 2L
    }
}
