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

    private fun newRepository() = SyncRepository(
        database, syncApi, transactionApi, categoryApi, accountApi, scheduledApi,
        transactionDao, categoryDao, accountDao, scheduledDao, pendingOpDao,
        prefs, gson
    )

    @Before
    fun setUp() {
        queue.clear()
        nextOpId = 1L
        // Route Room's withTransaction block straight through so remap*Id
        // executes its body in tests.
        mockkStatic("androidx.room.RoomDatabaseKt")
        val block = slot<suspend () -> Any?>()
        coEvery { database.withTransaction(capture(block)) } coAnswers { block.captured.invoke() }

        coEvery { pendingOpDao.getAll() } answers { queue.toList() }
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
        coEvery { pendingOpDao.findByEntityTypeAndOperation(any(), any()) } answers {
            val type = firstArg<String>()
            val op = secondArg<String>()
            queue.filter { it.entityType == type && it.operation == op }
        }
        coEvery { pendingOpDao.getById(any()) } answers {
            val id = firstArg<Long>()
            queue.firstOrNull { it.id == id }
        }
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

    private fun catCreate(localId: Long, parentId: Long? = null): PendingOperationEntity {
        val input = CategoryInput(parentId, "Food", "expense", null, null)
        return enqueue(PendingOperationEntity(
            entityType = "category", operation = "CREATE",
            entityId = localId, payloadJson = gson.toJson(input), clientId = "cat-$localId"
        ))
    }

    private inline fun <reified T> errorBody(code: Int): Response<T> = Response.error(
        code, "".toResponseBody("application/json".toMediaTypeOrNull())
    )

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
}
