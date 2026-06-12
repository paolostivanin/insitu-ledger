package com.insituledger.app.data.local.db.converter

import androidx.room.TypeConverter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// NOTE: do not type date columns as LocalDateTime in entities. Since v1.28.0
// the wire/storage format is RFC3339 with offset ("2026-06-11T08:41:00+02:00");
// toLocalDateTime below would crash on the offset suffix. Date columns are
// String-typed everywhere (these converters are currently unused dead code) —
// keep it that way.
class Converters {
    private val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    @TypeConverter
    fun fromLocalDateTime(value: LocalDateTime?): String? = value?.format(dateTimeFormatter)

    @TypeConverter
    fun toLocalDateTime(value: String?): LocalDateTime? = value?.let {
        LocalDateTime.parse(it.replace(" ", "T"), dateTimeFormatter)
    }

    @TypeConverter
    fun fromLocalDate(value: LocalDate?): String? = value?.format(dateFormatter)

    @TypeConverter
    fun toLocalDate(value: String?): LocalDate? = value?.let {
        LocalDate.parse(it.take(10), dateFormatter)
    }
}
