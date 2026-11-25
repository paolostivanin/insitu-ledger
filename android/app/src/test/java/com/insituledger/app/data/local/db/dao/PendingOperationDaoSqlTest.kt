package com.insituledger.app.data.local.db.dao

import android.database.Cursor
import androidx.room.RoomDatabase
import androidx.room.util.createCancellationSignal
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The push loop's ordering and reconciliation rest on these queries, and the
 * SyncRepository tests use an in-memory fake of them. This reads the SQL the
 * Room-generated DAO actually runs (no SQLite in JVM tests), so a change to
 * the @Query text that breaks an invariant fails here.
 */
class PendingOperationDaoSqlTest {
    private val db: RoomDatabase = mockk(relaxed = true)
    private val ran = mutableListOf<Pair<String, List<Any?>>>()

    private class RecordingProgram : SupportSQLiteProgram {
        val bound = mutableListOf<Any?>()
        private fun set(index: Int, value: Any?) {
            while (bound.size < index) bound.add(null)
            bound[index - 1] = value
        }
        override fun bindNull(index: Int) = set(index, null)
        override fun bindLong(index: Int, value: Long) = set(index, value)
        override fun bindDouble(index: Int, value: Double) = set(index, value)
        override fun bindString(index: Int, value: String) = set(index, value)
        override fun bindBlob(index: Int, value: ByteArray) = set(index, value)
        override fun clearBindings() = bound.clear()
        override fun close() {}
    }

    @Before fun setUp() {
        // DBUtil.kt's file facade; its CancellationSignal is an Android stub here.
        mockkStatic(DB_UTIL)
        every { createCancellationSignal() } returns null
        // Already in a transaction: CoroutinesRoom runs the query inline.
        every { db.isOpenInternal } returns true
        every { db.inTransaction() } returns true
        every { db.query(any<SupportSQLiteQuery>(), any()) } answers {
            val query = firstArg<SupportSQLiteQuery>()
            val program = RecordingProgram()
            query.bindTo(program)
            // Recorded now: the statement goes back to Room's pool afterwards.
            ran += query.sql.trimIndent().replace(Regex("\\s+"), " ").trim() to program.bound.toList()
            mockk<Cursor>(relaxed = true)
        }
    }

    @After fun tearDown() { unmockkStatic(DB_UTIL) }

    private fun dao() = PendingOperationDao_Impl(db)

    private companion object {
        const val DB_UTIL = "androidx.room.util.DBUtil"
    }

    @Test fun pushOrderIsInsertionOrderNotWallClock() = runTest {
        dao().getAll()

        val (sql, _) = ran.single()
        assertTrue(sql, sql.contains("WHERE state = 'pending'"))
        assertTrue(sql, sql.endsWith("ORDER BY id ASC"))
        assertFalse("created_at can go backwards: $sql", sql.contains("created_at"))
    }

    @Test fun fkRewriteLookupOnlySeesPendingOpsInQueueOrder() = runTest {
        dao().findByEntityTypeAndOperation("transaction", "CREATE")

        val (sql, args) = ran.single()
        assertTrue(sql, sql.contains("state = 'pending'"))
        assertTrue(sql, sql.endsWith("ORDER BY id ASC"))
        assertFalse(sql, sql.contains("created_at"))
        assertEquals(listOf("transaction", "CREATE"), args)
    }

    @Test fun olderOpsLookupMatchesThePushLoopsEntityKey() = runTest {
        dao().getOlderOpsForEntity("transaction", 42L, 7L)

        val (sql, args) = ran.single()
        assertEquals(
            "SELECT * FROM pending_operations WHERE entity_type = ? AND COALESCE(server_id, entity_id) = ? " +
                "AND id < ? AND operation != 'CREATE' ORDER BY id ASC",
            sql
        )
        // Any state: failed ops are exactly the ones a retry could replay.
        assertFalse(sql, sql.contains("state"))
        assertEquals(listOf("transaction", 42L, 7L), args)
    }

    @Test fun minEntityIdCountsEveryQueuedOp() = runTest {
        dao().getMinEntityId("scheduled")

        val (sql, args) = ran.single()
        assertEquals("SELECT MIN(entity_id) FROM pending_operations WHERE entity_type = ?", sql)
        assertEquals(listOf("scheduled"), args)
    }
}
