package com.insituledger.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.dao.AccountDao
import com.insituledger.app.data.local.db.dao.TransactionDao
import com.insituledger.app.data.local.db.entity.AccountEntity
import com.insituledger.app.data.local.db.entity.TransactionEntity
import com.insituledger.app.data.repository.AccountRepository
import com.insituledger.app.data.repository.CategoryRepository
import com.insituledger.app.data.repository.ScheduledRepository
import com.insituledger.app.data.repository.TransactionRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/**
 * Debug-only synthetic data for visual QA (not compiled into release builds).
 *
 *   adb shell am broadcast -n com.insituledger.app/.debug.DebugSeedReceiver --es profile typical
 *   adb shell am broadcast -n com.insituledger.app/.debug.DebugSeedReceiver --es profile stress
 *
 * Dates are relative to now so "Today"/"Yesterday" headers stay meaningful.
 * For the empty profile, clear app data instead (adb shell pm clear …).
 *
 * QA-only preference switches (no data written):
 *   … --es profile prefs --ez secure false --es theme dark|light|system --es hero net_worth|month_net
 */
class DebugSeedReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SeedEntryPoint {
        fun accounts(): AccountRepository
        fun categories(): CategoryRepository
        fun transactions(): TransactionRepository
        fun scheduled(): ScheduledRepository
        fun accountDao(): AccountDao
        fun transactionDao(): TransactionDao
        fun prefs(): UserPreferences
    }

    override fun onReceive(context: Context, intent: Intent) {
        val profile = intent.getStringExtra("profile") ?: "typical"
        val ep = EntryPointAccessors.fromApplication(context.applicationContext, SeedEntryPoint::class.java)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Screenshots of a FLAG_SECURE window come out black.
                if (intent.hasExtra("secure")) ep.prefs().saveScreenSecure(intent.getBooleanExtra("secure", true))
                intent.getStringExtra("theme")?.let { ep.prefs().saveThemeMode(it) }
                intent.getStringExtra("hero")?.let { ep.prefs().saveDashboardHeroMode(it) }
                if (profile != "prefs") Seeder(ep).run(profile)
                Log.i(TAG, "Seeded profile '$profile'")
            } catch (e: Exception) {
                Log.e(TAG, "Seeding failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private class Seeder(private val ep: SeedEntryPoint) {
        private val zone = ZoneId.systemDefault()
        private val rnd = Random(42)

        private fun at(daysAgo: Long, hour: Int, minute: Int): String =
            ZonedDateTime.now(zone).minusDays(daysAgo)
                .withHour(hour).withMinute(minute).truncatedTo(ChronoUnit.MINUTES)
                .toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        suspend fun run(profile: String) {
            val accounts = ep.accounts()
            val categories = ep.categories()
            val transactions = ep.transactions()

            val checking = accounts.create("Checking", "EUR", 2_450.0)
            val savings = accounts.create("Savings", "EUR", 12_000.0)
            val travel = accounts.create("Travel card", "USD", 320.0)

            val food = categories.create("Food", "expense", null, "🍽️", "#E65100")
            val groceries = categories.create("Groceries", "expense", food, "🛒", null)
            val dining = categories.create("Restaurants", "expense", food, null, null)
            val coffee = categories.create("Coffee", "expense", food, "☕", null)
            val home = categories.create("Home", "expense", null, "🏠", "#1565C0")
            val rent = categories.create("Rent", "expense", home, null, null)
            val utilities = categories.create("Utilities", "expense", home, null, null)
            val transport = categories.create("Transport", "expense", null, "🚆", "#2E7D32")
            val subscriptions = categories.create("Subscriptions", "expense", null, null, "#6A1B9A")
            val health = categories.create("Health", "expense", null, null, "#C62828")
            val salary = categories.create("Salary", "income", null, "💼", "#00695C")
            val refunds = categories.create("Refunds", "income", null, null, "#455A64")

            data class Spend(val desc: String, val cat: Long, val min: Double, val max: Double, val account: Long = checking)
            val spends = listOf(
                Spend("Esselunga", groceries, 18.0, 96.0),
                Spend("Carrefour Express", groceries, 6.0, 34.0),
                Spend("Trattoria da Mario", dining, 24.0, 68.0),
                Spend("Bar Centrale", coffee, 1.2, 4.5),
                Spend("Trenitalia", transport, 9.9, 49.0),
                Spend("ATM monthly pass", transport, 39.0, 39.0),
                Spend("Pharmacy", health, 6.5, 28.0),
                Spend("Starbucks JFK", coffee, 5.0, 9.0, travel)
            )

            // ~60 entries over two months, newest first.
            var day = 0L
            var count = 0
            while (count < 60 && day < 62) {
                val perDay = rnd.nextInt(0, 3)
                repeat(perDay) {
                    val s = spends[rnd.nextInt(spends.size)]
                    val amount = (s.min + rnd.nextDouble() * (s.max - s.min)).let { Math.round(it * 100) / 100.0 }
                    val currency = if (s.account == travel) "USD" else "EUR"
                    transactions.create(s.account, s.cat, "expense", amount, currency, s.desc, null,
                        at(day, rnd.nextInt(8, 21), rnd.nextInt(0, 60)))
                    count++
                }
                day++
            }
            listOf(1L, 31L).forEach { d ->
                transactions.create(checking, salary, "income", 2_850.0, "EUR", "Salary", null, at(d, 9, 0))
                transactions.create(checking, rent, "expense", 950.0, "EUR", "Rent", "Via Roma 12", at(d, 10, 0))
                transactions.create(checking, utilities, "expense", 74.30, "EUR", "Electricity", null, at(d + 3, 12, 15))
            }
            transactions.create(checking, refunds, "income", 23.99, "EUR", "Amazon refund", null, at(4, 16, 40))
            transactions.create(savings, salary, "income", 300.0, "EUR", "Monthly saving", null, at(2, 8, 0))

            val scheduled = ep.scheduled()
            scheduled.create(checking, rent, "expense", 950.0, "EUR", "Rent", null, "FREQ=MONTHLY", at(-17, 10, 0))
            scheduled.create(checking, subscriptions, "expense", 12.99, "EUR", "Netflix", null, "FREQ=MONTHLY", at(-5, 9, 0), 12)
            scheduled.create(checking, subscriptions, "expense", 4.99, "EUR", "iCloud", null, "FREQ=MONTHLY", at(-22, 9, 0))
            scheduled.create(checking, health, "expense", 85.0, "EUR", "Dentist", null, "FREQ=DAILY", at(-3, 15, 30), 1)
            scheduled.create(checking, salary, "income", 2_850.0, "EUR", "Salary", null, "FREQ=MONTHLY", at(-29, 9, 0))

            if (profile == "stress") seedStress(checking, groceries, salary)
        }

        private suspend fun seedStress(checking: Long, groceries: Long, salary: Long) {
            val accounts = ep.accounts()
            val transactions = ep.transactions()
            repeat(8) { i -> accounts.create("Account ${i + 1} with a rather long descriptive name", "EUR", 100.0 * (i + 1)) }
            val overdrawn = accounts.create("Credit card", "EUR", 0.0)
            transactions.create(overdrawn, groceries, "expense", 1_843.27, "EUR", "Furniture", null, at(0, 11, 5))
            transactions.create(checking, salary, "income", 1_234_567_890.12, "EUR", "Lottery jackpot", null, at(0, 12, 0))
            transactions.create(
                checking, groceries, "expense", 1_234_567.89, "EUR",
                "An unusually long description that keeps going to check ellipsis and wrapping behaviour in narrow rows",
                "A note", at(0, 13, 30)
            )
            // A co-owned account with entries attributed to someone else.
            val shared = -9_001L
            ep.accountDao().upsert(
                AccountEntity(id = shared, userId = 7, name = "Family", currency = "EUR", balance = 640.0,
                    isLocalOnly = false, ownerName = "Anna", isShared = true)
            )
            ep.transactionDao().upsert(
                TransactionEntity(id = -9_002L, accountId = shared, categoryId = groceries, userId = 7,
                    type = "expense", amount = 56.40, currency = "EUR", description = "Weekly shop",
                    date = at(0, 9, 45), createdByUserId = 7, createdByName = "Anna")
            )
        }
    }

    private companion object {
        const val TAG = "DebugSeed"
    }
}
