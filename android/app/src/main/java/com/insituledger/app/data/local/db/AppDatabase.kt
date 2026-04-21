package com.insituledger.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.insituledger.app.data.local.db.converter.Converters
import com.insituledger.app.data.local.db.dao.*
import com.insituledger.app.data.local.db.entity.*

@Database(
    entities = [
        AccountEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
        ScheduledTransactionEntity::class,
        PendingOperationEntity::class
    ],
    version = 7,
    exportSchema = true
)
@androidx.room.TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun scheduledTransactionDao(): ScheduledTransactionDao
    abstract fun pendingOperationDao(): PendingOperationDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scheduled_transactions ADD COLUMN max_occurrences INTEGER")
                db.execSQL("ALTER TABLE scheduled_transactions ADD COLUMN occurrence_count INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_date ON transactions(date)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_category_id ON transactions(category_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_account_id ON transactions(account_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_deleted_at ON transactions(deleted_at)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_categories_type ON categories(type)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_deleted_date ON transactions(deleted_at, date)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_deleted_category ON transactions(deleted_at, category_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_categories_deleted_at ON categories(deleted_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_accounts_deleted_at ON accounts(deleted_at)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN note TEXT")
                db.execSQL("ALTER TABLE scheduled_transactions ADD COLUMN note TEXT")
            }
        }

        // v1.24.0 (server v1.15.0) — add per-row attribution + cached owner
        // metadata for shared accounts. created_by_user_id is the actual creator
        // (kept sticky on edit); owner_name and is_shared are cached on accounts
        // to power the "Shared by [name]" badge offline.
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN created_by_user_id INTEGER")
                db.execSQL("ALTER TABLE scheduled_transactions ADD COLUMN created_by_user_id INTEGER")
                db.execSQL("UPDATE transactions SET created_by_user_id = user_id WHERE created_by_user_id IS NULL")
                db.execSQL("UPDATE scheduled_transactions SET created_by_user_id = user_id WHERE created_by_user_id IS NULL")
                db.execSQL("ALTER TABLE accounts ADD COLUMN owner_name TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE accounts ADD COLUMN is_shared INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
