package com.insituledger.app.util

/**
 * Free-text limits shared by the transaction and scheduled forms. The backend
 * measures `len()` in UTF-8 bytes, and a 4xx parks the sync operation for
 * good, so the forms enforce the same byte limits up front.
 */
object FieldLimits {
    const val MAX_DESCRIPTION_BYTES = 500
    const val MAX_NOTE_BYTES = 2000

    const val MSG_DESCRIPTION_TOO_LONG = "Description is too long"
    const val MSG_NOTE_TOO_LONG = "Note is too long"

    fun utf8Length(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    fun descriptionError(description: String): String? =
        if (utf8Length(description) > MAX_DESCRIPTION_BYTES) MSG_DESCRIPTION_TOO_LONG else null

    fun noteError(note: String): String? =
        if (utf8Length(note) > MAX_NOTE_BYTES) MSG_NOTE_TOO_LONG else null
}
