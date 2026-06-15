package com.insituledger.app.data.repository

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidateBackupTest {

    private fun acct(id: Long = 1) = AccountBackup(id, "Wallet", "EUR", 0.0, "2026-01-01T00:00", "2026-01-01T00:00")
    private fun cat(id: Long = 1, parentId: Long? = null) =
        CategoryBackup(id, parentId, "Food", "expense", null, null, "2026-01-01T00:00", "2026-01-01T00:00")
    private fun tx(id: Long = 1, accountId: Long = 1, categoryId: Long = 1, amount: Double = 5.0, type: String = "expense", date: String = "2026-01-01T12:00") =
        TransactionBackup(id, accountId, categoryId, type, amount, "EUR", "Lunch", null, date, "2026-01-01T00:00", "2026-01-01T00:00")
    private fun sched(id: Long = 1, accountId: Long = 1, categoryId: Long = 1, rrule: String = "FREQ=MONTHLY") =
        ScheduledBackup(id, accountId, categoryId, "expense", 10.0, "EUR", "Rent", null, rrule, "2026-02-01T09:00", true, null, 0, "2026-01-01T00:00", "2026-01-01T00:00")

    private fun backup(
        accounts: List<AccountBackup> = listOf(acct()),
        categories: List<CategoryBackup> = listOf(cat()),
        transactions: List<TransactionBackup> = listOf(tx()),
        scheduled: List<ScheduledBackup> = listOf(sched()),
    ) = BackupData(BACKUP_SCHEMA_VERSION, accounts, categories, transactions, scheduled)

    @Test fun `valid payload returns null`() {
        assertNull(validateBackup(backup()))
    }

    @Test fun `empty payload is valid (nothing to corrupt)`() {
        assertNull(validateBackup(backup(emptyList(), emptyList(), emptyList(), emptyList())))
    }

    // FK integrity is intentionally NOT enforced — soft-deleting a category or
    // account on Android leaves live transactions referencing a now-absent id,
    // so a legitimate self-generated backup will contain dangling references
    // after any reorganization. Importing must accept these.
    @Test fun `transaction with dangling account_id is accepted (soft-delete leak)`() {
        assertNull(validateBackup(backup(transactions = listOf(tx(accountId = 999)))))
    }

    @Test fun `transaction with dangling category_id is accepted (soft-delete leak)`() {
        assertNull(validateBackup(backup(transactions = listOf(tx(categoryId = 999)))))
    }

    @Test fun `non-finite amount is rejected`() {
        val result = validateBackup(backup(transactions = listOf(tx(amount = Double.NaN))))
        assertNotNull(result)
        assertTrue(result!!, result.contains("finite"))
    }

    @Test fun `zero amount is rejected`() {
        val result = validateBackup(backup(transactions = listOf(tx(amount = 0.0))))
        assertNotNull(result)
        assertTrue(result!!, result.contains("positive"))
    }

    @Test fun `negative amount is rejected`() {
        val result = validateBackup(backup(transactions = listOf(tx(amount = -1.0))))
        assertNotNull(result)
    }

    @Test fun `unknown type string is rejected`() {
        val result = validateBackup(backup(transactions = listOf(tx(type = "transfer"))))
        assertNotNull(result)
        assertTrue(result!!, result.contains("invalid type"))
    }

    @Test fun `non-date date string is rejected`() {
        val result = validateBackup(backup(transactions = listOf(tx(date = "yesterday"))))
        assertNotNull(result)
        assertTrue(result!!, result.contains("unparseable date"))
    }

    @Test fun `duplicate account id is rejected`() {
        val result = validateBackup(backup(accounts = listOf(acct(1), acct(1))))
        assertNotNull(result)
        assertTrue(result!!, result.contains("duplicate"))
    }

    @Test fun `scheduled with invalid rrule is rejected`() {
        val result = validateBackup(backup(scheduled = listOf(sched(rrule = "MONTHLY"))))
        assertNotNull(result)
        assertTrue(result!!, result.contains("FREQ="))
    }

    @Test fun `category with dangling parent_id is accepted (soft-delete leak)`() {
        assertNull(validateBackup(backup(categories = listOf(cat(1, parentId = 999)))))
    }

    @Test fun `category with valid parent passes`() {
        val parent = cat(id = 1)
        val child = cat(id = 2, parentId = 1)
        assertNull(validateBackup(backup(categories = listOf(parent, child))))
    }

    // Tier B B3: dup-ID rejection extended to transactions and scheduled.
    // Without these, two rows with the same id would silently collapse to a
    // single upsert and lose the second payload's data.
    @Test fun `duplicate transaction id is rejected`() {
        val result = validateBackup(backup(transactions = listOf(tx(id = 1), tx(id = 1))))
        assertNotNull(result)
        assertTrue(result!!, result.contains("duplicate"))
    }

    @Test fun `duplicate scheduled id is rejected`() {
        val result = validateBackup(backup(scheduled = listOf(sched(id = 1), sched(id = 1))))
        assertNotNull(result)
        assertTrue(result!!, result.contains("duplicate"))
    }

    @Test fun `duplicate category id remains rejected`() {
        val result = validateBackup(backup(categories = listOf(cat(1), cat(1))))
        assertNotNull(result)
        assertTrue(result!!, result.contains("duplicate"))
    }
}
