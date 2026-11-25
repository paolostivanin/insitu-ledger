package com.insituledger.app.util

/**
 * The server's version as reported by GET /api/health, compared by numeric
 * semver parts ("1.27.0" < "1.27.1" < "1.28" < "2"). A leading "v" and any
 * pre-release / build suffix ("1.27.0-rc1", "1.27.0+abc") are ignored.
 */
object ServerVersion {
    /** Stored when /api/health answers without a version: a server older than 1.27. */
    const val OLD = "0"

    /** Numeric parts, or null when [version] doesn't start with a number. */
    fun parse(version: String): List<Int>? {
        val core = version.trim().removePrefix("v").substringBefore('-').substringBefore('+')
        if (core.isEmpty()) return null
        val parts = core.split('.').map { part ->
            part.takeWhile { it.isDigit() }.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: return null
        }
        return parts
    }

    /** [version] >= [minimum]. An unparsable version never qualifies. */
    fun atLeast(version: String, minimum: String): Boolean {
        val have = parse(version) ?: return false
        val want = parse(minimum) ?: return false
        for (i in 0 until maxOf(have.size, want.size)) {
            val a = have.getOrElse(i) { 0 }
            val b = want.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return true
    }
}
