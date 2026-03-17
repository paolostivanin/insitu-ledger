package com.insituledger.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
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
    version = 1,
    exportSchema = true
)
@androidx.room.TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun scheduledTransactionDao(): ScheduledTransactionDao
    abstract fun pendingOperationDao(): PendingOperationDao
}
