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
import com.insituledger.app.data.remote.dto.CreateTransactionResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
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

    private fun newRepository() = SyncRepository(
        database, syncApi, transactionApi, categoryApi, accountApi, scheduledApi,
        transactionDao, categoryDao, accountDao, scheduledDao, pendingOpDao,
        prefs, Gson()
    )

    private fun createOp() = PendingOperationEntity(
        id = 1,
        entityType = "transaction",
        operation = "CREATE",
        entityId = -7,
        payloadJson = """{"account_id":1,"category_id":2,"type":"expense","amount":4.15,"currency":"EUR","date":"2026-06-12T08:41:00+03:00"}""",
        clientId = "uuid-1"
    )

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    // PLAN §6.5 defense: if the backend ever converts a transaction CREATE
    // into a scheduled tx ({"scheduled": true}), the local optimistic row must
    // be dropped (the next pull brings the scheduled_transactions row) and the
    // returned id must NOT be remapped onto the transactions table — pre-1.28
    // that remap produced a phantom local row pointing at a
    // scheduled_transactions id, the visible-duplicate bug.
    @Test
    fun createRespondingScheduledTrueDropsLocalRowAndSkipsRemap() = runTest {
        val op = createOp()
        coEvery { pendingOpDao.getAll() } returns listOf(op)
        coEvery { transactionApi.create(any(), isNull()) } returns
            Response.success(CreateTransactionResponse(id = 99, scheduled = true))

        val result = newRepository().pushPendingOperations()

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { transactionDao.deleteById(-7) }
        // remapTransactionId must NOT run — no id rewrite, no op re-pointing.
        coVerify(exactly = 0) { transactionDao.updateId(any(), any()) }
        coVerify(exactly = 0) { pendingOpDao.updateEntityId(any(), any(), any()) }
        // The op counts as handled and is cleared from the queue.
        coVerify(exactly = 1) { pendingOpDao.delete(op) }
    }

    // Positive control for the assertion above: a plain {"id": N} response
    // (scheduled defaults to false) keeps the pre-existing remap behavior.
    @Test
    fun createRespondingPlainIdRemaps() = runTest {
        // remapTransactionId wraps its dao calls in Room's withTransaction
        // extension — route the captured block straight through.
        mockkStatic("androidx.room.RoomDatabaseKt")
        val block = slot<suspend () -> Any?>()
        coEvery { database.withTransaction(capture(block)) } coAnswers { block.captured.invoke() }

        val op = createOp()
        coEvery { pendingOpDao.getAll() } returns listOf(op)
        coEvery { transactionApi.create(any(), isNull()) } returns
            Response.success(CreateTransactionResponse(id = 99))

        val result = newRepository().pushPendingOperations()

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { transactionDao.updateId(-7, 99) }
        coVerify(exactly = 0) { transactionDao.deleteById(any()) }
        coVerify(exactly = 1) { pendingOpDao.delete(op) }
    }
}
