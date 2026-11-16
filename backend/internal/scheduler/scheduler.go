package scheduler

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"runtime/debug"
	"strings"
	"time"
)

// maxCatchUpPasses bounds how many query→materialize passes a single tick may
// run. Each pass advances every due schedule by one occurrence, so a schedule
// that is N periods overdue (server was down, or a past start date was saved)
// catches up in one tick for N <= 400 and continues on the next tick beyond
// that, without letting one tick run unbounded.
const maxCatchUpPasses = 400

// Start launches a goroutine that checks for due scheduled transactions
// every interval and materializes them into actual transactions.
// It stops when the context is cancelled.
//
// home is the optional home time zone (INSITU_TIMEZONE); nil keeps the
// fixed-offset stepping. See advanceDate.
func Start(ctx context.Context, db *sql.DB, interval time.Duration, home *time.Location) {
	go func() {
		safeProcessDue(db, home)

		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				log.Println("scheduler: shutting down")
				return
			case <-ticker.C:
				safeProcessDue(db, home)
			}
		}
	}()
}

// safeProcessDue runs processDue under a recover so a panic in one tick
// (e.g. a malformed row) does not terminate the scheduler goroutine and
// silently freeze all future scheduled materializations.
func safeProcessDue(db *sql.DB, home *time.Location) {
	defer func() {
		if rec := recover(); rec != nil {
			log.Printf("scheduler: panic recovered: %v\n%s", rec, debug.Stack())
		}
	}()
	processDue(db, home)
}

type scheduled struct {
	id              int64
	accountID       int64
	categoryID      int64
	userID          int64
	createdByUserID sql.NullInt64
	typ             string
	amount          float64
	currency        string
	description     *string
	note            *string
	rrule           string
	nextOccurrence  string
	maxOccurrences  *int64
	occurrenceCount int64
}

// processDue materializes every due occurrence, re-querying after each pass
// until nothing is due (capped at maxCatchUpPasses). A schedule that is
// several periods overdue therefore catches up in one tick instead of one
// occurrence per tick. Deactivated / exhausted schedules (active = 0 or
// deleted_at set by processOne) drop out of the query and stop.
func processDue(db *sql.DB, home *time.Location) {
	now := time.Now()

	// Rows excluded from the rest of this tick:
	//   - rows that failed (already logged; retrying them every pass would
	//     only repeat the error up to maxCatchUpPasses times), and
	//   - rows whose next_occurrence did not move (advanceDate could not
	//     parse it), so re-processing would re-materialize the same
	//     occurrence instead of catching up.
	// Both are retried on the next tick, exactly as before catch-up existed.
	skip := make(map[int64]bool)

	for pass := 0; pass < maxCatchUpPasses; pass++ {
		due, err := queryDue(db)
		if err != nil {
			log.Printf("scheduler: query error: %v", err)
			return
		}

		processed := 0
		for _, s := range due {
			if skip[s.id] {
				continue
			}
			processed++
			// Retry the entire tx body (not just Begin) on transient SQLite
			// locked/busy errors — a writer can grab the lock between Begin and
			// the first Exec, and without retrying the whole body those errors
			// would cause the schedule to be skipped this tick.
			var next string
			var err error
			for attempt := 0; attempt < 3; attempt++ {
				next, err = processOne(db, s, now, home)
				if err == nil || !isLockedError(err) {
					break
				}
				time.Sleep(time.Duration(50*(attempt+1)) * time.Millisecond)
			}
			if err != nil {
				log.Printf("scheduler: scheduled %d failed: %v", s.id, err)
				skip[s.id] = true
			} else if next == s.nextOccurrence {
				skip[s.id] = true
			}
		}
		if processed == 0 {
			return
		}
	}
	log.Printf("scheduler: catch-up stopped after %d passes; remaining due occurrences continue next tick", maxCatchUpPasses)
}

// queryDue returns every active scheduled row whose next_occurrence is due.
func queryDue(db *sql.DB) ([]scheduled, error) {
	// datetime(next_occurrence) parses TZ-aware ISO strings (post-1.18) and
	// naive ones (legacy), normalizing both to UTC. datetime('now') is UTC
	// too. Date-only values parse as UTC midnight — accepted shift from
	// server-local midnight for date-only schedules (TZ-agnostic per design).
	rows, err := db.Query(
		`SELECT id, account_id, category_id, user_id, created_by_user_id, type, amount, currency, description, note, rrule, next_occurrence, max_occurrences, occurrence_count
		 FROM scheduled_transactions
		 WHERE active = 1 AND deleted_at IS NULL AND datetime(next_occurrence) <= datetime('now')`,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var due []scheduled
	for rows.Next() {
		var s scheduled
		if err := rows.Scan(&s.id, &s.accountID, &s.categoryID, &s.userID, &s.createdByUserID, &s.typ,
			&s.amount, &s.currency, &s.description, &s.note, &s.rrule, &s.nextOccurrence,
			&s.maxOccurrences, &s.occurrenceCount); err != nil {
			log.Printf("scheduler: scan error: %v", err)
			continue
		}
		due = append(due, s)
	}
	return due, rows.Err()
}

// processOne materializes a single due scheduled row inside one transaction.
// Returns the stored next_occurrence and nil on success; the caller decides
// whether to retry.
func processOne(db *sql.DB, s scheduled, now time.Time, home *time.Location) (string, error) {
	tx, err := db.Begin()
	if err != nil {
		return "", fmt.Errorf("begin: %w", err)
	}

	// Deterministic client_id per (scheduled, occurrence): if this exact
	// occurrence was already materialized (e.g. multi-instance race, or a
	// crafted collision), the partial UNIQUE index on
	// (created_by_user_id, client_id) rejects the INSERT, the transaction
	// rolls back, and the schedule's next_occurrence is NOT re-advanced.
	clientID := fmt.Sprintf("sched-%d-%s", s.id, s.nextOccurrence)
	if _, err := tx.Exec(
		`INSERT INTO transactions (account_id, category_id, user_id, created_by_user_id, type, amount, currency, description, note, date, client_id)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		s.accountID, s.categoryID, s.userID, s.createdByUserID, s.typ, s.amount, s.currency, s.description, s.note, s.nextOccurrence, clientID,
	); err != nil {
		tx.Rollback()
		return "", fmt.Errorf("insert transaction (client_id=%s): %w", clientID, err)
	}

	sign := 1.0
	if s.typ == "expense" {
		sign = -1.0
	}
	if _, err := tx.Exec("UPDATE accounts SET balance = balance + ? WHERE id = ?", s.amount*sign, s.accountID); err != nil {
		tx.Rollback()
		return "", fmt.Errorf("update balance: %w", err)
	}

	next, pastUntil := advanceDate(s.nextOccurrence, s.rrule, home)
	newCount := s.occurrenceCount + 1
	deactivate := pastUntil || (s.maxOccurrences != nil && newCount >= *s.maxOccurrences)

	active := 1
	if deactivate {
		active = 0
	}

	deletedAt := sql.NullTime{}
	if deactivate {
		deletedAt = sql.NullTime{Time: now, Valid: true}
	}

	if _, err := tx.Exec(
		"UPDATE scheduled_transactions SET next_occurrence = ?, occurrence_count = ?, active = ?, deleted_at = ? WHERE id = ?",
		next, newCount, active, deletedAt, s.id,
	); err != nil {
		tx.Rollback()
		return "", fmt.Errorf("advance next_occurrence: %w", err)
	}

	if err := tx.Commit(); err != nil {
		return "", fmt.Errorf("commit: %w", err)
	}

	if deactivate {
		log.Printf("scheduler: materialized scheduled %d, deactivated after %d occurrences", s.id, newCount)
	} else {
		log.Printf("scheduler: materialized scheduled %d, next: %s (%d/%v)", s.id, next, newCount, s.maxOccurrences)
	}
	return next, nil
}

func isLockedError(err error) bool {
	if err == nil {
		return false
	}
	s := strings.ToLower(err.Error())
	return strings.Contains(s, "locked") || strings.Contains(s, "busy")
}

// advanceDate calculates the next occurrence from the current date and rrule.
// Supports simplified RRULE: FREQ=DAILY, FREQ=WEEKLY, FREQ=MONTHLY, FREQ=YEARLY
// with optional INTERVAL=n and UNTIL=YYYYMMDD[THHMMSSZ].
// Preserves time component AND offset if present (e.g.
// 2026-03-18T09:00:00+02:00 -> 2026-04-18T09:00:00+02:00).
// MONTHLY/YEARLY clamp the day-of-month to the target month's last valid day
// (see addMonthsClamped) so the backend agrees with the Android client.
//
// Home time zone (INSITU_TIMEZONE, passed as home; nil = unset): when the
// stored value carries an explicit offset AND that offset equals home's offset
// at that instant, the value is treated as home-zone wall-clock time. The wall
// clock is stepped in home (same DAILY/WEEKLY/MONTHLY/YEARLY and clamping
// rules) and re-resolved there, so the offset follows DST: Rome 09:00+01:00
// monthly lands on 09:00+02:00 after the March switch. A wall-clock reading
// that falls in a DST gap is shifted forward by the gap length; one that falls
// in an overlap takes the earlier offset (see resolveWallClock). Everything
// else — home unset, a foreign offset (e.g. created while travelling), naive
// and date-only values — keeps the fixed-offset stepping above, unchanged.
// A schedule that already drifted off home time switches back to home-zone
// stepping the next time its offset matches home again.
//
// Returns (next, pastUntil) where pastUntil is true if the new occurrence falls
// after the rrule's UNTIL value (caller should deactivate the schedule).
func advanceDate(current string, rrule string, home *time.Location) (string, bool) {
	// Format preference for emit, in order of input shape:
	//   RFC3339 (with offset) → RFC3339         (preserves client's offset)
	//   no-seconds offset     → RFC3339         (canonicalized + seconds)
	//   naive datetime        → "2006-01-02T15:04"  (legacy-compat)
	//   date-only             → "2006-01-02"
	var t time.Time
	var emitFormat string
	hasOffset := false
	if parsed, err := time.Parse(time.RFC3339, current); err == nil {
		t = parsed
		emitFormat = time.RFC3339
		hasOffset = true
	} else if parsed, err := time.Parse("2006-01-02T15:04Z07:00", current); err == nil {
		t = parsed
		emitFormat = time.RFC3339
		hasOffset = true
	} else if parsed, err := time.ParseInLocation("2006-01-02T15:04", current, time.Local); err == nil {
		t = parsed
		emitFormat = "2006-01-02T15:04"
	} else if parsed, err := time.ParseInLocation("2006-01-02", current, time.Local); err == nil {
		t = parsed
		emitFormat = "2006-01-02"
	} else {
		// Unparseable — return verbatim with pastUntil=false so the caller
		// doesn't loop on this row every tick (it'd still appear due, but
		// processOne would error and log).
		return current, false
	}

	freq := ""
	interval := 1
	untilStr := ""

	for _, part := range strings.Split(rrule, ";") {
		kv := strings.SplitN(part, "=", 2)
		if len(kv) != 2 {
			continue
		}
		switch kv[0] {
		case "FREQ":
			freq = kv[1]
		case "INTERVAL":
			n := 0
			for _, c := range kv[1] {
				if c >= '0' && c <= '9' {
					n = n*10 + int(c-'0')
				}
			}
			if n > 0 {
				interval = n
			}
		case "UNTIL":
			untilStr = kv[1]
		}
	}

	if home != nil && hasOffset && offsetMatchesZone(t, home) {
		t = stepWallClockInZone(t.In(home), freq, interval)
	} else {
		switch freq {
		case "DAILY":
			t = t.AddDate(0, 0, interval)
		case "WEEKLY":
			t = t.AddDate(0, 0, 7*interval)
		case "MONTHLY":
			t = addMonthsClamped(t, interval)
		case "YEARLY":
			t = addMonthsClamped(t, 12*interval)
		default:
			t = addMonthsClamped(t, 1) // fallback: monthly
		}
	}

	pastUntil := false
	if untilStr != "" {
		if until, ok := ParseUntil(untilStr); ok && t.After(until) {
			pastUntil = true
		}
	}

	return t.Format(emitFormat), pastUntil
}

// lastDayOfMonth returns the number of days in (y, m). Day 0 of month m+1 is
// the last day of month m; time.Date normalizes December → January of y+1.
func lastDayOfMonth(y int, m time.Month) int {
	return time.Date(y, m+1, 0, 0, 0, 0, 0, time.UTC).Day()
}

// addMonthsClamped adds n months, clamping the day-of-month to the last valid
// day of the target month (2026-01-31 + 1mo → 2026-02-28). Go's AddDate
// normalizes the overflow *forward* (→ 2026-03-03), which skips February
// entirely and diverges from java.time.LocalDate.plusMonths on Android.
//
// The clamp is sticky: once a schedule lands on Feb 28 it stays on the 28th
// (Feb 28 → Mar 28, not Mar 31). That matches Android and is the accepted
// trade-off; true RFC 5545 semantics would keep the 31st anchor and skip
// short months, which needs an anchor-day column. See TODO.
//
// Preserves the clock time and the input's Location so a fixed RFC3339 offset
// survives advanceDate's format contract.
func addMonthsClamped(t time.Time, n int) time.Time {
	y, m, d := addMonthsToDate(t.Year(), t.Month(), t.Day(), n)
	return time.Date(y, m, d,
		t.Hour(), t.Minute(), t.Second(), t.Nanosecond(), t.Location())
}

// addMonthsToDate is the calendar-date core of addMonthsClamped: it adds n
// months to (y, m, d) and clamps d to the target month's last valid day.
func addMonthsToDate(y int, m time.Month, d int, n int) (int, time.Month, int) {
	total := int(m) - 1 + n
	ty := y + total/12
	tm := total % 12
	// Guard the negative-modulo case defensively; the rrule parser guarantees
	// interval >= 1 today, but this must not silently produce month 0 or -3.
	if tm < 0 {
		tm += 12
		ty--
	}
	targetMonth := time.Month(tm + 1)

	if last := lastDayOfMonth(ty, targetMonth); d > last {
		d = last
	}
	return ty, targetMonth, d
}

// addDaysToDate adds n calendar days to (y, m, d). Computed on a UTC
// midnight, so no DST transition can turn it into n±1 days.
func addDaysToDate(y int, m time.Month, d int, n int) (int, time.Month, int) {
	return time.Date(y, m, d+n, 0, 0, 0, 0, time.UTC).Date()
}

// offsetMatchesZone reports whether t's UTC offset is the offset loc has in
// effect at that same instant, i.e. t is a plausible loc wall-clock reading.
func offsetMatchesZone(t time.Time, loc *time.Location) bool {
	_, off := t.Zone()
	_, locOff := t.In(loc).Zone()
	return off == locOff
}

// stepWallClockInZone advances the wall-clock reading of lt (already in its
// home Location) by one rrule step and resolves the result in that Location,
// so the offset is recomputed for the new date. DAILY/WEEKLY add calendar
// days; MONTHLY/YEARLY use the same sticky month-end clamp as
// addMonthsClamped. The time of day is kept as-is.
func stepWallClockInZone(lt time.Time, freq string, interval int) time.Time {
	y, m, d := lt.Date()
	switch freq {
	case "DAILY":
		y, m, d = addDaysToDate(y, m, d, interval)
	case "WEEKLY":
		y, m, d = addDaysToDate(y, m, d, 7*interval)
	case "MONTHLY":
		y, m, d = addMonthsToDate(y, m, d, interval)
	case "YEARLY":
		y, m, d = addMonthsToDate(y, m, d, 12*interval)
	default:
		y, m, d = addMonthsToDate(y, m, d, 1) // fallback: monthly
	}
	return resolveWallClock(y, m, d, lt.Hour(), lt.Minute(), lt.Second(), lt.Nanosecond(), lt.Location())
}

// resolveWallClock maps a wall-clock reading in loc to an instant with
// explicit, documented DST rules instead of relying on time.Date, whose choice
// in a gap or overlap is unspecified:
//
//   - gap (the reading never occurs, e.g. Rome 02:30 on the spring-forward
//     day): shift forward by the gap length, i.e. interpret the reading with
//     the pre-transition offset → 03:30+02:00. Same as java.time
//     ZonedDateTime.of, which the Android client uses.
//   - overlap (the reading occurs twice, e.g. Rome 02:30 on the fall-back
//     day): take the earlier instant, i.e. the earlier offset → 02:30+02:00.
func resolveWallClock(y int, m time.Month, d, hh, mm, ss, ns int, loc *time.Location) time.Time {
	// The reading's digits as if they were UTC; the real instant is this
	// minus the zone offset, so it lies within about ±14h of it.
	wall := time.Date(y, m, d, hh, mm, ss, ns, time.UTC)

	// Offsets in effect well before and well after the reading. A transition
	// relevant to it lies within this ±36h window, and real zones never
	// transition twice that close together.
	_, offBefore := wall.Add(-36 * time.Hour).In(loc).Zone()
	_, offAfter := wall.Add(36 * time.Hour).In(loc).Zone()

	// A candidate offset is valid if the instant it yields really has that
	// offset in loc.
	var valid []time.Time
	for _, off := range []int{offBefore, offAfter} {
		inst := wall.Add(-time.Duration(off) * time.Second)
		if _, got := inst.In(loc).Zone(); got == off {
			valid = append(valid, inst)
		}
		if offBefore == offAfter {
			break
		}
	}

	switch {
	case len(valid) == 1:
		return valid[0].In(loc)
	case len(valid) == 2:
		// Overlap: both offsets produce the reading; keep the earlier instant.
		if valid[1].Before(valid[0]) {
			return valid[1].In(loc)
		}
		return valid[0].In(loc)
	case offAfter > offBefore:
		// Gap: no offset produces the reading. With the pre-transition offset
		// the instant lands just past the transition, which reads as the
		// requested wall clock plus the gap length.
		return wall.Add(-time.Duration(offBefore) * time.Second).In(loc)
	default:
		// Not reachable for real tzdata; keep a defined result anyway.
		return time.Date(y, m, d, hh, mm, ss, ns, loc)
	}
}

// ParseUntil accepts the common RFC 5545 UNTIL forms:
//
//	20261231T235959Z, 20261231T235959, 20261231, 2026-12-31, 2026-12-31T23:59:59
//
// Exported so internal/api can validate a client-supplied UNTIL against
// exactly the shapes the scheduler will later accept.
func ParseUntil(s string) (time.Time, bool) {
	for _, e := range []struct {
		layout   string
		dateOnly bool
	}{
		{"20060102T150405Z", false},
		{"20060102T150405", false},
		{"20060102", true},
		{"2006-01-02T15:04:05", false},
		{"2006-01-02T15:04", false},
		{"2006-01-02", true},
	} {
		if t, err := time.Parse(e.layout, s); err == nil {
			if e.dateOnly {
				// RFC 5545: a date-only UNTIL is inclusive of the whole day.
				t = t.Add(24*time.Hour - time.Nanosecond)
			}
			return t, true
		}
	}
	return time.Time{}, false
}
