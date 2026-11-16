package com.insituledger.app.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.google.gson.Gson
import com.insituledger.app.data.local.db.AppDatabase
import com.insituledger.app.data.local.db.dao.AccountDao
import com.insituledger.app.data.local.db.dao.CategoryDao
import com.insituledger.app.data.local.db.dao.ScheduledTransactionDao
import com.insituledger.app.data.local.db.dao.TransactionDao
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

class FileBackupRepositoryTest {
    private val context: Context = mockk()
    private val resolver: ContentResolver = mockk()
    private val database: AppDatabase = mockk(relaxed = true)
    private val accountDao: AccountDao = mockk(relaxed = true)
    private val categoryDao: CategoryDao = mockk(relaxed = true)
    private val transactionDao: TransactionDao = mockk(relaxed = true)
    private val scheduledDao: ScheduledTransactionDao = mockk(relaxed = true)
    private val uri: Uri = mockk()
    private val gson = Gson()

    @Before
    fun setUp() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { context.contentResolver } returns resolver
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun midImportFailureEscapesSingleRoomTransactionForRollback() = runTest {
        val backup = BackupData(
            accounts = listOf(AccountBackup(1, "Wallet", "EUR", 0.0, "", "")),
            categories = listOf(CategoryBackup(1, null, "Food", "expense", null, null, "", "")),
            transactions = emptyList(),
            scheduledTransactions = emptyList()
        )
        coEvery { resolver.openInputStream(uri) } returns ByteArrayInputStream(gson.toJson(backup).toByteArray())
        val block = slot<suspend () -> Any?>()
        coEvery { database.withTransaction(capture(block)) } coAnswers { block.captured.invoke() }
        coEvery { accountDao.upsertAll(any()) } throws IllegalStateException("simulated write failure")

        val repository = FileBackupRepository(
            context, database, accountDao, categoryDao, transactionDao, scheduledDao, gson
        )
        val result = repository.importFromUri(uri)

        assertTrue(result.isFailure)
        coVerify(exactly = 1) { database.withTransaction(any<suspend () -> Any?>()) }
        coVerify(exactly = 1) { categoryDao.upsertAll(any()) }
        coVerify(exactly = 1) { accountDao.upsertAll(any()) }
        coVerify(exactly = 0) { transactionDao.upsertAll(any()) }
    }

    private fun repository() = FileBackupRepository(
        context, database, accountDao, categoryDao, transactionDao, scheduledDao, gson
    )

    /** Imports [json] and returns the transaction rows handed to Room. */
    private suspend fun importTransactions(json: String): List<TransactionEntity> {
        coEvery { resolver.openInputStream(uri) } returns ByteArrayInputStream(json.toByteArray())
        val block = slot<suspend () -> Any?>()
        coEvery { database.withTransaction(capture(block)) } coAnswers { block.captured.invoke() }
        val rows = slot<List<TransactionEntity>>()
        coEvery { transactionDao.upsertAll(capture(rows)) } returns Unit
        val result = repository().importFromUri(uri)
        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        return rows.captured
    }

    private fun entity(id: Long, scheduledId: Long? = null, occurrence: Int? = null, total: Int? = null) = TransactionEntity(
        id = id, accountId = 1, categoryId = 1, userId = 0, type = "expense", amount = 12.5,
        date = "2026-11-01T10:00:00+01:00", scheduledId = scheduledId, occurrence = occurrence, occurrenceTotal = total
    )

    @Test
    fun recurringMarkerRoundTripsThroughABackup() = runTest {
        coEvery { accountDao.getAllSync() } returns emptyList()
        coEvery { categoryDao.getAllSync() } returns emptyList()
        coEvery { scheduledDao.getAllSync() } returns emptyList()
        coEvery { transactionDao.getAllSync() } returns listOf(
            entity(1, scheduledId = 4, occurrence = 2, total = 3),
            entity(2, scheduledId = 5, occurrence = 7),
            entity(3)
        )
        val json = repository().generateBackupJson().getOrThrow()
        assertTrue(json, json.contains("\"scheduled_id\":4") && json.contains("\"occurrence_total\":3"))
        assertTrue(json, json.contains("\"version\":$BACKUP_SCHEMA_VERSION"))

        val (bounded, open, plain) = importTransactions(json)
        assertEquals(Triple(4L, 2, 3), Triple(bounded.scheduledId, bounded.occurrence, bounded.occurrenceTotal))
        assertEquals(5L, open.scheduledId)
        assertEquals(7, open.occurrence)
        assertNull(open.occurrenceTotal)
        assertNull(plain.scheduledId)
        assertNull(plain.occurrence)
        assertNull(plain.occurrenceTotal)
    }

    @Test
    fun backupWrittenBeforeTheMarkerStillImports() = runTest {
        val json = """
            {"version": 1, "accounts": [], "categories": [], "scheduled_transactions": [],
             "transactions": [{"id": 9, "account_id": 1, "category_id": 1, "type": "income",
               "amount": 5.0, "currency": "EUR", "description": "Gift", "date": "2025-02-03",
               "created_at": "", "updated_at": ""}]}
        """.trimIndent()
        val row = importTransactions(json).single()
        assertEquals(9L, row.id)
        assertNull(row.scheduledId)
        assertNull(row.occurrence)
        assertNull(row.occurrenceTotal)
    }

    @Test
    fun nonPositiveSeriesPositionIsRejected() {
        val txn = TransactionBackup(1, 1, 1, "expense", 1.0, "EUR", null, null, "2026-01-01", "", "", scheduledId = 3, occurrence = 0)
        val backup = BackupData(accounts = emptyList(), categories = emptyList(), transactions = listOf(txn), scheduledTransactions = emptyList())
        assertEquals("transactions[0]: invalid occurrence 0", validateBackup(backup))
        assertEquals(
            "transactions[0]: invalid occurrence_total -1",
            validateBackup(backup.copy(transactions = listOf(txn.copy(occurrence = 1, occurrenceTotal = -1))))
        )
        assertNull(validateBackup(backup.copy(transactions = listOf(txn.copy(occurrence = 1, occurrenceTotal = null)))))
    }
}
