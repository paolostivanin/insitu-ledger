package com.insituledger.app.data.local.db

import android.content.Context
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * One-time migration: re-encrypts the existing plaintext Room SQLite file with SQLCipher.
 *
 * Runs synchronously during the first DB provide. Detection is by inspecting the file
 * header — plaintext SQLite starts with "SQLite format 3\0", anything else is treated
 * as already-encrypted (or missing) and the migration is a no-op.
 *
 * Strategy: open the plaintext file via SQLCipher with no key, ATTACH a fresh encrypted
 * file, run sqlcipher_export(), preserve user_version, then atomically replace the
 * plaintext file with the encrypted one. WAL/SHM sidecars are deleted so SQLCipher
 * starts clean.
 */
object PlaintextToCipherMigration {
    private const val TAG = "PlaintextMig"
    private const val DB_NAME = "insitu_ledger.db"
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    fun runIfNeeded(context: Context, passphrase: String) {
        val plaintextFile = context.getDatabasePath(DB_NAME)
        if (!plaintextFile.exists()) return
        if (!isPlaintextSqlite(plaintextFile)) return

        val parent = plaintextFile.parentFile
            ?: throw IllegalStateException("DB file has no parent directory")
        val encryptedFile = File(parent, "$DB_NAME.enc")
        val backupFile = File(parent, "$DB_NAME.bak")
        if (encryptedFile.exists()) encryptedFile.delete()
        if (backupFile.exists()) backupFile.delete()

        // 1. Pre-create the encrypted target file via SQLCipher so its header is
        //    initialized with the key. ATTACH-with-KEY against a non-existent file
        //    fails with SQLITE_CANTOPEN on this build of SQLCipher.
        val passphraseBytes = passphrase.toByteArray(Charsets.US_ASCII)
        try {
            val seed = SQLiteDatabase.openOrCreateDatabase(
                encryptedFile,
                passphraseBytes,
                null as SQLiteDatabase.CursorFactory?,
                null,
            )
            seed.close()
        } catch (t: Throwable) {
            encryptedFile.delete()
            throw t
        }

        // 2. Open the plaintext file via SQLCipher WITHOUT keying main, then ATTACH
        //    the now-existing encrypted DB and run sqlcipher_export.
        val plain = SQLiteDatabase.openDatabase(
            plaintextFile.absolutePath,
            null as SQLiteDatabase.CursorFactory?,
            SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            val userVersion = plain.version
            // Base64 alphabet contains no single quotes or backslashes, so direct
            // SQL interpolation is safe; SQLCipher does not allow parameter binding
            // for ATTACH KEY clauses.
            plain.execSQL(
                "ATTACH DATABASE '${encryptedFile.absolutePath}' AS encrypted KEY '$passphrase'"
            )
            // sqlcipher_export() returns a result row, so it must be invoked via rawQuery
            // (execSQL refuses any statement that produces rows).
            plain.rawQuery("SELECT sqlcipher_export('encrypted')", emptyArray()).use { c ->
                while (c.moveToNext()) { /* drain */ }
            }
            plain.execSQL("PRAGMA encrypted.user_version = $userVersion")
            plain.execSQL("DETACH DATABASE encrypted")
        } catch (t: Throwable) {
            try { plain.close() } catch (_: Throwable) {}
            encryptedFile.delete()
            throw t
        }
        plain.close()

        // Swap files. Rename original to .bak first; only delete .bak after success.
        if (!plaintextFile.renameTo(backupFile)) {
            encryptedFile.delete()
            throw IllegalStateException("Failed to rename plaintext DB to backup")
        }
        if (!encryptedFile.renameTo(plaintextFile)) {
            // Restore original; do not leave the user without a DB.
            backupFile.renameTo(plaintextFile)
            throw IllegalStateException("Failed to install encrypted DB; plaintext restored")
        }
        backupFile.delete()
        File(parent, "$DB_NAME-wal").delete()
        File(parent, "$DB_NAME-shm").delete()

        Log.i(TAG, "Migrated plaintext Room DB to SQLCipher")
    }

    private fun isPlaintextSqlite(file: File): Boolean {
        if (file.length() < SQLITE_HEADER.size) return false
        val header = ByteArray(SQLITE_HEADER.size)
        file.inputStream().use { stream ->
            var off = 0
            while (off < header.size) {
                val n = stream.read(header, off, header.size - off)
                if (n < 0) return false
                off += n
            }
        }
        return header.contentEquals(SQLITE_HEADER)
    }
}
