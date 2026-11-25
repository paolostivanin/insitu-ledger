package com.insituledger.app.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.AppDatabase
import com.insituledger.app.data.local.db.dao.AccountDao
import com.insituledger.app.data.local.db.dao.CategoryDao
import com.insituledger.app.data.local.db.dao.PendingOperationDao
import com.insituledger.app.data.local.db.dao.ScheduledTransactionDao
import com.insituledger.app.data.local.db.dao.TransactionDao
import com.insituledger.app.data.local.db.entity.PendingOperationEntity
import com.insituledger.app.data.remote.api.AccountApi
import com.insituledger.app.data.remote.api.CategoryApi
import com.insituledger.app.data.remote.api.ScheduledApi
import com.insituledger.app.data.remote.api.SyncApi
import com.insituledger.app.data.remote.api.TransactionApi
import com.insituledger.app.data.remote.dto.AccountInput
import com.insituledger.app.data.remote.dto.BatchUpdateRequest
import com.insituledger.app.data.remote.dto.BatchUpdateResponse
import com.insituledger.app.data.remote.dto.BulkPatchPayload
import com.insituledger.app.data.remote.dto.ScheduledBatchUpdateRequest
import com.insituledger.app.data.remote.dto.CategoryInput
import com.insituledger.app.data.remote.dto.CreateTransactionResponse
import com.insituledger.app.data.remote.dto.IdResponse
import com.insituledger.app.data.remote.dto.ScheduledInput
import com.insituledger.app.data.remote.dto.TransactionInput
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class SyncRepositoryTest {

    private val database: AppDatabase = mockk(relaxed = true)
    private val syncApi: SyncApi = mockk(relaxed = true)
    private val transactionApi: TransactionApi = mockk()
    private val categoryApi: CategoryApi = mockk(relaxed = true)
    private val accountApi: AccountApi = mockk(relaxed = true)
    private val scheduledApi: ScheduledApi = mockk(relaxed = true)
    private val transactionDao: TransactionDao = mockk(relaxed = true)
    private val categoryDao: CategoryDao = mockk(relaxed = true)
    private val accountDao: AccountDao = mockk(relaxed = true)
    private val scheduledDao: ScheduledTransactionDao = mockk(relaxed = true)
    private val pendingOpDao: PendingOperationDao = mockk(relaxed = true)
    private val prefs: UserPreferences = mockk(relaxed = true)
    private val gson = Gson()

    // Mini in-memory pending-op DAO so multi-step flows (CREATE → remap →
    // dependent UPDATE) observe queue state changes the way Room would.
    private val queue = mutableListOf<PendingOperationEntity>()
    private var nextOpId = 1L
    // Same as the DAO's ORDER BY id ASC: queue order, never the wall clock.
    private val queueOrder = compareBy<PendingOperationEntity> { it.id }

    private fun newRepository() = SyncRepository(
        database, syncApi, transactionApi, categoryApi, accountApi, scheduledApi,
        transactionDao, categoryDao, accountDao, scheduledDao, pendingOpDao,
        prefs, gson
    )

    @Before
    fun setUp() {
        coEvery { prefs.fullSnapshotDoneFlow } returns kotlinx.coroutines.flow.flowOf(true)
        queue.clear()
        nextOpId = 1L
        // Route Room's withTransaction block straight through so remap*Id
        // executes its body in tests.
        mockkStatic("androidx.room.RoomDatabaseKt")
        val block = slot<suspend () -> Any?>()
        coEvery { database.withTransaction(capture(block)) } coAnswers { block.captured.invoke() }

        coEvery { pendingOpDao.getAll() } answers {
            queue.filter { it.state == PendingOperationEntity.STATE_PENDING }.sortedWith(queueOrder)
        }
        coEvery { pendingOpDao.deleteFailed() } answers {
            queue.removeAll { it.state == PendingOperationEntity.STATE_FAILED }
        }
        // Mirrors the DAO: same entity (COALESCE(server_id, entity_id)), queued
        // earlier, any state, never a CREATE.
        coEvery { pendingOpDao.getOlderOpsForEntity(any(), any(), any()) } answers {
            val type = firstArg<String>()
            val entityId = secondArg<Long>()
            val beforeId = thirdArg<Long>()
            queue.filter {
                it.entityType == type && (it.serverId ?: it.entityId) == entityId &&
                    it.id < beforeId && it.operation != "CREATE"
            }.sortedWith(queueOrder)
        }
        coEvery { pendingOpDao.retryFailed() } answers {
            for (i in queue.indices) {
                if (queue[i].state == PendingOperationEntity.STATE_FAILED) {
                    queue[i] = queue[i].copy(state = PendingOperationEntity.STATE_PENDING, lastError = null)
                }
            }
        }
        coEvery { pendingOpDao.delete(any()) } answers {
            val op = firstArg<PendingOperationEntity>()
            queue.removeAll { it.id == op.id }
        }
        coEvery { pendingOpDao.updateEntityId(any(), any(), any()) } answers {
            val oldId = firstArg<Long>()
            val newId = secondArg<Long>()
            val type = thirdArg<String>()
            for (i in queue.indices) {
                val op = queue[i]
                if (op.entityType == type && op.entityId == oldId) {
                    queue[i] = op.copy(entityId = newId)
                }
            }
        }
        coEvery { pendingOpDao.updatePayloadJson(any(), any()) } answers {
            val id = firstArg<Long>()
            val payload = secondArg<String>()
            for (i in queue.indices) {
                if (queue[i].id == id) queue[i] = queue[i].copy(payloadJson = payload)
            }
        }
        coEvery { pendingOpDao.markFailed(any(), any()) } answers {
            val id = firstArg<Long>()
            val error = secondArg<String>()
            for (i in queue.indices) {
                if (queue[i].id == id) {
                    queue[i] = queue[i].copy(
                        state = PendingOperationEntity.STATE_FAILED,
                        lastError = error
                    )
                }
            }
        }
        coEvery { pendingOpDao.deleteByEntity(any(), any()) } answers {
            val type = firstArg<String>()
            val id = secondArg<Long>()
            queue.removeAll { it.entityType == type && it.entityId == id }
        }
        // Like the real query: pending ops only, in queue order.
        coEvery { pendingOpDao.findByEntityTypeAndOperation(any(), any()) } answers {
            val type = firstArg<String>()
            val op = secondArg<String>()
            queue.filter {
                it.state == PendingOperationEntity.STATE_PENDING && it.entityType == type && it.operation == op
            }.sortedWith(queueOrder)
        }
        coEvery { pendingOpDao.getById(any()) } answers {
            val id = firstArg<Long>()
            queue.firstOrNull { it.id == id }
        }
        coEvery { pendingOpDao.insert(any()) } answers { enqueue(firstArg()).id }
        // Like the real queries: CREATEs only, any state, oldest first.
        coEvery { pendingOpDao.findCreatesAnyState(any(), any()) } answers {
            val type = firstArg<String>()
            val entityId = secondArg<Long>()
            queue.filter { it.entityType == type && it.operation == "CREATE" && it.entityId == entityId }
                .sortedWith(queueOrder)
        }
        coEvery { pendingOpDao.requeueCreate(any(), any()) } answers {
            val id = firstArg<Long>()
            val payload = secondArg<String>()
            for (i in queue.indices) {
                if (queue[i].id == id) {
                    queue[i] = queue[i].copy(state = PendingOperationEntity.STATE_PENDING, lastError = null, payloadJson = payload)
                }
            }
        }
        coEvery { pendingOpDao.deleteOtherCreates(any(), any(), any()) } answers {
            val type = firstArg<String>()
            val ids = secondArg<List<Long>>()
            val keepId = thirdArg<Long>()
            queue.removeAll { it.entityType == type && it.operation == "CREATE" && it.entityId in ids && it.id != keepId }
        }
        coEvery { accountDao.getAllSync() } returns emptyList()
        coEvery { categoryDao.getAllSync() } returns emptyList()
        coEvery { transactionDao.getAllSync() } returns emptyList()
        coEvery { scheduledDao.getAllSync() } returns emptyList()
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    private fun enqueue(op: PendingOperationEntity): PendingOperationEntity {
        val withId = if (op.id == 0L) op.copy(id = nextOpId++) else op
        queue.add(withId)
        return withId
    }

    private fun txCreate(localId: Long, accountId: Long, categoryId: Long): PendingOperationEntity {
        val input = TransactionInput(accountId, categoryId, "expense", 4.15, "EUR", null, null, "2026-06-12T08:41:00+03:00")
        return enqueue(PendingOperationEntity(
            entityType = "transaction", operation = "CREATE",
            entityId = localId, payloadJson = gson.toJson(input), clientId = "tx-$localId"
        ))
    }

    private fun txUpdate(entityId: Long, accountId: Long, categoryId: Long): PendingOperationEntity {
        val input = TransactionInput(accountId, categoryId, "expense", 9.99, "EUR", "edited", null, "2026-06-12T08:41:00+03:00")
        return enqueue(PendingOperationEntity(
            entityType = "transaction", operation = "UPDATE",
            entityId = entityId,
            serverId = if (entityId > 0) entityId else null,
            payloadJson = gson.toJson(input)
        ))
    }

    private fun txDelete(entityId: Long): PendingOperationEntity {
        return enqueue(PendingOperationEntity(
            entityType = "transaction", operation = "DELETE",
            entityId = entityId,
            serverId = if (entityId > 0) entityId else null,
            payloadJson = null
        ))
    }

    private fun acctCreate(localId: Long, name: String = "Wallet"): PendingOperationEntity {
        val input = AccountInput(name, "EUR", 0.0)
        return enqueue(PendingOperationEntity(
            entityType = "account", operation = "CREATE",
            entityId = localId, payloadJson = gson.toJson(input), clientId = "acct-$localId"
        ))
    }

    private fun acctUpdate(entityId: Long): PendingOperationEntity = enqueue(PendingOperationEntity(
        entityType = "account", operation = "UPDATE", entityId = entityId,
        serverId = if (entityId > 0) entityId else null,
        payloadJson = gson.toJson(AccountInput("Renamed", "EUR", null))
    ))

    private fun acctDelete(entityId: Long): PendingOperationEntity = enqueue(PendingOperationEntity(
        entityType = "account", operation = "DELETE", entityId = entityId,
        serverId = if (entityId > 0) entityId else null
    ))

    private fun catCreate(localId: Long, parentId: Long? = null): PendingOperationEntity {
        val input = CategoryInput(parentId, "Food", "expense", null, null)
        return enqueue(PendingOperationEntity(
            entityType = "category", operation = "CREATE",
            entityId = localId, payloadJson = gson.toJson(input), clientId = "cat-$localId"
        ))
    }

    private fun catUpdate(entityId: Long): PendingOperationEntity = enqueue(PendingOperationEntity(
        entityType = "category", operation = "UPDATE", entityId = entityId,
        serverId = if (entityId > 0) entityId else null,
        payloadJson = gson.toJson(CategoryInput(null, "Renamed", "expense", null, null))
    ))

    private fun catDelete(entityId: Long): PendingOperationEntity = enqueue(PendingOperationEntity(
        entityType = "category", operation = "DELETE", entityId = entityId,
        serverId = if (entityId > 0) entityId else null
    ))

    private fun schedCreate(localId: Long): PendingOperationEntity = enqueue(PendingOperationEntity(
        entityType = "scheduled", operation = "CREATE", entityId = localId,
        payloadJson = gson.toJson(ScheduledInput(
            1L, 2L, "expense", 10.0, "EUR", null, null,
            "FREQ=MONTHLY", "2026-07-01", null
        )),
        clientId = "sched-$localId"
    ))

    private fun schedUpdate(entityId: Long): PendingOperationEntity = enqueue(PendingOperationEntity(
        entityType = "scheduled", operation = "UPDATE", entityId = entityId,
        serverId = if (entityId > 0) entityId else null,
        payloadJson = gson.toJson(ScheduledInput(
            1L, 2L, "expense", 12.0, "EUR", null, null,
            "FREQ=MONTHLY", "2026-07-01", null
        ))
    ))

    private fun schedDelete(entityId: Long): PendingOperationEntity = enqueue(PendingOperationEntity(
        entityType = "scheduled", operation = "DELETE", entityId = entityId,
        serverId = if (entityId > 0) entityId else null
    ))

    private inline fun <reified T> errorBody(code: Int): Response<T> = Response.error(
        code, "".toResponseBody("application/json".toMediaTypeOrNull())
    )

    // Bulk-edit PATCH, as BulkEditRepository queues it.
    private fun txPatch(
        entityId: Long,
        description: String? = "Stipendio",
        categoryId: Long? = null,
        createdAt: Long = System.currentTimeMillis()
    ): PendingOperationEntity = enqueue(PendingOperationEntity(
        entityType = "transaction", operation = "PATCH", entityId = entityId,
        serverId = entityId.takeIf { it > 0 },
        payloadJson = gson.toJson(BulkPatchPayload(description, categoryId)),
        createdAt = createdAt
    ))

    private fun schedPatch(entityId: Long, description: String? = null, categoryId: Long? = 11): PendingOperationEntity =
        enqueue(PendingOperationEntity(
            entityType = "scheduled", operation = "PATCH", entityId = entityId,
            serverId = entityId.takeIf { it > 0 },
            payloadJson = gson.toJson(BulkPatchPayload(description, categoryId))
        ))

    private fun ok() = Response.success(BatchUpdateResponse(updated = 1))

    private fun txInput(op: PendingOperationEntity): TransactionInput =
        gson.fromJson(op.payloadJson, TransactionInput::class.java)

    private fun payloadKeys(op: PendingOperationEntity): Set<String> =
        com.google.gson.JsonParser.parseString(op.payloadJson).asJsonObject.keySet()

    private fun emptySync() {
        coEvery { prefs.lastSyncVersionFlow } returns kotlinx.coroutines.flow.flowOf(0L)
        coEvery { syncApi.sync(any()) } returns Response.success(
            com.insituledger.app.data.remote.dto.SyncResponse(
                currentVersion = 1, transactions = emptyList(), categories = emptyList(),
                accounts = emptyList(), scheduledTransactions = emptyList()
            )
        )
    }

    // ====================
    // Pre-existing behaviour: preserved by the rewrite.
    // ====================

    // PLAN §6.5 defense: if the backend ever converts a transaction CREATE
    // into a scheduled tx ({"scheduled": true}), the local optimistic row must
    // be dropped (the next pull brings the scheduled_transactions row) and the
    // returned id must NOT be remapped onto the transactions table — pre-1.28
    // that remap produced a phantom local row pointing at a
    // scheduled_transactions id, the visible-duplicate bug.
    @Test
    fun createRespondingScheduledTrueDropsLocalRowAndSkipsRemap() = runTest {
        txCreate(localId = -7, accountId = 1, categoryId = 2)
        coEvery { transactionApi.create(any(), isNull()) } returns
            Response.success(CreateTransactionResponse(id = 99, scheduled = true))

        val result = newRepository().pushPendingOperations()

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { transactionDao.deleteById(-7) }
        coVerify(exactly = 0) { transactionDao.updateId(any(), any()) }
        assertTrue("Queue should be empty after success", queue.isEmpty())
    }

    @Test
    fun createRespondingPlainIdRemaps() = runTest {
        txCreate(localId = -7, accountId = 1, categoryId = 2)
        coEvery { transactionApi.create(any(), isNull()) } returns
            Response.success(CreateTransactionResponse(id = 99))

        val result = newRepository().pushPendingOperations()

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { transactionDao.updateId(-7, 99) }
        coVerify(exactly = 0) { transactionDao.deleteById(any()) }
        assertTrue(queue.isEmpty())
    }

    // ====================
    // A1: process queue with re-read so dependent UPDATE/DELETE
    // sees the remapped positive id.
    // ====================

    @Test
    fun offlineCreateThenUpdateUsesRemappedServerId() = runTest {
        // Order matters: CREATE first, then UPDATE of the same negative id.
        txCreate(localId = -7, accountId = 1, categoryId = 2)
        txUpdate(entityId = -7, accountId = 1, categoryId = 2)

        coEvery { transactionApi.create(any(), isNull()) } returns
            Response.success(CreateTransactionResponse(id = 99))
        coEvery { transactionApi.update(eq(99L), any(), isNull()) } returns Response.success(Unit)

        val result = newRepository().pushPendingOperations()

        assertTrue("sync should succeed; got ${result.exceptionOrNull()?.message}", result.isSuccess)
        coVerify(exactly = 1) { transactionApi.update(99, any(), isNull()) }
        coVerify(exactly = 0) { transactionApi.update(eq(-7L), any(), isNull()) }
        assertTrue("Queue should drain completely", queue.isEmpty())
    }

    @Test
    fun offlineCreateThenDeleteUsesRemappedServerId() = runTest {
        txCreate(localId = -7, accountId = 1, categoryId = 2)
        txDelete(entityId = -7)

        coEvery { transactionApi.create(any(), isNull()) } returns
            Response.success(CreateTransactionResponse(id = 99))
        coEvery { transactionApi.delete(eq(99L), isNull()) } returns Response.success(Unit)

        val result = newRepository().pushPendingOperations()

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { transactionApi.delete(99, isNull()) }
        coVerify(exactly = 0) { transactionApi.delete(eq(-7L), isNull()) }
        assertTrue(queue.isEmpty())
    }

    @Test
    fun offlineAccountCreateThenUpdateUsesRemappedServerId() = runTest {
        acctCreate(-1L)
        acctUpdate(-1L)
        coEvery { accountApi.create(any(), isNull()) } returns Response.success(IdResponse(10L))
        coEvery { accountApi.update(10L, any(), isNull()) } returns Response.success(Unit)

        assertTrue(newRepository().pushPendingOperations().isSuccess)

        coVerify(exactly = 1) { accountApi.update(10L, any(), isNull()) }
    }

    @Test
    fun offlineAccountCreateThenDeleteUsesRemappedServerId() = runTest {
        acctCreate(-1L)
        acctDelete(-1L)
        coEvery { accountApi.create(any(), isNull()) } returns Response.success(IdResponse(10L))
        coEvery { accountApi.delete(10L, isNull()) } returns Response.success(Unit)

        assertTrue(newRepository().pushPendingOperations().isSuccess)

        coVerify(exactly = 1) { accountApi.delete(10L, isNull()) }
    }

    @Test
    fun offlineCategoryCreateThenUpdateUsesRemappedServerId() = runTest {
        catCreate(-1L)
        catUpdate(-1L)
        coEvery { categoryApi.create(any(), isNull()) } returns Response.success(IdResponse(10L))
        coEvery { categoryApi.update(10L, any(), isNull()) } returns Response.success(Unit)

        assertTrue(newRepository().pushPendingOperations().isSuccess)

        coVerify(exactly = 1) { categoryApi.update(10L, any(), isNull()) }
    }

    @Test
    fun offlineCategoryCreateThenDeleteUsesRemappedServerId() = runTest {
        catCreate(-1L)
        catDelete(-1L)
        coEvery { categoryApi.create(any(), isNull()) } returns Response.success(IdResponse(10L))
        coEvery { categoryApi.delete(10L, isNull()) } returns Response.success(Unit)

        assertTrue(newRepository().pushPendingOperations().isSuccess)

        coVerify(exactly = 1) { categoryApi.delete(10L, isNull()) }
    }

    @Test
    fun offlineScheduledCreateThenUpdateUsesRemappedServerId() = runTest {
        schedCreate(-1L)
        schedUpdate(-1L)
        coEvery { scheduledApi.create(any(), isNull()) } returns Response.success(IdResponse(10L))
        coEvery { scheduledApi.update(10L, any(), isNull()) } returns Response.success(Unit)

        assertTrue(newRepository().pushPendingOperations().isSuccess)

        coVerify(exactly = 1) { scheduledApi.update(10L, any(), isNull()) }
    }

    @Test
    fun offlineScheduledCreateThenDeleteUsesRemappedServerId() = runTest {
        schedCreate(-1L)
        schedDelete(-1L)
        coEvery { scheduledApi.create(any(), isNull()) } returns Response.success(IdResponse(10L))
        coEvery { scheduledApi.delete(10L, isNull()) } returns Response.success(Unit)

        assertTrue(newRepository().pushPendingOperations().isSuccess)

        coVerify(exactly = 1) { scheduledApi.delete(10L, isNull()) }
    }

    // ====================
    // A2: rewrite FK references inside payload_json so dependent CREATE/UPDATE
    // is sent with the positive id.
    // ====================

    @Test
    fun offlineAccountCreateThenTransactionCreateRewritesAccountFk() = runTest {
        // Account is created locally with id -1; transaction also offline with
        // account_id = -1 in its payload.
        acctCreate(localId = -1)
        txCreate(localId = -7, accountId = -1, categoryId = 2)

        coEvery { accountApi.create(any(), isNull()) } returns Response.success(IdResponse(100))
        val capturedTx = slot<TransactionInput>()
        coEvery { transactionApi.create(capture(capturedTx), isNull()) } returns
            Response.success(CreateTransactionResponse(id = 99))

        val result = newRepository().pushPendingOperations()

        assertTrue("sync should succeed; got ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals("transaction CREATE should be sent with remapped account_id",
            100L, capturedTx.captured.accountId)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun offlineCategoryCreateThenTransactionCreateRewritesCategoryFk() = runTest {
        catCreate(localId = -3)
        txCreate(localId = -7, accountId = 1, categoryId = -3)

        coEvery { categoryApi.create(any(), isNull()) } returns Response.success(IdResponse(200))
        val capturedTx = slot<TransactionInput>()
        coEvery { transactionApi.create(capture(capturedTx), isNull()) } returns
            Response.success(CreateTransactionResponse(id = 99))

        val result = newRepository().pushPendingOperations()

        assertTrue(result.isSuccess)
        assertEquals(200L, capturedTx.captured.categoryId)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun offlineAccountCreateThenTransactionUpdateRewritesUpdatePayloadFk() = runTest {
        // Realistic: account created offline (-1), transaction created online
        // and assigned account_id -1 via offline edit. The UPDATE payload
        // still carries the negative FK because the form serialized it before
        // the account was remapped.
        acctCreate(localId = -1)
        txUpdate(entityId = 42, accountId = -1, categoryId = 5)

        coEvery { accountApi.create(any(), isNull()) } returns Response.success(IdResponse(100))
        val capturedUpd = slot<TransactionInput>()
        coEvery { transactionApi.update(eq(42L), capture(capturedUpd), isNull()) } returns Response.success(Unit)

        val result = newRepository().pushPendingOperations()

        assertTrue(result.isSuccess)
        assertEquals(100L, capturedUpd.captured.accountId)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun offlineParentCategoryCreateThenChildRewritesParentId() = runTest {
        catCreate(localId = -1)               // parent
        catCreate(localId = -2, parentId = -1) // child references negative parent

        // categoryApi.create is called twice; capture both inputs.
        val captured = mutableListOf<CategoryInput>()
        coEvery { categoryApi.create(capture(captured), isNull()) } returnsMany listOf(
            Response.success<IdResponse>(IdResponse(10)),
            Response.success<IdResponse>(IdResponse(11))
        )

        val result = newRepository().pushPendingOperations()

        assertTrue("sync should succeed; got ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals(2, captured.size)
        assertNull("parent CREATE has no parent_id", captured[0].parentId)
        assertEquals("child CREATE should be sent with remapped parent_id",
            10L, captured[1].parentId)
        assertTrue(queue.isEmpty())
    }

    // ====================
    // A3: surface failures up through sync() and skip pull when push fails.
    // ====================

    @Test
    fun httpFailedPushRetainsPendingOpAndReportsFailure() = runTest {
        val op = txCreate(localId = -7, accountId = 1, categoryId = 2)
        coEvery { transactionApi.create(any(), isNull()) } returns errorBody<CreateTransactionResponse>(500)

        val result = newRepository().pushPendingOperations()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SyncPushException)
        // Op must NOT have been deleted on failure.
        coVerify(exactly = 0) { pendingOpDao.delete(op) }
        assertEquals(1, queue.size)
    }

    @Test
    fun thrownExceptionDuringPushReportsFailure() = runTest {
        val op = txCreate(localId = -7, accountId = 1, categoryId = 2)
        coEvery { transactionApi.create(any(), isNull()) } throws java.io.IOException("network down")

        val result = newRepository().pushPendingOperations()

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { pendingOpDao.delete(op) }
        assertEquals(1, queue.size)
    }

    @Test
    fun independentOpsContinueAfterOneFailure() = runTest {
        // Two unrelated transaction CREATEs. First fails, second succeeds.
        txCreate(localId = -7, accountId = 1, categoryId = 2)
        txCreate(localId = -8, accountId = 1, categoryId = 2)

        coEvery { transactionApi.create(any(), isNull()) } returnsMany listOf(
            errorBody<CreateTransactionResponse>(500),
            Response.success(CreateTransactionResponse(id = 200))
        )

        val result = newRepository().pushPendingOperations()

        assertTrue(result.isFailure)
        // The successful one drained; the failed one is still queued.
        assertEquals(1, queue.size)
        assertEquals(-7L, queue.first().entityId)
    }

    @Test
    fun syncSkipsPullWhenPushFails() = runTest {
        txCreate(localId = -7, accountId = 1, categoryId = 2)
        coEvery { transactionApi.create(any(), isNull()) } returns errorBody<CreateTransactionResponse>(500)

        val result = newRepository().sync()

        assertTrue(result.isFailure)
        // pull() must not have been called — syncApi.sync would have fetched data.
        coVerify(exactly = 0) { syncApi.sync(any()) }
    }

    @Test
    fun permanentFailureMovesToFailedQueueAndDoesNotBlockPull() = runTest {
        txCreate(localId = -7, accountId = 1, categoryId = 2)
        coEvery { transactionApi.create(any(), isNull()) } returns errorBody(400)
        coEvery { prefs.lastSyncVersionFlow } returns kotlinx.coroutines.flow.flowOf(0L)
        coEvery { syncApi.sync(any()) } returns Response.success(
            com.insituledger.app.data.remote.dto.SyncResponse(
                currentVersion = 1,
                transactions = emptyList(),
                categories = emptyList(),
                accounts = emptyList(),
                scheduledTransactions = emptyList()
            )
        )

        val result = newRepository().sync()

        assertTrue(result.isFailure)
        assertEquals(PendingOperationEntity.STATE_FAILED, queue.single().state)
        coVerify(exactly = 1) { syncApi.sync(0L) }
        coVerify(exactly = 1) { prefs.saveSyncCheckpoint(1L, false) }
    }

    @Test
    fun mixedLostAccessAndTransientFailureStillPurgesRevokedAccountWithoutAdvancingCursor() = runTest {
        txUpdate(entityId = 42L, accountId = 7L, categoryId = 2L)
        txCreate(localId = -7L, accountId = 1L, categoryId = 2L)
        coEvery { transactionApi.update(eq(42L), any(), isNull()) } returns errorBody(403)
        coEvery { transactionApi.create(any(), isNull()) } returns errorBody(503)
        coEvery { prefs.lastSyncVersionFlow } returns kotlinx.coroutines.flow.flowOf(5L)
        coEvery { syncApi.sync(5L) } returns Response.success(
            com.insituledger.app.data.remote.dto.SyncResponse(
                currentVersion = 9,
                transactions = emptyList(),
                categories = emptyList(),
                accounts = emptyList(),
                scheduledTransactions = emptyList(),
                revokedAccountIds = listOf(7L)
            )
        )
        coEvery { transactionDao.selectIdsByAccountId(7L) } returns listOf(42L)

        val result = newRepository().sync()

        assertTrue(result.isFailure)
        coVerify(exactly = 1) { transactionDao.deleteByAccountId(7L) }
        coVerify(exactly = 0) { prefs.saveSyncCheckpoint(any(), any()) }
    }

    @Test
    fun suspectedLostAccessDoesNotBlockConfirmedRevocationPull() = runTest {
        txUpdate(entityId = 42L, accountId = 7L, categoryId = 2L)
        coEvery { transactionApi.update(eq(42L), any(), isNull()) } returns errorBody(403)
        coEvery { prefs.lastSyncVersionFlow } returns kotlinx.coroutines.flow.flowOf(5L)
        coEvery { syncApi.sync(5L) } returns Response.success(
            com.insituledger.app.data.remote.dto.SyncResponse(
                currentVersion = 9,
                transactions = emptyList(),
                categories = emptyList(),
                accounts = emptyList(),
                scheduledTransactions = emptyList(),
                revokedAccountIds = listOf(7L)
            )
        )
        coEvery { transactionDao.selectIdsByAccountId(7L) } returns listOf(42L)

        newRepository().sync()

        assertTrue("confirmed tombstone purge should remove the failed revoked op", queue.isEmpty())
        coVerify(exactly = 1) { syncApi.sync(5L) }
        coVerify(exactly = 1) { prefs.saveSyncCheckpoint(9L, false) }
    }

    @Test
    fun syncRunsPullWhenPushSucceeds() = runTest {
        // Empty queue → push trivially succeeds; pull then runs.
        coEvery { prefs.lastSyncVersionFlow } returns kotlinx.coroutines.flow.flowOf(0L)
        coEvery { syncApi.sync(any()) } returns Response.success(
            com.insituledger.app.data.remote.dto.SyncResponse(
                currentVersion = 1,
                transactions = emptyList(),
                categories = emptyList(),
                accounts = emptyList(),
                scheduledTransactions = emptyList()
            )
        )

        val result = newRepository().sync()

        assertTrue("sync should succeed; got ${result.exceptionOrNull()?.message}", result.isSuccess)
        coVerify(exactly = 1) { syncApi.sync(any()) }
    }

    @Test
    fun deferredUpdateBlockedByFailedCreateIsReportedAsFailure() = runTest {
        // CREATE fails; the dependent UPDATE can never get a positive id.
        txCreate(localId = -7, accountId = 1, categoryId = 2)
        txUpdate(entityId = -7, accountId = 1, categoryId = 2)

        coEvery { transactionApi.create(any(), isNull()) } returns errorBody(400)

        val result = newRepository().pushPendingOperations()

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull() as SyncPushException
        // Both the CREATE failure and the deferred UPDATE should be counted.
        assertEquals(2, ex.failedCount)
        // Both ops remain queued (would otherwise have been silently dropped).
        assertEquals(2, queue.size)
        // The UPDATE was never sent because the local row never got a positive id.
        coVerify(exactly = 0) { transactionApi.update(any(), any()) }
    }

    @Test
    fun negativeIdUpdateIsNotSilentlyDeletedFromQueue() = runTest {
        // Regression guard for the original bug: a UPDATE with id < 0 that
        // wasn't preceded by a CREATE in this batch (e.g. CREATE in an earlier
        // crashed run that never got past enqueue) must NOT be removed without
        // an API call.
        val op = txUpdate(entityId = -7, accountId = 1, categoryId = 2)

        val result = newRepository().pushPendingOperations()

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { transactionApi.update(any(), any()) }
        coVerify(exactly = 0) { pendingOpDao.delete(op) }
        assertEquals(1, queue.size)
    }

    // ====================
    // Tier B B1: pull() must transactionally purge revoked accounts plus
    // their transactions/schedules/pending ops.
    // ====================

    @Test
    fun pullPurgesRevokedAccountAndItsRows() = runTest {
        coEvery { prefs.lastSyncVersionFlow } returns kotlinx.coroutines.flow.flowOf(0L)
        coEvery { syncApi.sync(any()) } returns Response.success(
            com.insituledger.app.data.remote.dto.SyncResponse(
                currentVersion = 7,
                transactions = emptyList(),
                categories = emptyList(),
                accounts = emptyList(),
                scheduledTransactions = emptyList(),
                revokedAccountIds = listOf(42L)
            )
        )
        coEvery { transactionDao.selectIdsByAccountId(42L) } returns listOf(100L, 101L)
        coEvery { scheduledDao.selectIdsByAccountId(42L) } returns listOf(500L)

        val result = newRepository().pull()

        assertTrue("pull should succeed; got ${result.exceptionOrNull()?.message}", result.isSuccess)
        coVerify(exactly = 1) { pendingOpDao.deleteByEntity("transaction", 100L) }
        coVerify(exactly = 1) { pendingOpDao.deleteByEntity("transaction", 101L) }
        coVerify(exactly = 1) { pendingOpDao.deleteByEntity("scheduled", 500L) }
        coVerify(exactly = 1) { transactionDao.deleteByAccountId(42L) }
        coVerify(exactly = 1) { scheduledDao.deleteByAccountId(42L) }
        coVerify(exactly = 1) { pendingOpDao.deleteByEntity("account", 42L) }
        coVerify(exactly = 1) { accountDao.deleteById(42L) }
    }

    @Test
    fun pullWithoutRevokedAccountIdsTouchesNoDeletes() = runTest {
        coEvery { prefs.lastSyncVersionFlow } returns kotlinx.coroutines.flow.flowOf(0L)
        coEvery { syncApi.sync(any()) } returns Response.success(
            com.insituledger.app.data.remote.dto.SyncResponse(
                currentVersion = 1,
                transactions = emptyList(),
                categories = emptyList(),
                accounts = emptyList(),
                scheduledTransactions = emptyList(),
                revokedAccountIds = null  // pre-1.19 backend
            )
        )

        val result = newRepository().pull()

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { transactionDao.deleteByAccountId(any()) }
        coVerify(exactly = 0) { accountDao.deleteById(any()) }
        coVerify(exactly = 0) { pendingOpDao.deleteByEntity(any(), any()) }
    }

    // ====================
    // v1.20 F3: account tombstones in the synced payload (deleted_at IS NOT
    // NULL) must trigger the same purge as revoked_account_ids.
    // ====================

    @Test
    fun pullPurgesDependentsForSoftDeletedAccountTombstone() = runTest {
        coEvery { prefs.lastSyncVersionFlow } returns kotlinx.coroutines.flow.flowOf(0L)
        // The server returns the account row with deleted_at set (owner soft-
        // deleted it). No revoked_account_ids — this is the owner's own data,
        // not a share revocation. The local purge must still run.
        val deletedAccount = com.insituledger.app.data.remote.dto.AccountDto(
            id = 77L, userId = 1L, name = "Wallet", currency = "EUR", balance = 0.0,
            createdAt = "2025-01-01T00:00:00Z", updatedAt = "2026-06-15T00:00:00Z",
            deletedAt = "2026-06-15T00:00:00Z", syncVersion = 10L,
            ownerUserId = 1L, ownerName = "Admin", isShared = false
        )
        coEvery { syncApi.sync(any()) } returns Response.success(
            com.insituledger.app.data.remote.dto.SyncResponse(
                currentVersion = 10,
                transactions = emptyList(),
                categories = emptyList(),
                accounts = listOf(deletedAccount),
                scheduledTransactions = emptyList(),
                revokedAccountIds = null
            )
        )
        coEvery { transactionDao.selectIdsByAccountId(77L) } returns listOf(200L)
        coEvery { scheduledDao.selectIdsByAccountId(77L) } returns listOf(600L)

        val result = newRepository().pull()

        assertTrue("pull should succeed; got ${result.exceptionOrNull()?.message}", result.isSuccess)
        coVerify(exactly = 1) { pendingOpDao.deleteByEntity("transaction", 200L) }
        coVerify(exactly = 1) { pendingOpDao.deleteByEntity("scheduled", 600L) }
        coVerify(exactly = 1) { transactionDao.deleteByAccountId(77L) }
        coVerify(exactly = 1) { scheduledDao.deleteByAccountId(77L) }
        coVerify(exactly = 1) { accountDao.deleteById(77L) }
    }

    @Test
    fun pullCollectsDependentIdsBeforeGenericTombstonePurge() = runTest {
        coEvery { prefs.lastSyncVersionFlow } returns kotlinx.coroutines.flow.flowOf(0L)
        val deletedAccount = com.insituledger.app.data.remote.dto.AccountDto(
            id = 77L, userId = 1L, name = "Wallet", currency = "EUR", balance = 0.0,
            createdAt = "", updatedAt = "", deletedAt = "deleted", syncVersion = 10L,
            ownerUserId = 1L, ownerName = "Admin", isShared = false
        )
        val deletedTransaction = com.insituledger.app.data.remote.dto.TransactionDto(
            id = 200L, accountId = 77L, categoryId = 2L, userId = 1L,
            type = "expense", amount = 1.0, currency = "EUR", description = null,
            note = null, date = "2026-01-01", createdAt = "", updatedAt = "",
            deletedAt = "deleted", syncVersion = 10L
        )
        var rowsStillPresent = true
        coEvery { transactionDao.selectIdsByAccountId(77L) } answers {
            if (rowsStillPresent) listOf(200L) else emptyList()
        }
        coEvery { transactionDao.purgeDeleted() } answers { rowsStillPresent = false }
        coEvery { syncApi.sync(any()) } returns Response.success(
            com.insituledger.app.data.remote.dto.SyncResponse(
                currentVersion = 10,
                transactions = listOf(deletedTransaction),
                categories = emptyList(),
                accounts = listOf(deletedAccount),
                scheduledTransactions = emptyList()
            )
        )

        val result = newRepository().pull()

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { pendingOpDao.deleteByEntity("transaction", 200L) }
    }

    // ====================
    // 403/404 account-scoped mutations enter the visible failed queue until a
    // pull confirms an access-loss tombstone and purges them.
    // ====================

    @Test
    fun pushTransactionUpdateReturning403MovesToFailedQueue() = runTest {
        // Transaction was created online (entityId = 42, serverId = 42), edited
        // offline, then the account was revoked. Server returns 403.
        val op = txUpdate(entityId = 42L, accountId = 1L, categoryId = 2L)
        coEvery { transactionApi.update(eq(42L), any(), isNull()) } returns errorBody<Unit>(403)

        val result = newRepository().pushPendingOperations()

        assertTrue(result.isFailure)
        assertEquals(PendingOperationEntity.STATE_FAILED, queue.single().state)
        coVerify(exactly = 0) { pendingOpDao.delete(op) }
    }

    @Test
    fun push404OnUpdateIsAlsoLostAccess() = runTest {
        // UPDATE 404 = entity (or parent account) is gone — same outcome as 403.
        val op = txUpdate(entityId = 99L, accountId = 1L, categoryId = 2L)
        coEvery { transactionApi.update(eq(99L), any(), isNull()) } returns errorBody<Unit>(404)

        val result = newRepository().pushPendingOperations()

        assertTrue(result.isFailure)
        assertEquals(PendingOperationEntity.STATE_FAILED, queue.single().state)
        coVerify(exactly = 0) { pendingOpDao.delete(op) }
    }

    @Test
    fun push403OnCreateIsPermanentAndNeverSilentlyDropped() = runTest {
        val op = acctCreate(localId = -1L)
        coEvery { accountApi.create(any(), isNull()) } returns errorBody<IdResponse>(403)

        val result = newRepository().pushPendingOperations()

        val ex = result.exceptionOrNull() as SyncPushException
        assertEquals(1, ex.permanentCount)
        assertEquals(PendingOperationEntity.STATE_FAILED, queue.single().state)
        coVerify(exactly = 0) { pendingOpDao.delete(op) }
    }

    // ====================
    // v1.20 F4: 5xx is transient (retry); other 4xx is permanent (no retry).
    // ====================

    @Test
    fun push500MarksTransientAndRetainsOp() = runTest {
        val op = txCreate(localId = -7L, accountId = 1L, categoryId = 2L)
        coEvery { transactionApi.create(any(), isNull()) } returns errorBody<CreateTransactionResponse>(503)

        val result = newRepository().pushPendingOperations()

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull() as SyncPushException
        assertEquals(1, ex.transientCount)
        assertEquals(0, ex.permanentCount)
        assertTrue("canRetry should be true for transient failures", ex.canRetry)
        assertEquals(1, queue.size)
        coVerify(exactly = 0) { pendingOpDao.delete(op) }
    }

    @Test
    fun push400MarksPermanentAndBlocksRetry() = runTest {
        val op = txCreate(localId = -7L, accountId = 1L, categoryId = 2L)
        coEvery { transactionApi.create(any(), isNull()) } returns errorBody<CreateTransactionResponse>(400)

        val result = newRepository().pushPendingOperations()

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull() as SyncPushException
        assertEquals(0, ex.transientCount)
        assertEquals(1, ex.permanentCount)
        assertFalse("canRetry should be false when only permanent failures", ex.canRetry)
        assertEquals(1, queue.size)
        assertEquals(PendingOperationEntity.STATE_FAILED, queue.single().state)
        coVerify(exactly = 0) { pendingOpDao.delete(op) }
    }

    @Test
    fun pushNetworkExceptionIsTransient() = runTest {
        txCreate(localId = -7L, accountId = 1L, categoryId = 2L)
        coEvery { transactionApi.create(any(), isNull()) } throws java.io.IOException("network down")

        val result = newRepository().pushPendingOperations()

        val ex = result.exceptionOrNull() as SyncPushException
        assertEquals(1, ex.transientCount)
        assertTrue(ex.canRetry)
    }
    @Test
    fun fullBackfillCachesCreatorNamesAndCommitsCheckpoint() = runTest {
        coEvery { prefs.fullSnapshotDoneFlow } returns kotlinx.coroutines.flow.flowOf(false)
        coEvery { prefs.lastSyncVersionFlow } returns kotlinx.coroutines.flow.flowOf(42L)
        val transaction = com.insituledger.app.data.remote.dto.TransactionDto(
            id = 1, accountId = 7, categoryId = 2, userId = 1, type = "expense",
            amount = 10.0, currency = "EUR", description = "Food", date = "2026-10-01",
            createdAt = "", updatedAt = "", deletedAt = null, syncVersion = 2,
            createdByUserId = 3, createdByName = "Guest"
        )
        val scheduled = com.insituledger.app.data.remote.dto.ScheduledTransactionDto(
            id = 2, accountId = 7, categoryId = 2, userId = 1, type = "expense",
            amount = 10.0, currency = "EUR", description = "Rent", rrule = "FREQ=MONTHLY",
            nextOccurrence = "2030-01-01", active = true,
            createdAt = "", updatedAt = "", deletedAt = null, syncVersion = 3,
            createdByUserId = 3, createdByName = "Guest"
        )
        coEvery { syncApi.sync(0L) } returns Response.success(
            com.insituledger.app.data.remote.dto.SyncResponse(
                currentVersion = 42, transactions = listOf(transaction), categories = emptyList(),
                accounts = emptyList(), scheduledTransactions = listOf(scheduled)
            )
        )
        assertTrue(newRepository().pull().isSuccess)
        coVerify { transactionDao.upsertAll(match { it.single().createdByName == "Guest" && it.single().createdByUserId == 3L }) }
        coVerify { scheduledDao.upsertAll(match { it.single().createdByName == "Guest" }) }
        coVerify { prefs.saveSyncCheckpoint(42L, true) }
    }

    @Test
    fun failedBackfillDoesNotCommitAndNextPullRetriesFullSnapshot() = runTest {
        coEvery { prefs.fullSnapshotDoneFlow } returns kotlinx.coroutines.flow.flowOf(false)
        coEvery { prefs.lastSyncVersionFlow } returns kotlinx.coroutines.flow.flowOf(42L)
        coEvery { syncApi.sync(0L) } returns errorBody(503)
        assertTrue(newRepository().pull().isFailure)
        assertTrue(newRepository().pull().isFailure)
        coVerify(exactly = 2) { syncApi.sync(0L) }
        coVerify(exactly = 0) { prefs.saveSyncCheckpoint(any(), any()) }
    }

    // ====================
    // 429: rate limited is transient and ends the drain.
    // ====================

    @Test
    fun push429IsTransientAndStopsDrain() = runTest {
        txUpdate(entityId = 42L, accountId = 1L, categoryId = 2L)
        txUpdate(entityId = 43L, accountId = 1L, categoryId = 2L)
        txCreate(localId = -7L, accountId = 1L, categoryId = 2L)
        coEvery { transactionApi.update(eq(42L), any(), isNull()) } returns errorBody(429)

        val result = newRepository().pushPendingOperations()

        val ex = result.exceptionOrNull() as SyncPushException
        assertEquals(1, ex.transientCount)
        assertEquals(0, ex.permanentCount)
        assertTrue("SyncWorker retries with backoff", ex.canRetry)
        // Nothing after the 429 was sent; everything is still pending.
        coVerify(exactly = 0) { transactionApi.update(eq(43L), any(), isNull()) }
        coVerify(exactly = 0) { transactionApi.create(any(), any()) }
        assertEquals(3, queue.size)
        assertTrue(queue.all { it.state == PendingOperationEntity.STATE_PENDING })
    }

    @Test
    fun cancellationDuringSinglePushPropagatesAndKeepsOpPending() = runTest {
        txUpdate(entityId = 42L, accountId = 1L, categoryId = 2L)
        coEvery { transactionApi.update(eq(42L), any(), isNull()) } throws kotlinx.coroutines.CancellationException("drain cancelled")

        try {
            newRepository().pushPendingOperations()
            org.junit.Assert.fail("cancellation must propagate, not be reported as a push failure")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // expected
        }
        assertEquals(1, queue.size)
        assertEquals(PendingOperationEntity.STATE_PENDING, queue.single().state)
        assertNull(queue.single().lastError)
    }

    // ===== Duplicate server rows: 401 → logout → login → Settings Retry =====

    private fun localTx(id: Long, amount: Double = 4.15) = com.insituledger.app.data.local.db.entity.TransactionEntity(
        id = id, accountId = 1L, categoryId = 2L, userId = 0L, type = "expense", amount = amount,
        currency = "EUR", date = "2026-06-12T08:41:00+03:00", isLocalOnly = true
    )

    private fun creates(entityType: String = "transaction") = queue.filter { it.entityType == entityType && it.operation == "CREATE" }

    @Test
    fun duplicateScenario_401ThenReloginThenRetry_createsExactlyOneServerRow() = runTest {
        // An unsynced entry on the phone.
        txCreate(localId = -7L, accountId = 1L, categoryId = 2L)
        coEvery { transactionDao.getAllSync() } returns listOf(localTx(-7L))
        val sentClientIds = mutableListOf<String?>()
        coEvery { transactionApi.create(any(), isNull()) } answers {
            sentClientIds += firstArg<TransactionInput>().clientId
            errorBody<CreateTransactionResponse>(401)
        }

        // 1. The session is rejected: the CREATE is kept pending, not failed.
        val ex = newRepository().pushPendingOperations().exceptionOrNull() as SyncPushException
        assertEquals(1, ex.transientCount)
        assertEquals(0, ex.permanentCount)
        assertEquals(PendingOperationEntity.STATE_PENDING, creates().single().state)

        // 2. Log in again: the row still has exactly one CREATE, same client_id.
        newRepository().enqueueLocalDataForSync()
        assertEquals(listOf("tx--7"), creates().map { it.clientId })

        // 3. It syncs once.
        coEvery { transactionApi.create(any(), isNull()) } answers {
            sentClientIds += firstArg<TransactionInput>().clientId
            Response.success(CreateTransactionResponse(id = 99))
        }
        assertTrue(newRepository().pushPendingOperations().isSuccess)

        // 4. Settings → Retry has nothing left that could create a second row.
        pendingOpDao.retryFailed()
        assertTrue(newRepository().pushPendingOperations().isSuccess)

        assertEquals("one 401 attempt and one successful attempt, same client_id", listOf("tx--7", "tx--7"), sentClientIds)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun leftoverDuplicateCreatesFromOlderBuilds_onlyOneReachesTheServer() = runTest {
        // What an older build left behind: the 401-failed CREATE plus the one
        // re-queued at login with a fresh client_id.
        enqueue(txCreate(-7L, 1L, 2L).also { queue.remove(it) }
            .copy(state = PendingOperationEntity.STATE_FAILED, lastError = "HTTP 401", clientId = "A"))
        enqueue(txCreate(-7L, 1L, 2L).also { queue.remove(it) }.copy(id = 0, clientId = "B"))
        coEvery { transactionApi.create(any(), isNull()) } returns Response.success(CreateTransactionResponse(id = 99))

        assertTrue(newRepository().pushPendingOperations().isSuccess)
        pendingOpDao.retryFailed()
        assertTrue(newRepository().pushPendingOperations().isSuccess)

        coVerify(exactly = 1) { transactionApi.create(any(), isNull()) }
        coVerify(exactly = 1) { transactionApi.create(match { it.clientId == "B" }, isNull()) }
        assertTrue("the failed duplicate was dropped, not left for Retry", queue.isEmpty())
    }

    @Test
    fun twoPendingCreatesForOneRow_secondIsDroppedWhenTheFirstSucceeds() = runTest {
        txCreate(-7L, 1L, 2L)
        enqueue(txCreate(-7L, 1L, 2L).also { queue.remove(it) }.copy(id = 0, clientId = "second"))
        coEvery { transactionApi.create(any(), isNull()) } returns Response.success(CreateTransactionResponse(id = 99))

        assertTrue(newRepository().pushPendingOperations().isSuccess)

        coVerify(exactly = 1) { transactionApi.create(any(), isNull()) }
        coVerify(exactly = 1) { transactionApi.create(match { it.clientId == "tx--7" }, isNull()) }
        assertTrue(queue.isEmpty())
    }

    @Test
    fun duplicateAccountCreates_onlyOneReachesTheServer() = runTest {
        acctCreate(-1L)
        enqueue(queue.last().copy(id = 0, clientId = "second"))
        coEvery { accountApi.create(any(), isNull()) } returns Response.success(IdResponse(10L))

        assertTrue(newRepository().pushPendingOperations().isSuccess)

        coVerify(exactly = 1) { accountApi.create(any(), isNull()) }
        assertTrue(creates("account").isEmpty())
    }

    @Test
    fun enqueueRequeuesAFailedCreateWithCurrentValuesAndItsOriginalClientId() = runTest {
        val failed = txCreate(-7L, 1L, 2L).also { queue.remove(it) }
            .copy(state = PendingOperationEntity.STATE_FAILED, lastError = "HTTP 401")
        enqueue(failed)
        coEvery { transactionDao.getAllSync() } returns listOf(localTx(-7L, amount = 12.0))

        newRepository().enqueueLocalDataForSync()

        val op = creates().single()
        assertEquals(failed.id, op.id)
        assertEquals("tx--7", op.clientId)
        assertEquals(PendingOperationEntity.STATE_PENDING, op.state)
        assertNull(op.lastError)
        assertEquals(12.0, gson.fromJson(op.payloadJson, TransactionInput::class.java).amount, 0.0)
    }

    @Test
    fun enqueueKeepsThePendingCreateAndDropsFailedDuplicates() = runTest {
        enqueue(txCreate(-7L, 1L, 2L).also { queue.remove(it) }
            .copy(state = PendingOperationEntity.STATE_FAILED, clientId = "A"))
        val pending = enqueue(txCreate(-7L, 1L, 2L).also { queue.remove(it) }.copy(id = 0, clientId = "B"))
        coEvery { transactionDao.getAllSync() } returns listOf(localTx(-7L, amount = 12.0))

        newRepository().enqueueLocalDataForSync()

        assertEquals(listOf(pending), creates())
    }

    @Test
    fun enqueueAddsOneCreateForARowWithoutOne_andNeverASecond() = runTest {
        coEvery { transactionDao.getAllSync() } returns listOf(localTx(-7L))

        newRepository().enqueueLocalDataForSync()
        newRepository().enqueueLocalDataForSync()

        val op = creates().single()
        assertEquals(-7L, op.entityId)
        assertNotNull(op.clientId)
    }

    @Test
    fun push401StopsTheDrainAndKeepsEveryOpPending() = runTest {
        txUpdate(entityId = 42L, accountId = 1L, categoryId = 2L)
        txUpdate(entityId = 43L, accountId = 1L, categoryId = 2L)
        txCreate(localId = -7L, accountId = 1L, categoryId = 2L)
        coEvery { transactionApi.update(eq(42L), any(), isNull()) } returns errorBody(401)

        val ex = newRepository().pushPendingOperations().exceptionOrNull() as SyncPushException

        assertEquals(1, ex.transientCount)
        assertEquals(0, ex.permanentCount)
        coVerify(exactly = 0) { transactionApi.update(eq(43L), any(), isNull()) }
        coVerify(exactly = 0) { transactionApi.create(any(), any()) }
        assertEquals(3, queue.size)
        assertTrue(queue.all { it.state == PendingOperationEntity.STATE_PENDING && it.lastError == null })
    }

    @Test
    fun batch401StopsTheDrainWithoutFallingBackToSingles() = runTest {
        txPatch(41L)
        txPatch(42L)
        txDelete(43L)
        coEvery { transactionApi.batchUpdate(any()) } returns errorBody(401)

        val ex = newRepository().pushPendingOperations().exceptionOrNull() as SyncPushException

        assertEquals(0, ex.permanentCount)
        coVerify(exactly = 1) { transactionApi.batchUpdate(any()) }
        coVerify(exactly = 0) { transactionApi.delete(any(), any()) }
        coVerify(exactly = 0) { transactionApi.batchDelete(any()) }
        assertTrue(queue.all { it.state == PendingOperationEntity.STATE_PENDING })
    }

    @Test
    fun push429OnCreatePatchAndDeleteIsTransientNotPermanent() = runTest {
        for (setup in listOf<() -> Unit>(
            { txCreate(-7L, 1L, 2L); coEvery { transactionApi.create(any(), isNull()) } returns errorBody(429) },
            { txPatch(42L); coEvery { transactionApi.batchUpdate(any()) } returns errorBody(429) },
            { txDelete(42L); coEvery { transactionApi.delete(eq(42L), isNull()) } returns errorBody(429) }
        )) {
            queue.clear()
            setup()

            val ex = newRepository().pushPendingOperations().exceptionOrNull() as SyncPushException

            assertEquals(1, ex.transientCount)
            assertEquals(0, ex.permanentCount)
            assertEquals(PendingOperationEntity.STATE_PENDING, queue.single().state)
        }
    }

    // ====================
    // Per-entity FIFO.
    // ====================

    @Test
    fun transientFailureSkipsLaterOpsSameEntity() = runTest {
        txUpdate(entityId = 42L, accountId = 1L, categoryId = 2L)
        val patch = txPatch(entityId = 42L)
        txUpdate(entityId = 43L, accountId = 1L, categoryId = 2L)
        coEvery { transactionApi.update(eq(42L), any(), isNull()) } returns errorBody(503)
        coEvery { transactionApi.update(eq(43L), any(), isNull()) } returns Response.success(Unit)

        val result = newRepository().pushPendingOperations()

        val ex = result.exceptionOrNull() as SyncPushException
        assertEquals(1, ex.transientCount)
        assertEquals(0, ex.permanentCount)
        coVerify(exactly = 0) { transactionApi.batchUpdate(any()) }
        // The unrelated entity still went through.
        coVerify(exactly = 1) { transactionApi.update(eq(43L), any(), isNull()) }
        assertEquals(listOf(1L, patch.id), queue.map { it.id })
        assertTrue(queue.all { it.state == PendingOperationEntity.STATE_PENDING })
    }

    @Test
    fun skippedOpPushedAfterEarlierNextDrain() = runTest {
        txUpdate(entityId = 42L, accountId = 1L, categoryId = 2L)
        txPatch(entityId = 42L, description = "Stipendio")
        val calls = mutableListOf<String>()
        var updateFails = true
        coEvery { transactionApi.update(eq(42L), any(), isNull()) } answers {
            calls += "UPDATE"
            if (updateFails) errorBody(503) else Response.success(Unit)
        }
        coEvery { transactionApi.batchUpdate(any()) } answers { calls += "PATCH"; ok() }
        val repository = newRepository()

        assertTrue(repository.pushPendingOperations().isFailure)
        updateFails = false
        assertTrue(repository.pushPendingOperations().isSuccess)

        assertEquals(listOf("UPDATE", "UPDATE", "PATCH"), calls)
        assertTrue(queue.isEmpty())
    }

    // Was permanentFailureBlocksLaterSameEntityOps: the newer op used to be
    // marked failed ("blocked by earlier failed operation") without being
    // sent. Now it is sent, and its success rewrites the failed op so a later
    // retry can't undo it.
    @Test
    fun permanentFailureDoesNotBlockLaterSameEntityOps() = runTest {
        val update = txUpdate(entityId = 42L, accountId = 1L, categoryId = 2L)
        txPatch(entityId = 42L, description = "Stipendio")
        txUpdate(entityId = 43L, accountId = 1L, categoryId = 2L)
        coEvery { transactionApi.update(eq(42L), any(), isNull()) } returns errorBody(400)
        coEvery { transactionApi.update(eq(43L), any(), isNull()) } returns Response.success(Unit)
        coEvery { transactionApi.batchUpdate(any()) } returns ok()

        val ex = newRepository().pushPendingOperations().exceptionOrNull() as SyncPushException

        assertEquals(1, ex.permanentCount)
        assertEquals(0, ex.transientCount)
        coVerify(exactly = 1) { transactionApi.batchUpdate(match { it.ids == listOf(42L) }) }
        coVerify(exactly = 1) { transactionApi.update(eq(43L), any(), isNull()) }
        val failed = queue.single()
        assertEquals(update.id, failed.id)
        assertEquals(PendingOperationEntity.STATE_FAILED, failed.state)
        assertEquals("the newer PATCH's name wins on retry", "Stipendio", txInput(failed).description)
    }

    // Was lostAccessBlocksLaterSameEntityOps: same change as above.
    @Test
    fun lostAccessDoesNotBlockLaterSameEntityOps() = runTest {
        txUpdate(entityId = 42L, accountId = 1L, categoryId = 2L)
        val patch = txPatch(entityId = 42L)
        coEvery { transactionApi.update(eq(42L), any(), isNull()) } returns errorBody(403)
        coEvery { transactionApi.batchUpdate(any()) } returns errorBody(403)

        val ex = newRepository().pushPendingOperations().exceptionOrNull() as SyncPushException

        coVerify(exactly = 1) { transactionApi.batchUpdate(match { it.ids == listOf(42L) }) }
        assertEquals(2, ex.lostAccessCount)
        val failedPatch = queue.single { it.id == patch.id }
        assertEquals(PendingOperationEntity.STATE_FAILED, failedPatch.state)
        assertTrue(failedPatch.lastError!!.endsWith("possible lost access"))
    }

    // Was existingFailedOpBlocksNewerOps: an op left failed by an earlier
    // drain no longer holds the entity's newer ops back.
    @Test
    fun existingFailedOpDoesNotBlockNewerOps() = runTest {
        // Failed in an earlier drain; the user hasn't retried or discarded it.
        val failed = enqueue(PendingOperationEntity(
            entityType = "transaction", operation = "UPDATE", entityId = 42L, serverId = 42L,
            payloadJson = gson.toJson(TransactionInput(1L, 2L, "expense", 9.99, "EUR", "x", null, "2026-06-12")),
            state = PendingOperationEntity.STATE_FAILED, lastError = "HTTP 400"
        ))
        txPatch(entityId = 42L)
        txPatch(entityId = 43L)
        coEvery { transactionApi.batchUpdate(any()) } returns ok()

        assertTrue(newRepository().pushPendingOperations().isSuccess)

        // Identical payloads: one request for both.
        coVerify(exactly = 1) { transactionApi.batchUpdate(any()) }
        coVerify(exactly = 1) { transactionApi.batchUpdate(match { it.ids == listOf(42L, 43L) }) }
        val left = queue.single()
        assertEquals(failed.id, left.id)
        assertEquals(PendingOperationEntity.STATE_FAILED, left.state)
        assertEquals("Stipendio", txInput(left).description)
        assertEquals("other fields untouched", 9.99, txInput(left).amount, 0.0)
    }

    // Now asserts insertion (id) order: created_at is reversed on purpose and
    // must not matter. Both ops fail on the first drain (nothing blocks the
    // second), and Retry replays them in the order they were queued.
    @Test
    fun retryFailedPushesInOriginalOrder() = runTest {
        val update = enqueue(PendingOperationEntity(
            entityType = "transaction", operation = "UPDATE", entityId = 42L, serverId = 42L,
            payloadJson = gson.toJson(TransactionInput(1L, 2L, "income", 1500.0, "EUR", "Paga", null, "2026-06-12")),
            createdAt = 2_000L
        ))
        val patch = txPatch(entityId = 42L, description = null, categoryId = 11L, createdAt = 1_000L)
        val calls = mutableListOf<String>()
        var fail = true
        coEvery { transactionApi.update(eq(42L), any(), isNull()) } answers {
            calls += "UPDATE"
            if (fail) errorBody(400) else Response.success(Unit)
        }
        coEvery { transactionApi.batchUpdate(any()) } answers {
            calls += "PATCH"
            if (fail) errorBody(400) else ok()
        }
        val repository = newRepository()

        val ex = repository.pushPendingOperations().exceptionOrNull() as SyncPushException
        assertEquals(2, ex.permanentCount)
        assertEquals(listOf("UPDATE", "PATCH"), calls)
        assertTrue(queue.all { it.state == PendingOperationEntity.STATE_FAILED })

        pendingOpDao.retryFailed()
        fail = false
        assertTrue(repository.pushPendingOperations().isSuccess)

        assertEquals(listOf("UPDATE", "PATCH", "UPDATE", "PATCH"), calls)
        assertTrue(queue.isEmpty())
        assertTrue(update.id < patch.id)
    }

    @Test
    fun sameTimestampPushesInIdOrder() = runTest {
        val first = txPatch(entityId = 42L, description = "First", createdAt = 5_000L)
        val second = txPatch(entityId = 42L, description = "Second", createdAt = 5_000L)
        // Simulate a table scan returning them in the other order.
        queue.reverse()
        val sent = mutableListOf<String?>()
        coEvery { transactionApi.batchUpdate(any()) } answers { sent += firstArg<BatchUpdateRequest>().description; ok() }

        assertTrue(newRepository().pushPendingOperations().isSuccess)

        assertTrue(first.id < second.id)
        assertEquals(listOf("First", "Second"), sent)
    }

    // ====================
    // PATCH ops (bulk edit).
    // ====================

    @Test
    fun patchTransactionSendsServerIdAndOnlyPatchedFields() = runTest {
        txPatch(entityId = 42L, description = "Stipendio", categoryId = null)
        val request = slot<BatchUpdateRequest>()
        coEvery { transactionApi.batchUpdate(capture(request)) } returns ok()

        assertTrue(newRepository().pushPendingOperations().isSuccess)

        assertEquals(BatchUpdateRequest(ids = listOf(42L), description = "Stipendio"), request.captured)
        val json = com.google.gson.JsonParser.parseString(gson.toJson(request.captured)).asJsonObject
        assertEquals("only the patched field goes over the wire", setOf("ids", "description"), json.keySet())
        // Never a full-row PUT built from the local copy.
        coVerify(exactly = 0) { transactionApi.update(any(), any(), any()) }
        assertTrue(queue.isEmpty())
    }

    @Test
    fun patchCategoryOnlySendsCategoryId() = runTest {
        txPatch(entityId = 42L, description = null, categoryId = 11L)
        val request = slot<BatchUpdateRequest>()
        coEvery { transactionApi.batchUpdate(capture(request)) } returns ok()

        assertTrue(newRepository().pushPendingOperations().isSuccess)

        val json = com.google.gson.JsonParser.parseString(gson.toJson(request.captured)).asJsonObject
        assertEquals(setOf("ids", "category_id"), json.keySet())
        assertEquals(11L, json.get("category_id").asLong)
    }

    @Test
    fun patchScheduledUsesScheduledBatchUpdate() = runTest {
        schedPatch(entityId = 70L, description = "Stipendio", categoryId = 11L)
        val request = slot<ScheduledBatchUpdateRequest>()
        coEvery { scheduledApi.batchUpdate(capture(request)) } returns ok()

        assertTrue(newRepository().pushPendingOperations().isSuccess)

        assertEquals(ScheduledBatchUpdateRequest(ids = listOf(70L), description = "Stipendio", categoryId = 11L), request.captured)
        // A full-row PUT would rewrite next_occurrence from the local copy.
        coVerify(exactly = 0) { scheduledApi.update(any(), any(), any()) }
        coVerify(exactly = 0) { transactionApi.batchUpdate(any()) }
        assertTrue(queue.isEmpty())
    }

    @Test
    fun negativeIdPatchDefersUntilCreateRemaps() = runTest {
        txCreate(localId = -7L, accountId = 1L, categoryId = 2L)
        txPatch(entityId = -7L, description = "Stipendio")
        coEvery { transactionApi.create(any(), isNull()) } returns Response.success(CreateTransactionResponse(id = 99))
        val request = slot<BatchUpdateRequest>()
        coEvery { transactionApi.batchUpdate(capture(request)) } returns ok()

        val result = newRepository().pushPendingOperations()

        assertTrue("got ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals(listOf(99L), request.captured.ids)
        coVerify(exactly = 1) { transactionApi.batchUpdate(any()) }
        assertTrue(queue.isEmpty())
    }

    @Test
    fun negativeIdPatchWaitsWhileItsCreateFailsTransiently() = runTest {
        txCreate(localId = -7L, accountId = 1L, categoryId = 2L)
        txPatch(entityId = -7L)
        coEvery { transactionApi.create(any(), isNull()) } returns errorBody(503)

        val ex = newRepository().pushPendingOperations().exceptionOrNull() as SyncPushException

        assertEquals(1, ex.transientCount)
        coVerify(exactly = 0) { transactionApi.batchUpdate(any()) }
        assertEquals(2, queue.size)
        assertTrue(queue.all { it.state == PendingOperationEntity.STATE_PENDING })
    }

    @Test
    fun negativeScheduledPatchUsesRemappedId() = runTest {
        schedCreate(-1L)
        schedPatch(-1L, categoryId = 11L)
        coEvery { scheduledApi.create(any(), isNull()) } returns Response.success(IdResponse(70L))
        coEvery { scheduledApi.batchUpdate(any()) } returns ok()

        assertTrue(newRepository().pushPendingOperations().isSuccess)

        coVerify(exactly = 1) { scheduledApi.batchUpdate(match { it.ids == listOf(70L) }) }
    }

    @Test
    fun patch403IsLostAccess() = runTest {
        val op = txPatch(entityId = 42L)
        coEvery { transactionApi.batchUpdate(any()) } returns errorBody(403)

        val ex = newRepository().pushPendingOperations().exceptionOrNull() as SyncPushException

        assertEquals(1, ex.lostAccessCount)
        assertEquals(PendingOperationEntity.STATE_FAILED, queue.single().state)
        coVerify(exactly = 0) { pendingOpDao.delete(op) }
    }

    @Test
    fun patch400IsPermanent() = runTest {
        // e.g. the entry's type was flipped on the web and the new category no longer fits.
        txPatch(entityId = 42L, description = null, categoryId = 11L)
        coEvery { transactionApi.batchUpdate(any()) } returns errorBody(400)

        val ex = newRepository().pushPendingOperations().exceptionOrNull() as SyncPushException

        assertEquals(1, ex.permanentCount)
        assertFalse(ex.canRetry)
        assertEquals(PendingOperationEntity.STATE_FAILED, queue.single().state)
    }

    @Test
    fun patchOnOldServer405IsPermanentNotDropped() = runTest {
        txPatch(entityId = 42L)
        coEvery { transactionApi.batchUpdate(any()) } returns errorBody(405)

        val ex = newRepository().pushPendingOperations().exceptionOrNull() as SyncPushException

        assertEquals(1, ex.permanentCount)
        assertEquals(PendingOperationEntity.STATE_FAILED, queue.single().state)
    }

    @Test
    fun emptyPatchPayloadIsPermanentWithoutARequest() = runTest {
        txPatch(entityId = 42L, description = null, categoryId = null)

        val ex = newRepository().pushPendingOperations().exceptionOrNull() as SyncPushException

        assertEquals(1, ex.permanentCount)
        coVerify(exactly = 0) { transactionApi.batchUpdate(any()) }
        assertEquals(PendingOperationEntity.STATE_FAILED, queue.single().state)
    }

    // ====================
    // Safety.
    // ====================

    @Test
    fun unknownOperationIsPermanent() = runTest {
        for (type in listOf("account", "category", "transaction", "scheduled", "budget")) {
            queue.clear()
            enqueue(PendingOperationEntity(entityType = type, operation = "MERGE", entityId = 5L, serverId = 5L))

            val ex = newRepository().pushPendingOperations().exceptionOrNull() as SyncPushException

            assertEquals(type, 1, ex.permanentCount)
            assertEquals("never silently dropped: $type", PendingOperationEntity.STATE_FAILED, queue.single().state)
        }
        enqueue(PendingOperationEntity(entityType = "account", operation = "PATCH", entityId = 6L, serverId = 6L))
        newRepository().pushPendingOperations()
        assertEquals(PendingOperationEntity.STATE_FAILED, queue.single { it.entityId == 6L }.state)
    }

    @Test
    fun concurrentSyncCallsSerialized() = runTest {
        txUpdate(entityId = 42L, accountId = 1L, categoryId = 2L)
        emptySync()
        var inFlight = 0
        var maxInFlight = 0
        val gate = CompletableDeferred<Unit>()
        coEvery { transactionApi.update(eq(42L), any(), isNull()) } coAnswers {
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            gate.await()
            inFlight--
            Response.success(Unit)
        }
        val repository = newRepository()

        val first = async { repository.sync() }
        val second = async { repository.sync() }
        runCurrent()
        assertEquals("the second drain waits for the first", 1, maxInFlight)
        gate.complete(Unit)

        assertTrue(first.await().isSuccess)
        assertTrue(second.await().isSuccess)
        assertEquals(1, maxInFlight)
        // The second drain found the op already pushed.
        coVerify(exactly = 1) { transactionApi.update(eq(42L), any(), isNull()) }
        coVerify(exactly = 2) { syncApi.sync(any()) }
    }

    // ====================
    // Supersede-on-success: nothing waits behind a permanent failure, and a
    // retry can never regress what a newer successful op set.
    // ====================

    private fun failedTxUpdate(entityId: Long, description: String?, amount: Double = 1500.0, categoryId: Long = 2L) =
        enqueue(PendingOperationEntity(
            entityType = "transaction", operation = "UPDATE", entityId = entityId, serverId = entityId,
            payloadJson = gson.toJson(TransactionInput(1L, categoryId, "income", amount, "EUR", description, "note", "2026-06-12")),
            state = PendingOperationEntity.STATE_FAILED, lastError = "HTTP 400"
        ))

    private fun failedTxPatch(entityId: Long, description: String?, categoryId: Long?) =
        enqueue(PendingOperationEntity(
            entityType = "transaction", operation = "PATCH", entityId = entityId, serverId = entityId,
            payloadJson = gson.toJson(BulkPatchPayload(description, categoryId)),
            state = PendingOperationEntity.STATE_FAILED, lastError = "HTTP 400"
        ))

    @Test
    fun deleteAfterPermanentlyFailedPatchIsSentAndDropsSupersededOps() = runTest {
        // Failed earlier, and fails again this drain.
        failedTxPatch(42L, "Stipendio", null)
        pendingOpDao.retryFailed()
        txUpdate(entityId = 42L, accountId = 1L, categoryId = 2L)
        txDelete(42L)
        coEvery { transactionApi.batchUpdate(any()) } returns errorBody(400)
        coEvery { transactionApi.update(eq(42L), any(), isNull()) } returns errorBody(400)
        coEvery { transactionApi.delete(eq(42L), isNull()) } returns Response.success(Unit)

        val ex = newRepository().pushPendingOperations().exceptionOrNull() as SyncPushException

        // The device deleted the row: the DELETE reached the server...
        coVerify(exactly = 1) { transactionApi.delete(eq(42L), isNull()) }
        // ...and took the failed PATCH and UPDATE with it, so neither Retry
        // nor Discard can leave the row alive or edit it afterwards.
        assertTrue("queue: $queue", queue.isEmpty())
        assertEquals(2, ex.permanentCount)
        pendingOpDao.retryFailed()
        assertTrue(newRepository().pushPendingOperations().isSuccess)
        coVerify(exactly = 1) { transactionApi.batchUpdate(any()) }
    }

    @Test
    fun deleteSupersedesFailedOpsFromAnEarlierDrainButNotCreates() = runTest {
        failedTxPatch(42L, "Stipendio", null)
        failedTxUpdate(42L, "Paga")
        // A failed CREATE of another row that happens to share the number:
        // CREATEs are never touched by reconciliation.
        val create = enqueue(PendingOperationEntity(
            entityType = "transaction", operation = "CREATE", entityId = 42L,
            payloadJson = gson.toJson(TransactionInput(1L, 2L, "expense", 1.0, "EUR", null, null, "2026-06-12")),
            clientId = "c", state = PendingOperationEntity.STATE_FAILED, lastError = "HTTP 400"
        ))
        val otherEntity = failedTxUpdate(43L, "Other")
        txDelete(42L)
        coEvery { transactionApi.delete(eq(42L), isNull()) } returns Response.success(Unit)

        assertTrue(newRepository().pushPendingOperations().isSuccess)

        assertEquals(listOf(create.id, otherEntity.id), queue.map { it.id })
    }

    @Test
    fun retryAfterNewerUpdateSucceededCannotRegress() = runTest {
        // (1) An older failed PATCH and UPDATE, then a newer full UPDATE that
        // succeeds: the UPDATE was built from the local row, which already
        // holds both older edits, so both are dropped.
        failedTxPatch(42L, "Old name", 11L)
        failedTxUpdate(42L, "Old name", amount = 10.0)
        txUpdate(entityId = 42L, accountId = 1L, categoryId = 2L) // "edited", 9.99
        coEvery { transactionApi.update(eq(42L), any(), isNull()) } returns Response.success(Unit)

        assertTrue(newRepository().pushPendingOperations().isSuccess)
        assertTrue("superseded ops dropped: $queue", queue.isEmpty())

        // (2) An older failed UPDATE, then a newer PATCH that succeeds: the
        // UPDATE keeps its own amount but takes the PATCH's name and category,
        // so retrying it can't put the old name back.
        failedTxUpdate(50L, "Paga", amount = 1500.0, categoryId = 2L)
        txPatch(entityId = 50L, description = "Stipendio", categoryId = 11L)
        val sent = mutableListOf<TransactionInput>()
        coEvery { transactionApi.batchUpdate(any()) } returns ok()
        coEvery { transactionApi.update(eq(50L), capture(sent), isNull()) } returns Response.success(Unit)

        assertTrue(newRepository().pushPendingOperations().isSuccess)
        pendingOpDao.retryFailed()
        assertTrue(newRepository().pushPendingOperations().isSuccess)

        val retried = sent.single()
        assertEquals("Stipendio", retried.description)
        assertEquals(11L, retried.categoryId)
        assertEquals(1500.0, retried.amount, 0.0)
        assertEquals("note", retried.note)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun patchSupersedesOlderPatchFieldsOnly() = runTest {
        // Older PATCH set both fields, newer one only the name: the older
        // keeps its category, loses the name.
        val both = failedTxPatch(42L, "Old name", 11L)
        // Older PATCH that only set the name: nothing left, dropped.
        failedTxPatch(42L, "Older name", null)
        // A newer op of the same entity is not touched.
        txPatch(entityId = 42L, description = "Stipendio")
        val newer = txPatch(entityId = 42L, description = null, categoryId = 12L)
        val requests = mutableListOf<BatchUpdateRequest>()
        coEvery { transactionApi.batchUpdate(capture(requests)) } answers {
            // Fail the newest so it stays queued for inspection.
            if (firstArg<BatchUpdateRequest>().categoryId == 12L) errorBody(400) else ok()
        }

        newRepository().pushPendingOperations()

        assertEquals(listOf(both.id, newer.id), queue.map { it.id })
        val trimmed = queue.first()
        assertEquals(setOf("category_id"), payloadKeys(trimmed))
        assertEquals(11L, gson.fromJson(trimmed.payloadJson, BulkPatchPayload::class.java).categoryId)
        assertEquals("newer op untouched", setOf("category_id"), payloadKeys(queue.last()))
        assertEquals(12L, gson.fromJson(queue.last().payloadJson, BulkPatchPayload::class.java).categoryId)

        // Retrying the trimmed op re-sends only the category.
        requests.clear()
        coEvery { transactionApi.batchUpdate(capture(requests)) } returns ok()
        pendingOpDao.retryFailed()
        assertTrue(newRepository().pushPendingOperations().isSuccess)
        assertEquals(BatchUpdateRequest(ids = listOf(42L), categoryId = 11L), requests.first())
    }

    @Test
    fun retryDuringDrainIsSentNotReblocked() = runTest {
        val failed = failedTxUpdate(42L, "Paga")
        txUpdate(entityId = 43L, accountId = 1L, categoryId = 2L)
        txPatch(entityId = 42L, description = "Stipendio")
        val sent42 = mutableListOf<TransactionInput>()
        // The user taps Retry in Settings while the drain is talking to the server.
        coEvery { transactionApi.update(eq(43L), any(), isNull()) } coAnswers {
            pendingOpDao.retryFailed()
            Response.success(Unit)
        }
        coEvery { transactionApi.batchUpdate(any()) } returns ok()
        coEvery { transactionApi.update(eq(42L), capture(sent42), isNull()) } returns Response.success(Unit)

        val result = newRepository().pushPendingOperations()

        assertTrue("nothing re-failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        coVerify(exactly = 1) { transactionApi.batchUpdate(match { it.ids == listOf(42L) }) }
        // The retried op went out in the same drain, carrying the newer name.
        assertEquals("Stipendio", sent42.single().description)
        assertTrue(queue.none { it.id == failed.id })
        assertTrue(queue.isEmpty())
    }

    @Test
    fun discardOnlyDropsGenuinelyFailedOps() = runTest {
        val bad = txUpdate(entityId = 42L, accountId = 1L, categoryId = 2L)
        txPatch(entityId = 42L, description = null, categoryId = 11L)
        txDelete(43L)
        coEvery { transactionApi.update(eq(42L), any(), isNull()) } returns errorBody(400)
        coEvery { transactionApi.batchUpdate(any()) } returns ok()
        coEvery { transactionApi.delete(eq(43L), isNull()) } returns Response.success(Unit)

        val ex = newRepository().pushPendingOperations().exceptionOrNull() as SyncPushException

        assertEquals(1, ex.permanentCount)
        assertEquals(listOf(bad.id), queue.map { it.id })
        // Everything else reached the server before the user could discard.
        coVerify(exactly = 1) { transactionApi.batchUpdate(any()) }
        coVerify(exactly = 1) { transactionApi.delete(eq(43L), isNull()) }

        pendingOpDao.deleteFailed()

        assertTrue(queue.isEmpty())
        assertTrue(newRepository().pushPendingOperations().isSuccess)
    }

    @Test
    fun transientStillSkipsLaterOpsOfSameEntity() = runTest {
        txUpdate(entityId = 42L, accountId = 1L, categoryId = 2L)
        val held = txPatch(entityId = 42L)
        txPatch(entityId = 43L)
        txPatch(entityId = 44L)
        coEvery { transactionApi.update(eq(42L), any(), isNull()) } returns errorBody(503)
        coEvery { transactionApi.batchUpdate(any()) } returns ok()

        val ex = newRepository().pushPendingOperations().exceptionOrNull() as SyncPushException

        assertEquals(1, ex.transientCount)
        assertEquals(0, ex.permanentCount)
        // The held PATCH is neither sent nor folded into the others' batch.
        coVerify(exactly = 1) { transactionApi.batchUpdate(any()) }
        coVerify(exactly = 1) { transactionApi.batchUpdate(match { it.ids == listOf(43L, 44L) }) }
        assertEquals(listOf(1L, held.id), queue.map { it.id })
        assertTrue(queue.all { it.state == PendingOperationEntity.STATE_PENDING })
    }

    @Test
    fun clockSkewDoesNotReorderOps() = runTest {
        // The clock went back between the two edits: the newer op has the
        // older timestamp. Queue (id) order still wins.
        enqueue(PendingOperationEntity(
            entityType = "transaction", operation = "UPDATE", entityId = 42L, serverId = 42L,
            payloadJson = gson.toJson(TransactionInput(1L, 2L, "income", 1500.0, "EUR", "Paga", null, "2026-06-12")),
            createdAt = 5_000_000L
        ))
        txPatch(entityId = 42L, description = "Stipendio", createdAt = 1_000L)
        val calls = mutableListOf<String>()
        coEvery { transactionApi.update(eq(42L), any(), isNull()) } answers { calls += "UPDATE"; Response.success(Unit) }
        coEvery { transactionApi.batchUpdate(any()) } answers { calls += "PATCH"; ok() }

        assertTrue(newRepository().pushPendingOperations().isSuccess)

        assertEquals(listOf("UPDATE", "PATCH"), calls)
    }

    // ====================
    // Coalescing.
    // ====================

    @Test
    fun identicalPatchesSentAsOneBatch() = runTest {
        // An older failed UPDATE of one of the batched rows: reconciled by the
        // batch's success like after a single PATCH.
        val failed = failedTxUpdate(43L, "Paga")
        txPatch(entityId = 42L, description = "Stipendio", categoryId = 11L)
        txPatch(entityId = 43L, description = "Stipendio", categoryId = 11L)
        txPatch(entityId = 44L, description = "Stipendio", categoryId = 11L)
        schedPatch(70L, description = "Stipendio", categoryId = 11L)
        schedPatch(71L, description = "Stipendio", categoryId = 11L)
        val txRequests = mutableListOf<BatchUpdateRequest>()
        val schedRequests = mutableListOf<ScheduledBatchUpdateRequest>()
        coEvery { transactionApi.batchUpdate(capture(txRequests)) } returns ok()
        coEvery { scheduledApi.batchUpdate(capture(schedRequests)) } returns ok()

        assertTrue(newRepository().pushPendingOperations().isSuccess)

        assertEquals(
            listOf(BatchUpdateRequest(ids = listOf(42L, 43L, 44L), description = "Stipendio", categoryId = 11L)),
            txRequests
        )
        assertEquals(
            listOf(ScheduledBatchUpdateRequest(ids = listOf(70L, 71L), description = "Stipendio", categoryId = 11L)),
            schedRequests
        )
        val left = queue.single()
        assertEquals(failed.id, left.id)
        assertEquals("Stipendio", txInput(left).description)
        assertEquals(11L, txInput(left).categoryId)
    }

    @Test
    fun batchOf500Splits() = runTest {
        for (id in 1L..1201L) txPatch(entityId = id, description = "Stipendio")
        val requests = mutableListOf<BatchUpdateRequest>()
        coEvery { transactionApi.batchUpdate(capture(requests)) } returns ok()

        assertTrue(newRepository().pushPendingOperations().isSuccess)

        assertEquals(listOf(500, 500, 201), requests.map { it.ids.size })
        assertEquals((1L..1201L).toList(), requests.flatMap { it.ids })
        assertTrue(queue.isEmpty())
    }

    @Test
    fun batchFailureFallsBackToSingles() = runTest {
        txPatch(entityId = 42L)
        txPatch(entityId = 43L)
        txPatch(entityId = 44L)
        coEvery { transactionApi.batchUpdate(any()) } answers {
            val ids = firstArg<BatchUpdateRequest>().ids
            when {
                ids.size > 1 -> errorBody(403) // the whole batch is refused...
                ids == listOf(43L) -> errorBody(403) // ...because of this one
                else -> ok()
            }
        }

        val ex = newRepository().pushPendingOperations().exceptionOrNull() as SyncPushException

        assertEquals(1, ex.lostAccessCount)
        assertEquals(1, ex.permanentCount)
        coVerify(exactly = 1) { transactionApi.batchUpdate(match { it.ids == listOf(42L, 43L, 44L) }) }
        for (id in listOf(42L, 43L, 44L)) {
            coVerify(exactly = 1) { transactionApi.batchUpdate(match { it.ids == listOf(id) }) }
        }
        val failed = queue.single()
        assertEquals(43L, failed.entityId)
        assertEquals(PendingOperationEntity.STATE_FAILED, failed.state)
        assertTrue(failed.lastError!!.endsWith("possible lost access"))
    }

    @Test
    fun batchExceptionFallsBackToSingles() = runTest {
        txDelete(42L)
        txDelete(43L)
        coEvery { transactionApi.batchDelete(any()) } throws java.io.IOException("reset")
        coEvery { transactionApi.delete(any(), isNull()) } returns Response.success(Unit)

        assertTrue(newRepository().pushPendingOperations().isSuccess)

        coVerify(exactly = 1) { transactionApi.delete(eq(42L), isNull()) }
        coVerify(exactly = 1) { transactionApi.delete(eq(43L), isNull()) }
        assertTrue(queue.isEmpty())
    }

    @Test
    fun batch429StopsDrainAllPending() = runTest {
        txPatch(entityId = 42L)
        txPatch(entityId = 43L)
        txUpdate(entityId = 50L, accountId = 1L, categoryId = 2L)
        coEvery { transactionApi.batchUpdate(any()) } returns errorBody(429)

        val ex = newRepository().pushPendingOperations().exceptionOrNull() as SyncPushException

        assertEquals(2, ex.transientCount)
        assertEquals(0, ex.permanentCount)
        assertTrue(ex.canRetry)
        // One request, no per-op fallback, nothing after it.
        coVerify(exactly = 1) { transactionApi.batchUpdate(any()) }
        coVerify(exactly = 0) { transactionApi.update(any(), any(), any()) }
        assertEquals(3, queue.size)
        assertTrue(queue.all { it.state == PendingOperationEntity.STATE_PENDING })
    }

    @Test
    fun differentPayloadsNotMerged() = runTest {
        txPatch(entityId = 42L, description = "A")
        txPatch(entityId = 43L, description = "B")
        txPatch(entityId = 44L, description = "A") // same as 42 but not adjacent
        // Same payload bytes, different entity type: never one request.
        schedPatch(70L, description = "A", categoryId = null)
        val txRequests = mutableListOf<BatchUpdateRequest>()
        val schedRequests = mutableListOf<ScheduledBatchUpdateRequest>()
        coEvery { transactionApi.batchUpdate(capture(txRequests)) } returns ok()
        coEvery { scheduledApi.batchUpdate(capture(schedRequests)) } returns ok()

        assertTrue(newRepository().pushPendingOperations().isSuccess)

        assertEquals(listOf(listOf(42L), listOf(43L), listOf(44L)), txRequests.map { it.ids })
        assertEquals(listOf("A", "B", "A"), txRequests.map { it.description })
        assertEquals(listOf(listOf(70L)), schedRequests.map { it.ids })
    }

    @Test
    fun negativeOrSkippedOpsNotMerged() = runTest {
        txUpdate(entityId = 44L, accountId = 1L, categoryId = 2L) // fails transiently
        txPatch(entityId = 42L)
        txPatch(entityId = -7L) // its CREATE hasn't been pushed
        txPatch(entityId = 43L)
        txPatch(entityId = 44L) // held behind the failed UPDATE
        txPatch(entityId = 45L)
        txPatch(entityId = 45L) // same entity twice: never in one batch
        coEvery { transactionApi.update(eq(44L), any(), isNull()) } returns errorBody(503)
        val requests = mutableListOf<BatchUpdateRequest>()
        coEvery { transactionApi.batchUpdate(capture(requests)) } returns ok()

        newRepository().pushPendingOperations()

        assertEquals(listOf(listOf(42L), listOf(43L), listOf(45L), listOf(45L)), requests.map { it.ids })
        assertTrue(requests.none { -7L in it.ids || 44L in it.ids })
        assertEquals(listOf(-7L, 44L, 44L), queue.map { it.entityId }.sorted())
    }

    @Test
    fun deletesSentViaBatchDelete() = runTest {
        txDelete(42L)
        txDelete(43L)
        txDelete(44L)
        schedDelete(70L)
        schedDelete(71L)
        val request = slot<com.insituledger.app.data.remote.dto.BatchDeleteRequest>()
        coEvery { transactionApi.batchDelete(capture(request)) } returns Response.success(Unit)
        coEvery { scheduledApi.delete(any(), isNull()) } returns Response.success(Unit)

        assertTrue(newRepository().pushPendingOperations().isSuccess)

        assertEquals(listOf(42L, 43L, 44L), request.captured.ids)
        coVerify(exactly = 1) { transactionApi.batchDelete(any()) }
        coVerify(exactly = 0) { transactionApi.delete(any(), any()) }
        // Schedules have no batch endpoint: still one DELETE each.
        coVerify(exactly = 1) { scheduledApi.delete(eq(70L), isNull()) }
        coVerify(exactly = 1) { scheduledApi.delete(eq(71L), isNull()) }
        assertTrue(queue.isEmpty())
    }

    @Test
    fun batchDelete404IsSuccess() = runTest {
        txDelete(42L)
        txDelete(43L)
        coEvery { transactionApi.batchDelete(any()) } returns errorBody(404)

        assertTrue(newRepository().pushPendingOperations().isSuccess)

        coVerify(exactly = 0) { transactionApi.delete(any(), any()) }
        assertTrue(queue.isEmpty())
    }

    @Test
    fun batchDelete429StopsDrain() = runTest {
        txDelete(42L)
        txDelete(43L)
        coEvery { transactionApi.batchDelete(any()) } returns errorBody(429)

        val ex = newRepository().pushPendingOperations().exceptionOrNull() as SyncPushException

        assertEquals(2, ex.transientCount)
        coVerify(exactly = 0) { transactionApi.delete(any(), any()) }
        assertTrue(queue.all { it.state == PendingOperationEntity.STATE_PENDING })
    }

    // ====================
    // Server version (bulk-edit gate).
    // ====================

    @Test
    fun syncStoresTheServerVersion() = runTest {
        emptySync()
        coEvery { syncApi.health() } returns Response.success(
            com.insituledger.app.data.remote.dto.HealthResponse(status = "ok", version = "1.27.0")
        )

        assertTrue(newRepository().sync().isSuccess)

        coVerify(exactly = 1) { prefs.saveServerVersion("1.27.0") }
    }

    @Test
    fun serverWithoutVersionIsStoredAsOld() = runTest {
        emptySync()
        coEvery { syncApi.health() } returns Response.success(
            com.insituledger.app.data.remote.dto.HealthResponse(status = "ok")
        )

        assertTrue(newRepository().sync().isSuccess)

        coVerify(exactly = 1) { prefs.saveServerVersion(com.insituledger.app.util.ServerVersion.OLD) }
    }

    @Test
    fun healthFetchFailureDoesNotBreakSync() = runTest {
        txPatch(entityId = 42L)
        coEvery { transactionApi.batchUpdate(any()) } returns ok()
        for (failure in listOf<suspend () -> Response<com.insituledger.app.data.remote.dto.HealthResponse>>(
            { throw java.io.IOException("no route") },
            { errorBody(503) },
            { throw com.google.gson.JsonSyntaxException("<html>") }
        )) {
            emptySync()
            coEvery { syncApi.health() } coAnswers { failure() }

            assertTrue(newRepository().sync().isSuccess)
        }

        coVerify(exactly = 0) { prefs.saveServerVersion(any()) }
        coVerify(exactly = 1) { transactionApi.batchUpdate(any()) }
        coVerify(exactly = 3) { syncApi.sync(any()) }
    }
}
