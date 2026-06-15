package com.insituledger.app.data.sync

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.OutputStream
import java.time.LocalDate
import java.time.temporal.IsoFields
import javax.inject.Inject

internal interface BackupFile {
    val name: String?
    fun openOutputStream(): OutputStream?
    fun renameTo(displayName: String): Boolean
    fun delete(): Boolean
}

internal interface BackupFolder {
    fun listFiles(): List<BackupFile>
    fun createFile(mime: String, displayName: String): BackupFile?
}

internal class DocumentBackupFolder(
    private val context: Context,
    private val folder: DocumentFile
) : BackupFolder {
    override fun listFiles(): List<BackupFile> =
        folder.listFiles().map { DocumentBackupFile(context, it) }

    override fun createFile(mime: String, displayName: String): BackupFile? =
        folder.createFile(mime, displayName)?.let { DocumentBackupFile(context, it) }
}

private class DocumentBackupFile(
    private val context: Context,
    private val file: DocumentFile
) : BackupFile {
    override val name: String? get() = file.name
    override fun openOutputStream(): OutputStream? = context.contentResolver.openOutputStream(file.uri)
    override fun renameTo(displayName: String): Boolean = file.renameTo(displayName)
    override fun delete(): Boolean = file.delete()
}

internal object AutoBackupNaming {
    fun weeklyFileName(today: LocalDate, runStamp: String, ext: String): String {
        val weekYear = today.get(IsoFields.WEEK_BASED_YEAR)
        val week = today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR).toString().padStart(2, '0')
        return "insitu-backup-weekly-$weekYear-W$week-T$runStamp.$ext"
    }

    fun namesToDelete(names: List<String>, prefix: String, retention: Int): List<String> =
        names
            .filter { it.startsWith(prefix) && (it.endsWith(".json") || it.endsWith(".ilbk")) }
            .sortedDescending()
            .drop(retention.coerceAtLeast(0))
}

class AutoBackupFileWriter @Inject constructor() {
    companion object {
        private const val TAG = "AutoBackupFileWriter"
    }

    internal fun writeAndCleanup(
        folder: BackupFolder,
        prefix: String,
        fileName: String,
        payload: ByteArray,
        mime: String,
        retention: Int
    ): Boolean {
        if (!writeBackup(folder, prefix, fileName, payload, mime)) return false
        cleanupOldBackups(folder, prefix, retention)
        return true
    }

    internal fun writeBackup(
        folder: BackupFolder,
        prefix: String,
        fileName: String,
        payload: ByteArray,
        mime: String
    ): Boolean {
        sweepTmpOrphans(folder, prefix)

        val tmpName = "$fileName.tmp"
        val tmpFile = try {
            folder.createFile(mime, tmpName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create temp file: $tmpName", e)
            return false
        } ?: return false

        val wrote = try {
            tmpFile.openOutputStream().use { out ->
                if (out == null) {
                    false
                } else {
                    out.write(payload)
                    out.flush()
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write temp backup: $tmpName", e)
            false
        }

        if (!wrote) {
            runCatching { tmpFile.delete() }
            return false
        }

        return try {
            tmpFile.renameTo(fileName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finalize backup: $fileName", e)
            false
        }
    }

    internal fun cleanupOldBackups(folder: BackupFolder, prefix: String, retention: Int) {
        try {
            val filesByName = folder.listFiles().mapNotNull { file ->
                file.name?.let { it to file }
            }.toMap()
            AutoBackupNaming.namesToDelete(filesByName.keys.toList(), prefix, retention).forEach { name ->
                filesByName[name]?.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup backups for prefix: $prefix", e)
        }
    }

    private fun sweepTmpOrphans(folder: BackupFolder, prefix: String) {
        runCatching {
            folder.listFiles()
                .filter { it.name?.startsWith(prefix) == true && it.name?.endsWith(".tmp") == true }
                .forEach { runCatching { it.delete() } }
        }
    }
}
