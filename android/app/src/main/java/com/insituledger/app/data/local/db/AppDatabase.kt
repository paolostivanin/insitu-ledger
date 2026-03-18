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
    version = 2,
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
    }
}
