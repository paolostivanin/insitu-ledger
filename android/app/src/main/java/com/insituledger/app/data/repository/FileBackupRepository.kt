package com.insituledger.app.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.insituledger.app.data.local.db.dao.*
import com.insituledger.app.data.local.db.entity.*
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class BackupData(
    val version: Int = BACKUP_SCHEMA_VERSION,
    val accounts: List<AccountBackup>,
    val categories: List<CategoryBackup>,
    val transactions: List<TransactionBackup>,
    @SerializedName("scheduled_transactions")
    val scheduledTransactions: List<ScheduledBackup>
)

// Bump when the backup payload shape changes in a non-additive way.
// Importer rejects newer versions outright (we don't know the new fields yet)
// and refuses unknown older versions until we've written a migration.
const val BACKUP_SCHEMA_VERSION = 1

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
    val description: String?, val note: String? = null, val date: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String
)

data class ScheduledBackup(
    val id: Long, @SerializedName("account_id") val accountId: Long,
    @SerializedName("category_id") val categoryId: Long,
    val type: String, val amount: Double, val currency: String,
    val description: String?, val note: String? = null, val rrule: String,
    @SerializedName("next_occurrence") val nextOccurrence: String,
    val active: Boolean,
    @SerializedName("max_occurrences") val maxOccurrences: Int? = null,
    @SerializedName("occurrence_count") val occurrenceCount: Int = 0,
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
    suspend fun generateBackupJson(): Result<String> {
        return try {
            val accounts = accountDao.getAllSync().map {
                AccountBackup(it.id, it.name, it.currency, it.balance, it.createdAt, it.updatedAt)
            }
            val categories = categoryDao.getAllSync().map {
                CategoryBackup(it.id, it.parentId, it.name, it.type, it.icon, it.color, it.createdAt, it.updatedAt)
            }
            val transactions = transactionDao.getAllSync().map {
                TransactionBackup(it.id, it.accountId, it.categoryId, it.type, it.amount, it.currency, it.description, it.note, it.date, it.createdAt, it.updatedAt)
            }
            val scheduled = scheduledDao.getAllSync().map {
                ScheduledBackup(it.id, it.accountId, it.categoryId, it.type, it.amount, it.currency, it.description, it.note, it.rrule, it.nextOccurrence, it.active, it.maxOccurrences, it.occurrenceCount, it.createdAt, it.updatedAt)
            }

            val backup = BackupData(
                accounts = accounts,
                categories = categories,
                transactions = transactions,
                scheduledTransactions = scheduled
            )

            Result.success(gson.toJson(backup))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun exportToUri(uri: Uri): Result<Int> {
        return generateBackupJson().mapCatching { json ->
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } ?: throw Exception("Could not open output stream")
            val backup = gson.fromJson(json, BackupData::class.java)
            backup.accounts.size + backup.categories.size + backup.transactions.size + backup.scheduledTransactions.size
        }
    }

    fun takeFolderPermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
    }

    fun releaseFolderPermission(uri: Uri) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.releasePersistableUriPermission(uri, flags)
        } catch (_: Exception) {}
    }

    suspend fun importFromUri(uri: Uri): Result<Int> {
        return try {
            val json = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().readText()
            } ?: return Result.failure(Exception("Could not open input stream"))

            val backup = try {
                gson.fromJson(json, BackupData::class.java)
            } catch (e: Exception) {
                return Result.failure(Exception("Invalid backup file format: ${e.message}"))
            }

            if (backup == null) {
                return Result.failure(Exception("Invalid backup file: empty or malformed JSON"))
            }

            if (backup.version > BACKUP_SCHEMA_VERSION) {
                return Result.failure(Exception(
                    "Backup is from a newer version of the app (v${backup.version}). " +
                    "Update InSitu Ledger before importing."
                ))
            }
            if (backup.version < 1) {
                return Result.failure(Exception("Backup version ${backup.version} is not supported."))
            }

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
                    description = it.description, note = it.note, date = it.date,
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
                    description = it.description, note = it.note, rrule = it.rrule,
                    nextOccurrence = it.nextOccurrence, active = it.active,
                    maxOccurrences = it.maxOccurrences, occurrenceCount = it.occurrenceCount,
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
