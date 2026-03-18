package com.insituledger.app.data.repository

import android.content.Context
import android.net.Uri
import com.insituledger.app.data.local.db.dao.*
import com.insituledger.app.data.local.db.entity.*
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class BackupData(
    val version: Int = 1,
    val accounts: List<AccountBackup>,
    val categories: List<CategoryBackup>,
    val transactions: List<TransactionBackup>,
    @SerializedName("scheduled_transactions")
    val scheduledTransactions: List<ScheduledBackup>
)

data class AccountBackup(
    val id: Long, val name: String, val currency: String, val balance: Double,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String
)

data class CategoryBackup(
    val id: Long, @SerializedName("parent_id") val parentId: Long?,
    val name: String, val type: String, val icon: String?, val color: String?,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String
)

data class TransactionBackup(
    val id: Long, @SerializedName("account_id") val accountId: Long,
    @SerializedName("category_id") val categoryId: Long,
    val type: String, val amount: Double, val currency: String,
    val description: String?, val date: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String
)

data class ScheduledBackup(
    val id: Long, @SerializedName("account_id") val accountId: Long,
    @SerializedName("category_id") val categoryId: Long,
    val type: String, val amount: Double, val currency: String,
    val description: String?, val rrule: String,
    @SerializedName("next_occurrence") val nextOccurrence: String,
    val active: Boolean,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String
)

@Singleton
class FileBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
    private val transactionDao: TransactionDao,
    private val scheduledDao: ScheduledTransactionDao,
    private val gson: Gson
) {
    suspend fun exportToUri(uri: Uri): Result<Int> {
        return try {
            val accounts = accountDao.getAllSync().map {
                AccountBackup(it.id, it.name, it.currency, it.balance, it.createdAt, it.updatedAt)
            }
            val categories = categoryDao.getAllSync().map {
                CategoryBackup(it.id, it.parentId, it.name, it.type, it.icon, it.color, it.createdAt, it.updatedAt)
            }
            val transactions = transactionDao.getAllSync().map {
                TransactionBackup(it.id, it.accountId, it.categoryId, it.type, it.amount, it.currency, it.description, it.date, it.createdAt, it.updatedAt)
            }
            val scheduled = scheduledDao.getAllSync().map {
                ScheduledBackup(it.id, it.accountId, it.categoryId, it.type, it.amount, it.currency, it.description, it.rrule, it.nextOccurrence, it.active, it.createdAt, it.updatedAt)
            }

            val backup = BackupData(
                accounts = accounts,
                categories = categories,
                transactions = transactions,
                scheduledTransactions = scheduled
            )

            val json = gson.toJson(backup)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } ?: return Result.failure(Exception("Could not open output stream"))

            val totalItems = accounts.size + categories.size + transactions.size + scheduled.size
            Result.success(totalItems)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun importFromUri(uri: Uri): Result<Int> {
        return try {
            val json = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().readText()
            } ?: return Result.failure(Exception("Could not open input stream"))

            val backup = gson.fromJson(json, BackupData::class.java)

            // Import categories first (transactions reference them)
            val categoryEntities = backup.categories.map {
                CategoryEntity(
                    id = it.id, userId = 0, parentId = it.parentId,
                    name = it.name, type = it.type, icon = it.icon, color = it.color,
                    createdAt = it.createdAt, updatedAt = it.updatedAt,
                    isLocalOnly = true
                )
            }
            if (categoryEntities.isNotEmpty()) categoryDao.upsertAll(categoryEntities)

            // Import accounts
            val accountEntities = backup.accounts.map {
                AccountEntity(
                    id = it.id, userId = 0,
                    name = it.name, currency = it.currency, balance = it.balance,
                    createdAt = it.createdAt, updatedAt = it.updatedAt,
                    isLocalOnly = true
                )
            }
            if (accountEntities.isNotEmpty()) accountDao.upsertAll(accountEntities)

            // Import transactions
            val transactionEntities = backup.transactions.map {
                TransactionEntity(
                    id = it.id, accountId = it.accountId, categoryId = it.categoryId,
                    userId = 0, type = it.type, amount = it.amount, currency = it.currency,
                    description = it.description, date = it.date,
                    createdAt = it.createdAt, updatedAt = it.updatedAt,
                    isLocalOnly = true
                )
            }
            if (transactionEntities.isNotEmpty()) transactionDao.upsertAll(transactionEntities)

            // Import scheduled transactions
            val scheduledEntities = backup.scheduledTransactions.map {
                ScheduledTransactionEntity(
                    id = it.id, accountId = it.accountId, categoryId = it.categoryId,
                    userId = 0, type = it.type, amount = it.amount, currency = it.currency,
                    description = it.description, rrule = it.rrule,
                    nextOccurrence = it.nextOccurrence, active = it.active,
                    createdAt = it.createdAt, updatedAt = it.updatedAt,
                    isLocalOnly = true
                )
            }
            if (scheduledEntities.isNotEmpty()) scheduledDao.upsertAll(scheduledEntities)

            val totalItems = categoryEntities.size + accountEntities.size +
                    transactionEntities.size + scheduledEntities.size
            Result.success(totalItems)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
