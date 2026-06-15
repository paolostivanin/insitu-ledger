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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
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
}
