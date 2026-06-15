package com.insituledger.app.data.sync

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.LocalDate

class AutoBackupFileWriterTest {
    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private class FakeFile(
        override var name: String?,
        private val canOpen: Boolean = true,
        private val renameSucceeds: Boolean = true,
        private val writeThrows: Boolean = false
    ) : BackupFile {
        var deleted = false
        val bytes = ByteArrayOutputStream()

        override fun openOutputStream(): OutputStream? = when {
            !canOpen -> null
            writeThrows -> object : OutputStream() {
                override fun write(b: Int) = throw IllegalStateException("simulated write failure")
            }
            else -> bytes
        }
        override fun renameTo(displayName: String): Boolean {
            if (renameSucceeds) name = displayName
            return renameSucceeds
        }
        override fun delete(): Boolean {
            deleted = true
            return true
        }
    }

    private class FakeFolder(
        val files: MutableList<FakeFile> = mutableListOf(),
        private val createdFile: FakeFile? = FakeFile("tmp")
    ) : BackupFolder {
        override fun listFiles(): List<BackupFile> = files.toList()
        override fun createFile(mime: String, displayName: String): BackupFile? =
            createdFile?.also {
                it.name = displayName
                files.add(it)
            }
    }

    @Test
    fun weeklyNameUsesIsoWeekBasedYear() {
        assertEquals(
            "insitu-backup-weekly-2026-W01-T030000.json",
            AutoBackupNaming.weeklyFileName(LocalDate.of(2025, 12, 29), "030000", "json")
        )
    }

    @Test
    fun renameFailurePreservesPreviousBackupsAndSkipsRetention() {
        val previous = FakeFile("insitu-backup-daily-2026-06-14-T030000.json")
        val oldest = FakeFile("insitu-backup-daily-2026-06-13-T030000.json")
        val folder = FakeFolder(mutableListOf(oldest, previous), FakeFile("tmp", renameSucceeds = false))

        val result = AutoBackupFileWriter().writeAndCleanup(
            folder, "insitu-backup-daily-", "insitu-backup-daily-2026-06-15-T030000.json",
            "payload".toByteArray(), "application/json", retention = 1
        )

        assertFalse(result)
        assertFalse(previous.deleted)
        assertFalse(oldest.deleted)
    }

    @Test
    fun openFailurePreservesPreviousBackup() {
        val previous = FakeFile("insitu-backup-daily-2026-06-14-T030000.json")
        val folder = FakeFolder(mutableListOf(previous), FakeFile("tmp", canOpen = false))

        val result = AutoBackupFileWriter().writeBackup(
            folder, "insitu-backup-daily-", "insitu-backup-daily-2026-06-15-T030000.json",
            "payload".toByteArray(), "application/json"
        )

        assertFalse(result)
        assertFalse(previous.deleted)
    }

    @Test
    fun createFailurePreservesPreviousBackup() {
        val previous = FakeFile("insitu-backup-daily-2026-06-14-T030000.json")
        val folder = FakeFolder(mutableListOf(previous), createdFile = null)

        val result = AutoBackupFileWriter().writeBackup(
            folder, "insitu-backup-daily-", "insitu-backup-daily-2026-06-15-T030000.json",
            "payload".toByteArray(), "application/json"
        )

        assertFalse(result)
        assertFalse(previous.deleted)
    }

    @Test
    fun writeFailurePreservesPreviousBackup() {
        val previous = FakeFile("insitu-backup-daily-2026-06-14-T030000.json")
        val folder = FakeFolder(mutableListOf(previous), FakeFile("tmp", writeThrows = true))

        val result = AutoBackupFileWriter().writeBackup(
            folder, "insitu-backup-daily-", "insitu-backup-daily-2026-06-15-T030000.json",
            "payload".toByteArray(), "application/json"
        )

        assertFalse(result)
        assertFalse(previous.deleted)
    }

    @Test
    fun retentionDeletesOnlyOldestMatchingBackups() {
        val newest = FakeFile("insitu-backup-daily-2026-06-15-T030000.json")
        val middle = FakeFile("insitu-backup-daily-2026-06-14-T030000.json")
        val oldest = FakeFile("insitu-backup-daily-2026-06-13-T030000.json")
        val unrelated = FakeFile("notes.json")
        val folder = FakeFolder(mutableListOf(oldest, unrelated, newest, middle))

        AutoBackupFileWriter().cleanupOldBackups(folder, "insitu-backup-daily-", retention = 2)

        assertTrue(oldest.deleted)
        assertFalse(middle.deleted)
        assertFalse(newest.deleted)
        assertFalse(unrelated.deleted)
    }
}
