package com.insituledger.app.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.AppDatabase
import com.insituledger.app.data.local.db.dao.AccountDao
import com.insituledger.app.data.local.db.dao.PendingOperationDao
import com.insituledger.app.data.local.db.dao.ScheduledTransactionDao
import com.insituledger.app.data.local.db.dao.TransactionDao
import com.insituledger.app.data.local.db.entity.PendingOperationEntity
import com.insituledger.app.data.local.db.entity.TransactionEntity
import com.insituledger.app.data.remote.api.SyncApi
import com.insituledger.app.data.remote.api.TransactionApi
import com.insituledger.app.data.remote.dto.SyncResponse
import com.insituledger.app.data.remote.dto.TransactionDto
import com.insituledger.app.data.sync.SyncManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * The recurring-entry marker (scheduled_id / occurrence / occurrence_total)
 * end to end on the client: wire JSON → DTO → Room entity (sync pull) →
 * domain model, preserved on local edits and never pushed.
 */
class RecurringMarkerMappingTest {
    private val database: AppDatabase = mockk(relaxed = true)
    private val syncApi: SyncApi = mockk()
    private val transactionApi: TransactionApi = mockk()
    private val transactionDao: TransactionDao = mockk(relaxed = true)
    private val scheduledDao: ScheduledTransactionDao = mockk(relaxed = true)
    private val accountDao: AccountDao = mockk(relaxed = true)
    private val pendingOpDao: PendingOperationDao = mockk(relaxed = true)
    private val prefs: UserPreferences = mockk(relaxed = true)
    private val gson = Gson()

    @Before fun setUp() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        val block = slot<suspend () -> Any?>()
        coEvery { database.withTransaction(capture(block)) } coAnswers { block.captured.invoke() }
        every { prefs.fullSnapshotDoneFlow } returns flowOf(true)
        every { prefs.lastSyncVersionFlow } returns flowOf(5L)
        every { prefs.userIdFlow } returns flowOf(1L)
        every { prefs.userNameFlow } returns flowOf("Me")
    }

    @After fun tearDown() { unmockkStatic("androidx.room.RoomDatabaseKt") }

    private fun syncRepository() = SyncRepository(
        database, syncApi, transactionApi, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        transactionDao, mockk(relaxed = true), accountDao, scheduledDao, pendingOpDao, prefs, gson
    )

    private fun transactionRepository() = TransactionRepository(
        database, transactionDao, accountDao, pendingOpDao, transactionApi, gson, mockk(relaxed = true), prefs
    )

    private fun transactionJson(extra: String) = """
        {"id": 41, "account_id": 1, "category_id": 2, "user_id": 1, "type": "expense",
         "amount": 950.0, "currency": "EUR", "description": "Rent", "note": null,
         "date": "2026-11-01T10:00:00+01:00", "created_at": "", "updated_at": "",
         "deleted_at": null, "sync_version": 6$extra}
    """.trimIndent()

    /** Pulls one transaction parsed from [json] and returns the entity Room received. */
    private suspend fun pullOne(json: String): TransactionEntity {
        val dto = gson.fromJson(json, TransactionDto::class.java)
        coEvery { syncApi.sync(5L) } returns Response.success(
            SyncResponse(currentVersion = 6, transactions = listOf(dto), categories = emptyList(),
                accounts = emptyList(), scheduledTransactions = emptyList())
        )
        val upserted = slot<List<TransactionEntity>>()
        coEvery { transactionDao.upsertAll(capture(upserted)) } returns Unit
        assertTrue(syncRepository().pull().isSuccess)
        return upserted.captured.single()
    }

    @Test fun `marker survives wire to entity to domain`() = runTest {
        val entity = pullOne(transactionJson(""", "scheduled_id": 9, "occurrence": 2, "occurrence_total": 3"""))
        assertEquals(9L, entity.scheduledId)
        assertEquals(2, entity.occurrence)
        assertEquals(3, entity.occurrenceTotal)

        coEvery { transactionDao.getById(41) } returns entity
        val domain = transactionRepository().getById(41)!!
        assertEquals(9L, domain.scheduledId)
        assertEquals(2, domain.occurrence)
        assertEquals(3, domain.occurrenceTotal)
    }

    @Test fun `open-ended marker keeps a null series length`() = runTest {
        val entity = pullOne(transactionJson(""", "scheduled_id": 9, "occurrence": 4, "occurrence_total": null"""))
        assertEquals(9L, entity.scheduledId)
        assertEquals(4, entity.occurrence)
        assertNull(entity.occurrenceTotal)
    }

    @Test fun `older servers without the fields map to an ordinary entry`() = runTest {
        val entity = pullOne(transactionJson(""))
        assertNull(entity.scheduledId)
        assertNull(entity.occurrence)
        assertNull(entity.occurrenceTotal)

        coEvery { transactionDao.getById(41) } returns entity
        assertNull(transactionRepository().getById(41)!!.scheduledId)
    }

    @Test fun `a re-sent known row picks up the backfilled schedule link`() = runTest {
        // The backend backfill links history to its schedule and bumps
        // sync_version, so an incremental pull re-sends rows this device
        // already has, with scheduled_id set and no position. The pull must
        // overwrite the stored row (Room @Upsert: replace on conflict), not
        // just insert unseen ones. The fake table mirrors that semantic.
        val table = linkedMapOf(41L to pullOne(transactionJson("")))
        assertNull(table.getValue(41).scheduledId)
        coEvery { transactionDao.upsertAll(any()) } answers {
            firstArg<List<TransactionEntity>>().forEach { table[it.id] = it }
        }
        val backfilled = gson.fromJson(
            transactionJson(""", "scheduled_id": 9""").replace("\"sync_version\": 6", "\"sync_version\": 7"),
            TransactionDto::class.java
        )
        coEvery { syncApi.sync(5L) } returns Response.success(
            SyncResponse(currentVersion = 7, transactions = listOf(backfilled), categories = emptyList(),
                accounts = emptyList(), scheduledTransactions = emptyList())
        )

        assertTrue(syncRepository().pull().isSuccess)

        val row = table.getValue(41)
        assertEquals(1, table.size)
        assertEquals(7L, row.syncVersion)
        assertEquals(9L, row.scheduledId)
        assertNull(row.occurrence)
        assertNull(row.occurrenceTotal)
        coEvery { transactionDao.getById(41) } returns row
        assertEquals("Recurring", com.insituledger.app.ui.common.seriesBadge(transactionRepository().getById(41)!!, null)?.text)
    }

    @Test fun `server list results carry the marker`() = runTest {
        val dto = gson.fromJson(
            transactionJson(""", "scheduled_id": 9, "occurrence": 1, "occurrence_total": 12"""),
            TransactionDto::class.java
        )
        coEvery { transactionApi.list(any(), any(), any(), any(), any(), any(), any(), any()) } returns Response.success(listOf(dto))
        val listed = transactionRepository().listFromServer(ownerId = 2).single()
        assertEquals(9L, listed.scheduledId)
        assertEquals(1, listed.occurrence)
        assertEquals(12, listed.occurrenceTotal)
    }

    @Test fun `editing an auto-added entry keeps its marker and never pushes it`() = runTest {
        every { prefs.getSyncModeImmediate() } returns "webapp"
        val existing = TransactionEntity(
            id = 41, accountId = 1, categoryId = 2, userId = 1, type = "expense", amount = 950.0,
            date = "2026-11-01T10:00:00+01:00", scheduledId = 9, occurrence = 2, occurrenceTotal = 3
        )
        coEvery { transactionDao.getById(41) } returns existing
        val saved = slot<TransactionEntity>()
        coEvery { transactionDao.upsert(capture(saved)) } returns Unit
        val op = slot<PendingOperationEntity>()
        coEvery { pendingOpDao.insert(capture(op)) } returns 1L

        transactionRepository().update(41, 1, 2, "expense", 975.0, "EUR", "Rent (new)", null, "2026-11-02T10:00:00+01:00")

        assertEquals(975.0, saved.captured.amount, 0.0)
        assertEquals(9L, saved.captured.scheduledId)
        assertEquals(2, saved.captured.occurrence)
        assertEquals(3, saved.captured.occurrenceTotal)
        assertEquals("UPDATE", op.captured.operation)
        val payload = op.captured.payloadJson!!
        assertFalse(payload, payload.contains("scheduled_id"))
        assertFalse(payload, payload.contains("occurrence"))
    }

    @Test fun `remapping a pushed local schedule repoints its entries`() = runTest {
        // A local schedule (-3) syncs as server id 70; entries it produced
        // locally must follow, or a future local schedule reusing -3 would
        // inherit them.
        val queue = mutableListOf(
            PendingOperationEntity(id = 1, entityType = "scheduled", operation = "CREATE", entityId = -3,
                payloadJson = gson.toJson(com.insituledger.app.data.remote.dto.ScheduledInput(
                    1, 2, "expense", 10.0, "EUR", "Gym", null, "FREQ=MONTHLY", "2026-12-01", 3
                )), clientId = "c")
        )
        coEvery { pendingOpDao.getAll() } answers { queue.toList() }
        coEvery { pendingOpDao.getById(1) } answers { queue.firstOrNull() }
        coEvery { pendingOpDao.delete(any()) } answers { queue.clear() }
        val scheduledApi: com.insituledger.app.data.remote.api.ScheduledApi = mockk()
        coEvery { scheduledApi.create(any(), any()) } returns Response.success(com.insituledger.app.data.remote.dto.IdResponse(70))
        val repo = SyncRepository(
            database, syncApi, transactionApi, mockk(relaxed = true), mockk(relaxed = true), scheduledApi,
            transactionDao, mockk(relaxed = true), accountDao, scheduledDao, pendingOpDao, prefs, gson
        )

        assertTrue(repo.pushPendingOperations().isSuccess)

        coVerify { scheduledDao.updateId(-3, 70) }
        coVerify { transactionDao.updateScheduledId(-3, 70) }
    }
}
