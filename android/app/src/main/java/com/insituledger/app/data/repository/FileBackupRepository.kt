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

    suspend fun exportToUri(uri: Uri, passphrase: String? = null): Result<Int> {
        return generateBackupJson().mapCatching { json ->
            val plaintext = json.toByteArray(Charsets.UTF_8)
            val payload = if (!passphrase.isNullOrEmpty()) {
                BackupCrypto.encrypt(plaintext, passphrase.toCharArray())
            } else {
                plaintext
            }
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(payload)
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

    fun isEncryptedBackup(uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val head = ByteArray(8)
                val n = input.read(head)
                n >= 4 && BackupCrypto.isEncrypted(head)
            } ?: false
        } catch (_: Exception) { false }
    }

    suspend fun importFromUri(uri: Uri, passphrase: String? = null): Result<Int> {
        return try {
            val raw = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes()
            } ?: return Result.failure(Exception("Could not open input stream"))

            val json = if (BackupCrypto.isEncrypted(raw)) {
                if (passphrase.isNullOrEmpty()) {
                    return Result.failure(Exception("This backup is encrypted. Enter the passphrase to import."))
                }
                try {
                    String(BackupCrypto.decrypt(raw, passphrase.toCharArray()), Charsets.UTF_8)
                } catch (e: Exception) {
                    return Result.failure(Exception("Wrong passphrase or corrupted backup."))
                }
            } else {
                String(raw, Charsets.UTF_8)
            }

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

            validateBackup(backup)?.let { return Result.failure(Exception(it)) }

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

// Backup payloads come from untrusted files (any .json picked via SAF). Gson
// is lenient and will silently fill fields with defaults, so we explicitly
// validate every entity before upserting — invalid type strings or non-finite
// amounts would corrupt the live DB. Returns null on success or the first
// problem encountered.
//
// We intentionally do NOT enforce FK integrity within the payload: category /
// account deletes on Android are soft-deletes that exclude the row from
// future exports, but existing transactions keep the now-dangling reference.
// Rejecting such backups would lock users out of re-importing their own data
// after any category/account cleanup. The downstream impact of a dangling id
// is purely cosmetic (the UI shows "(unknown)").
private val TYPE_VALUES = setOf("income", "expense")
private val DATE_PREFIX = Regex("^\\d{4}-\\d{2}-\\d{2}")

internal fun validateBackup(backup: BackupData): String? {
    val accountIds = HashSet<Long>(backup.accounts.size)
    backup.accounts.forEachIndexed { i, a ->
        if (a.id <= 0) return "accounts[$i]: invalid id ${a.id}"
        if (a.name.isBlank()) return "accounts[$i]: name is empty"
        if (a.currency.isBlank()) return "accounts[$i]: currency is empty"
        if (!a.balance.isFinite()) return "accounts[$i]: balance is not a finite number"
        if (!accountIds.add(a.id)) return "accounts[$i]: duplicate id ${a.id}"
    }

    val categoryIds = HashSet<Long>(backup.categories.size)
    backup.categories.forEachIndexed { i, c ->
        if (c.id <= 0) return "categories[$i]: invalid id ${c.id}"
        if (c.name.isBlank()) return "categories[$i]: name is empty"
        if (c.type !in TYPE_VALUES) return "categories[$i]: invalid type '${c.type}'"
        if (c.parentId != null && c.parentId <= 0) return "categories[$i]: invalid parent_id ${c.parentId}"
        if (!categoryIds.add(c.id)) return "categories[$i]: duplicate id ${c.id}"
    }

    backup.transactions.forEachIndexed { i, t ->
        if (t.id <= 0) return "transactions[$i]: invalid id ${t.id}"
        if (t.type !in TYPE_VALUES) return "transactions[$i]: invalid type '${t.type}'"
        if (!t.amount.isFinite() || t.amount <= 0) return "transactions[$i]: amount must be positive and finite"
        if (t.currency.isBlank()) return "transactions[$i]: currency is empty"
        if (!DATE_PREFIX.containsMatchIn(t.date)) return "transactions[$i]: unparseable date '${t.date}'"
    }

    backup.scheduledTransactions.forEachIndexed { i, s ->
        if (s.id <= 0) return "scheduled[$i]: invalid id ${s.id}"
        if (s.type !in TYPE_VALUES) return "scheduled[$i]: invalid type '${s.type}'"
        if (!s.amount.isFinite() || s.amount <= 0) return "scheduled[$i]: amount must be positive and finite"
        if (s.currency.isBlank()) return "scheduled[$i]: currency is empty"
        if (!s.rrule.startsWith("FREQ=")) return "scheduled[$i]: rrule must start with FREQ="
        if (!DATE_PREFIX.containsMatchIn(s.nextOccurrence)) return "scheduled[$i]: unparseable next_occurrence '${s.nextOccurrence}'"
    }

    return null
}
