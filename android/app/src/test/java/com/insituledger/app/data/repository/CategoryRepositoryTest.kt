package com.insituledger.app.data.repository

import com.google.gson.Gson
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.dao.CategoryDao
import com.insituledger.app.data.local.db.dao.PendingOperationDao
import com.insituledger.app.data.local.db.dao.ScheduledTransactionDao
import com.insituledger.app.data.local.db.dao.TransactionDao
import com.insituledger.app.data.local.db.entity.CategoryEntity
import com.insituledger.app.data.remote.api.CategoryApi
import com.insituledger.app.data.sync.SyncManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Test

class CategoryRepositoryTest {
    private val categoryDao: CategoryDao = mockk(relaxed = true)
    private val transactionDao: TransactionDao = mockk(relaxed = true)
    private val scheduledDao: ScheduledTransactionDao = mockk(relaxed = true)
    private val pendingOpDao: PendingOperationDao = mockk(relaxed = true)
    private val categoryApi: CategoryApi = mockk(relaxed = true)
    private val syncManager: SyncManager = mockk(relaxed = true)
    private val prefs: UserPreferences = mockk(relaxed = true)

    private fun repository() = CategoryRepository(
        categoryDao, transactionDao, scheduledDao, pendingOpDao,
        categoryApi, Gson(), syncManager, prefs
    )

    @Test
    fun undeletedScheduledTransactionBlocksDeleteRegardlessOfActiveState() = runTest {
        coEvery { categoryDao.getById(4L) } returns CategoryEntity(
            id = 4L, userId = 1L, name = "Rent", type = "expense"
        )
        coEvery { transactionDao.countByCategoryId(4L) } returns 0
        // DAO query intentionally counts active and inactive rows, excluding
        // only soft-deleted schedules.
        coEvery { scheduledDao.countByCategoryId(4L) } returns 1

        val result = repository().delete(4L)

        assertSame(CategoryDeleteResult.HasScheduled, result)
        coVerify(exactly = 0) { categoryDao.upsert(any()) }
        coVerify(exactly = 0) { pendingOpDao.insert(any()) }
    }

    @Test
    fun noLiveDependentsAllowsDelete() = runTest {
        coEvery { categoryDao.getById(4L) } returns CategoryEntity(
            id = 4L, userId = 1L, name = "Rent", type = "expense"
        )
        coEvery { transactionDao.countByCategoryId(4L) } returns 0
        coEvery { scheduledDao.countByCategoryId(4L) } returns 0

        val result = repository().delete(4L)

        assertSame(CategoryDeleteResult.Success, result)
        coVerify(exactly = 1) { categoryDao.upsert(match { it.deletedAt == "deleted" }) }
    }
}
