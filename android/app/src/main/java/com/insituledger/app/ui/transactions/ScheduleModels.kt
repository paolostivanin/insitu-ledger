package com.insituledger.app.ui.transactions

import com.insituledger.app.util.DateTimeUtil
import com.insituledger.app.util.RecurrenceDraft
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The two halves of the schedule sheet. */
enum class ScheduleMode { ONE_TIME, RECURRING }

/** One-tap dates in the sheet's One-time section (same time of day). */
enum class QuickPick { TOMORROW, IN_A_WEEK, IN_A_MONTH }

/**
 * The open schedule sheet. Derived fields ([error], [upcoming],
 * [pastEntries], [endsDuringCatchUp], [dstAdjusted]) are recomputed by the
 * ViewModel on every change, with strict validation against the clock.
 */
data class ScheduleSheetState(
    val mode: ScheduleMode,
    val date: LocalDate,
    val time: LocalTime,
    val draft: RecurrenceDraft = RecurrenceDraft(),
    /** Field-specific message; Done is disabled while non-null. */
    val error: String? = null,
    /** One-time: the chosen moment. Recurring: the next (up to 3) entries after now. */
    val upcoming: List<LocalDateTime> = emptyList(),
    /** Recurring with a past start: the entries the scheduler will create right away. */
    val pastEntries: List<LocalDateTime> = emptyList(),
    /** COUNT or UNTIL runs out within [pastEntries]: "…then ends". */
    val endsDuringCatchUp: Boolean = false,
    /** A schedule (or a future date) is already set on the form: offer "Remove schedule". */
    val isEditingExisting: Boolean = false,
    /** [time] doesn't exist on [date] (DST gap) and will shift forward. */
    val dstAdjusted: Boolean = false
)

/** What the primary button does; see [formMode]. */
enum class FormMode { ADD, SCHEDULE_ONCE, ADD_AND_REPEAT, SCHEDULE_RECURRING, EDIT }

/** Primary button label for [type] ("expense" / "income"). */
fun FormMode.primaryLabel(type: String): String {
    val noun = if (type == "income") "income" else "expense"
    return when (this) {
        FormMode.ADD -> "Add $noun"
        FormMode.SCHEDULE_ONCE, FormMode.SCHEDULE_RECURRING -> "Schedule $noun"
        FormMode.ADD_AND_REPEAT -> "Add & repeat"
        FormMode.EDIT -> "Save changes"
    }
}

/**
 * Pure: what saving [state] at [now] would do. The composable recomputes it
 * from a once-a-minute ticker, so a one-time schedule that slips into the past
 * relabels itself without the ViewModel polling.
 */
fun formMode(state: TransactionFormUiState, now: OffsetDateTime): FormMode {
    if (state.id != null) return FormMode.EDIT
    val date = DateTimeUtil.parseFormDateOrNull(state.date, now.offset)
    val future = date != null && date.isAfter(now)
    return when {
        state.recurrence != null -> if (future) FormMode.SCHEDULE_RECURRING else FormMode.ADD_AND_REPEAT
        state.explicitOnce || future -> FormMode.SCHEDULE_ONCE
        else -> FormMode.ADD
    }
}

/** Result of a successful save, shown by the caller (it outlives the form). */
sealed interface SaveOutcome {
    val message: String

    data class Created(val type: String) : SaveOutcome {
        override val message: String
            get() = if (type == "income") "Income added" else "Expense added"
    }

    data object Updated : SaveOutcome {
        override val message: String get() = "Transaction updated"
    }

    /** "Scheduled for Fri, 14 Nov at 09:00". */
    data class ScheduledOnce(val at: LocalDateTime) : SaveOutcome {
        override val message: String
            get() = "Scheduled for ${at.format(DAY_FORMAT)} at ${at.format(TIME_FORMAT)}"
    }

    /**
     * "Repeats monthly, first entry added" when the start is due (with
     * [pastEntries] > 1: "Repeats weekly, 2 entries added"), otherwise
     * "Repeats monthly from Fri, 20 Nov". [summary] is the frequency label,
     * e.g. "Monthly" or "Every 3 weeks".
     */
    data class ScheduledRecurring(
        val summary: String,
        val startsNow: Boolean,
        val start: LocalDateTime,
        val pastEntries: Int = if (startsNow) 1 else 0
    ) : SaveOutcome {
        override val message: String
            get() {
                val repeats = "Repeats " + summary.replaceFirstChar { it.lowercase(Locale.ENGLISH) }
                return when {
                    !startsNow -> "$repeats from ${start.format(DAY_FORMAT)}"
                    pastEntries > 1 -> "$repeats, $pastEntries entries added"
                    else -> "$repeats, first entry added"
                }
            }
    }
}

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
