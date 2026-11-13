package com.insituledger.app.ui.common

import com.insituledger.app.ui.reports.DateRangePreset
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Sentence-case label for a reporting period preset. */
fun DateRangePreset.label(): String = when (this) {
    DateRangePreset.THIS_WEEK -> "This week"
    DateRangePreset.THIS_MONTH -> "This month"
    DateRangePreset.THIS_YEAR -> "This year"
    DateRangePreset.LAST_WEEK -> "Last week"
    DateRangePreset.LAST_MONTH -> "Last month"
    DateRangePreset.LAST_3_MONTHS -> "Last 3 months"
    DateRangePreset.LAST_YEAR -> "Last year"
    DateRangePreset.ALL_TIME -> "All time"
    DateRangePreset.CUSTOM -> "Custom range"
}

/** "1 Nov – 13 Nov" for an inclusive "yyyy-MM-dd" range; open ends read "…". */
fun rangeLabel(from: String?, to: String?): String {
    val f = from?.let { DateLabels.parseDay(it) }
    val t = to?.let { DateLabels.parseDay(it) }
    return when {
        f != null && t != null -> "${DateLabels.short(f)} – ${DateLabels.short(t)}"
        f != null -> "From ${DateLabels.short(f)}"
        t != null -> "Until ${DateLabels.short(t)}"
        else -> "Any time"
    }
}

/** Material date pickers report UTC-midnight millis. */
fun utcMillisToLocalDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
