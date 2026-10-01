package com.insituledger.app.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.AppDatabase
import com.insituledger.app.data.local.db.dao.*
import com.insituledger.app.data.local.db.entity.AccountEntity
import com.insituledger.app.data.remote.api.AccountApi
import com.insituledger.app.data.sync.SyncManager
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class AccountRepositoryTest {
    private val db: AppDatabase = mockk(relaxed = true)
    private val accounts: AccountDao = mockk(relaxed = true)
    private val transactions: TransactionDao = mockk(relaxed = true)
    private val scheduled: ScheduledTransactionDao = mockk(relaxed = true)
    private val pending: PendingOperationDao = mockk(relaxed = true)
    private val prefs: UserPreferences = mockk(relaxed = true)
    private val sync: SyncManager = mockk(relaxed = true)

    @Before fun setup() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        val block = slot<suspend () -> Any?>()
        coEvery { db.withTransaction(capture(block)) } coAnswers { block.captured.invoke() }
        every { db.transactionDao() } returns transactions
        every { db.scheduledTransactionDao() } returns scheduled
        coEvery { accounts.getById(-1) } returns AccountEntity(-1, 0, "Local", isLocalOnly = true)
        coEvery { transactions.selectIdsByAccountId(-1) } returns listOf(-2)
        coEvery { scheduled.selectIdsByAccountId(-1) } returns listOf(-3)
    }
    @After fun teardown() { unmockkStatic("androidx.room.RoomDatabaseKt") }
    private fun repository() = AccountRepository(accounts, pending, mockk<AccountApi>(), Gson(), sync, prefs, db)

    @Test fun deletingLocalAccountHidesDependentsAndCancelsTheirPendingWrites() = runTest {
        every { prefs.getSyncModeImmediate() } returns "none"
        repository().delete(-1)
        coVerifyOrder {
            pending.deleteByEntity("transaction", -2)
            pending.deleteByEntity("scheduled", -3)
            transactions.softDeleteByAccountId(-1)
            scheduled.softDeleteByAccountId(-1)
            accounts.upsert(match { it.deletedAt != null })
        }
        coVerify { pending.deleteByEntity("account", -1) }
        coVerify(exactly = 0) { pending.insert(any()) }
    }

    @Test fun syncedDeletionKeepsParentCreateAndEnqueuesDeleteForRemapping() = runTest {
        every { prefs.getSyncModeImmediate() } returns "webapp"
        repository().delete(-1)
        coVerify(exactly = 0) { pending.deleteByEntity("account", -1) }
        coVerify { pending.insert(match { it.entityType == "account" && it.operation == "DELETE" && it.entityId == -1L && it.serverId == null }) }
        verify { sync.triggerImmediateSync() }
    }
}
