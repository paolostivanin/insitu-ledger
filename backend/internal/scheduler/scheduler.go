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

// Start launches a goroutine that checks for due scheduled transactions
// every interval and materializes them into actual transactions.
// It stops when the context is cancelled.
func Start(ctx context.Context, db *sql.DB, interval time.Duration) {
	go func() {
		safeProcessDue(db)

		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				log.Println("scheduler: shutting down")
				return
			case <-ticker.C:
				safeProcessDue(db)
			}
		}
	}()
}

// safeProcessDue runs processDue under a recover so a panic in one tick
// (e.g. a malformed row) does not terminate the scheduler goroutine and
// silently freeze all future scheduled materializations.
func safeProcessDue(db *sql.DB) {
	defer func() {
		if rec := recover(); rec != nil {
			log.Printf("scheduler: panic recovered: %v\n%s", rec, debug.Stack())
		}
	}()
	processDue(db)
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

func processDue(db *sql.DB) {
	now := time.Now()

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
		log.Printf("scheduler: query error: %v", err)
		return
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

	for _, s := range due {
		// Retry the entire tx body (not just Begin) on transient SQLite
		// locked/busy errors — a writer can grab the lock between Begin and
		// the first Exec, and without retrying the whole body those errors
		// would cause the schedule to be skipped this tick.
		var err error
		for attempt := 0; attempt < 3; attempt++ {
			err = processOne(db, s, now)
			if err == nil || !isLockedError(err) {
				break
			}
			time.Sleep(time.Duration(50*(attempt+1)) * time.Millisecond)
		}
		if err != nil {
			log.Printf("scheduler: scheduled %d failed: %v", s.id, err)
		}
	}
}

// processOne materializes a single due scheduled row inside one transaction.
// Returns nil on success; the caller decides whether to retry.
func processOne(db *sql.DB, s scheduled, now time.Time) error {
	tx, err := db.Begin()
	if err != nil {
		return fmt.Errorf("begin: %w", err)
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
		return fmt.Errorf("insert transaction (client_id=%s): %w", clientID, err)
	}

	sign := 1.0
	if s.typ == "expense" {
		sign = -1.0
	}
	if _, err := tx.Exec("UPDATE accounts SET balance = balance + ? WHERE id = ?", s.amount*sign, s.accountID); err != nil {
		tx.Rollback()
		return fmt.Errorf("update balance: %w", err)
	}

	next, pastUntil := advanceDate(s.nextOccurrence, s.rrule)
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
		return fmt.Errorf("advance next_occurrence: %w", err)
	}

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit: %w", err)
	}

	if deactivate {
		log.Printf("scheduler: materialized scheduled %d, deactivated after %d occurrences", s.id, newCount)
	} else {
		log.Printf("scheduler: materialized scheduled %d, next: %s (%d/%v)", s.id, next, newCount, s.maxOccurrences)
	}
	return nil
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
// Returns (next, pastUntil) where pastUntil is true if the new occurrence falls
// after the rrule's UNTIL value (caller should deactivate the schedule).
func advanceDate(current string, rrule string) (string, bool) {
	// Format preference for emit, in order of input shape:
	//   RFC3339 (with offset) → RFC3339         (preserves client's offset)
	//   no-seconds offset     → RFC3339         (canonicalized + seconds)
	//   naive datetime        → "2006-01-02T15:04"  (legacy-compat)
	//   date-only             → "2006-01-02"
	var t time.Time
	var emitFormat string
	if parsed, err := time.Parse(time.RFC3339, current); err == nil {
		t = parsed
		emitFormat = time.RFC3339
	} else if parsed, err := time.Parse("2006-01-02T15:04Z07:00", current); err == nil {
		t = parsed
		emitFormat = time.RFC3339
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

	switch freq {
	case "DAILY":
		t = t.AddDate(0, 0, interval)
	case "WEEKLY":
		t = t.AddDate(0, 0, 7*interval)
	case "MONTHLY":
		t = t.AddDate(0, interval, 0)
	case "YEARLY":
		t = t.AddDate(interval, 0, 0)
	default:
		t = t.AddDate(0, 1, 0) // fallback: monthly
	}

	pastUntil := false
	if untilStr != "" {
		if until, ok := parseUntil(untilStr); ok && t.After(until) {
			pastUntil = true
		}
	}

	return t.Format(emitFormat), pastUntil
}

// parseUntil accepts the common RFC 5545 UNTIL forms:
//
//	20261231T235959Z, 20261231T235959, 20261231, 2026-12-31, 2026-12-31T23:59:59
func parseUntil(s string) (time.Time, bool) {
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
