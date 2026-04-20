package com.insituledger.app.data.repository

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// Passphrase-encrypted backup file format. Backups commonly land in cloud
// storage (Drive, Dropbox) where plaintext finance data would be a soft leak.
//
// Layout (all big-endian):
//   magic:    4 bytes "ILBK"
//   version:  1 byte (currently 1)
//   salt:     16 bytes
//   iv:       12 bytes
//   payload:  AES-256-GCM ciphertext (includes 16-byte auth tag)
object BackupCrypto {
    private const val MAGIC = "ILBK"
    private const val VERSION: Byte = 1
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val GCM_TAG_BITS = 128
    private const val PBKDF2_ITER = 200_000
    private const val KEY_LEN_BITS = 256
    private const val HEADER_LEN = 4 + 1 + SALT_LEN + IV_LEN

    fun isEncrypted(bytes: ByteArray): Boolean {
        if (bytes.size < HEADER_LEN) return false
        return bytes[0] == 'I'.code.toByte() &&
            bytes[1] == 'L'.code.toByte() &&
            bytes[2] == 'B'.code.toByte() &&
            bytes[3] == 'K'.code.toByte()
    }

    fun encrypt(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        require(passphrase.isNotEmpty()) { "Passphrase must not be empty" }
        val rng = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rng.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { rng.nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(HEADER_LEN + ct.size).apply {
            put(MAGIC.toByteArray(Charsets.US_ASCII))
            put(VERSION)
            put(salt)
            put(iv)
            put(ct)
        }.array()
    }

    fun decrypt(blob: ByteArray, passphrase: CharArray): ByteArray {
        require(isEncrypted(blob)) { "Not an encrypted backup" }
        require(blob.size > HEADER_LEN) { "Truncated backup" }
        val version = blob[4]
        require(version == VERSION) { "Unsupported backup format version: $version" }
        val salt = blob.copyOfRange(5, 5 + SALT_LEN)
        val iv = blob.copyOfRange(5 + SALT_LEN, HEADER_LEN)
        val ct = blob.copyOfRange(HEADER_LEN, blob.size)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITER, KEY_LEN_BITS)
        try {
            val keyBytes = factory.generateSecret(spec).encoded
            return SecretKeySpec(keyBytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
