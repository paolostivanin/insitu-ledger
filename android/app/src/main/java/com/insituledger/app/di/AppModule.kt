package com.insituledger.app.di

import android.content.Context
import androidx.room.Room
import com.insituledger.app.data.local.db.AppDatabase
import com.insituledger.app.data.local.db.DatabaseKeyProvider
import com.insituledger.app.data.local.db.PlaintextToCipherMigration
import com.insituledger.app.data.local.db.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        keyProvider: DatabaseKeyProvider,
    ): AppDatabase {
        val passphrase = keyProvider.getOrCreatePassphrase()
        PlaintextToCipherMigration.runIfNeeded(context, passphrase)
        val passphraseBytes = passphrase.toByteArray(Charsets.US_ASCII)
        val factory = SupportOpenHelperFactory(passphraseBytes)
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "insitu_ledger.db"
        )
            .openHelperFactory(factory)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6).build()
    }

    @Provides fun provideAccountDao(db: AppDatabase): AccountDao = db.accountDao()
    @Provides fun provideCategoryDao(db: AppDatabase): CategoryDao = db.categoryDao()
    @Provides fun provideTransactionDao(db: AppDatabase): TransactionDao = db.transactionDao()
    @Provides fun provideScheduledTransactionDao(db: AppDatabase): ScheduledTransactionDao = db.scheduledTransactionDao()
    @Provides fun providePendingOperationDao(db: AppDatabase): PendingOperationDao = db.pendingOperationDao()
}
