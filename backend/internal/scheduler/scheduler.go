package scheduler

import (
	"context"
	"database/sql"
	"log"
	"strings"
	"time"
)

// Start launches a goroutine that checks for due scheduled transactions
// every interval and materializes them into actual transactions.
// It stops when the context is cancelled.
func Start(ctx context.Context, db *sql.DB, interval time.Duration) {
	go func() {
		// Run once immediately on startup
		processDue(db)

		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				log.Println("scheduler: shutting down")
				return
			case <-ticker.C:
				processDue(db)
			}
		}
	}()
}

func processDue(db *sql.DB) {
	now := time.Now()
	// Use datetime format so time-based scheduling works.
	// Old date-only values (YYYY-MM-DD) sort before any YYYY-MM-DDTHH:MM on the same day,
	// so they are treated as due at midnight.
	nowStr := now.Format("2006-01-02T15:04")

	rows, err := db.Query(
		`SELECT id, account_id, category_id, user_id, created_by_user_id, type, amount, currency, description, note, rrule, next_occurrence, max_occurrences, occurrence_count
		 FROM scheduled_transactions
		 WHERE active = 1 AND deleted_at IS NULL AND next_occurrence <= ?`, nowStr,
	)
	if err != nil {
		log.Printf("scheduler: query error: %v", err)
		return
	}
	defer rows.Close()

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
		// Retry up to 3 times with short backoff for transient SQLite "database
		// is locked" errors that can happen if the API is mid-write at the
		// moment the scheduler tick fires.
		var tx *sql.Tx
		var err error
		for attempt := 0; attempt < 3; attempt++ {
			tx, err = db.Begin()
			if err == nil {
				break
			}
			if !strings.Contains(strings.ToLower(err.Error()), "locked") &&
				!strings.Contains(strings.ToLower(err.Error()), "busy") {
				break
			}
			time.Sleep(time.Duration(50*(attempt+1)) * time.Millisecond)
		}
		if err != nil {
			log.Printf("scheduler: begin tx error for scheduled %d: %v", s.id, err)
			continue
		}

		_, err = tx.Exec(
			`INSERT INTO transactions (account_id, category_id, user_id, created_by_user_id, type, amount, currency, description, note, date)
			 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
			s.accountID, s.categoryID, s.userID, s.createdByUserID, s.typ, s.amount, s.currency, s.description, s.note, s.nextOccurrence,
		)
		if err != nil {
			tx.Rollback()
			log.Printf("scheduler: insert transaction error for scheduled %d: %v", s.id, err)
			continue
		}

		sign := 1.0
		if s.typ == "expense" {
			sign = -1.0
		}
		if _, err := tx.Exec("UPDATE accounts SET balance = balance + ? WHERE id = ?", s.amount*sign, s.accountID); err != nil {
			tx.Rollback()
			log.Printf("scheduler: update balance error for scheduled %d: %v", s.id, err)
			continue
		}

		next := advanceDate(s.nextOccurrence, s.rrule)
		newCount := s.occurrenceCount + 1
		deactivate := s.maxOccurrences != nil && newCount >= *s.maxOccurrences

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
			log.Printf("scheduler: advance next_occurrence error for scheduled %d: %v", s.id, err)
			continue
		}

		if err := tx.Commit(); err != nil {
			log.Printf("scheduler: commit error for scheduled %d: %v", s.id, err)
			continue
		}

		if deactivate {
			log.Printf("scheduler: materialized scheduled %d, deactivated after %d occurrences", s.id, newCount)
		} else {
			log.Printf("scheduler: materialized scheduled %d, next: %s (%d/%v)", s.id, next, newCount, s.maxOccurrences)
		}
	}
}

// advanceDate calculates the next occurrence from the current date and rrule.
// Supports simplified RRULE: FREQ=DAILY, FREQ=WEEKLY, FREQ=MONTHLY, FREQ=YEARLY
// with optional INTERVAL=n.
// Preserves time component if present (e.g. 2026-03-18T09:00 -> 2026-04-18T09:00).
func advanceDate(current string, rrule string) string {
	// Try datetime first, then date-only
	format := "2006-01-02T15:04"
	t, err := time.Parse(format, current)
	if err != nil {
		format = "2006-01-02"
		t, err = time.Parse(format, current)
		if err != nil {
			return current
		}
	}

	freq := ""
	interval := 1

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

	return t.Format(format)
}
