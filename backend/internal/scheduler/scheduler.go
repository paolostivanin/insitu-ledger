package scheduler

import (
	"database/sql"
	"log"
	"strings"
	"time"
)

// Start launches a goroutine that checks for due scheduled transactions
// every interval and materializes them into actual transactions.
func Start(db *sql.DB, interval time.Duration) {
	go func() {
		// Run once immediately on startup
		processDue(db)

		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for range ticker.C {
			processDue(db)
		}
	}()
}

func processDue(db *sql.DB) {
	today := time.Now().Format("2006-01-02")

	rows, err := db.Query(
		`SELECT id, account_id, category_id, user_id, type, amount, currency, description, rrule, next_occurrence
		 FROM scheduled_transactions
		 WHERE active = 1 AND deleted_at IS NULL AND next_occurrence <= ?`, today,
	)
	if err != nil {
		log.Printf("scheduler: query error: %v", err)
		return
	}
	defer rows.Close()

	type scheduled struct {
		id             int64
		accountID      int64
		categoryID     int64
		userID         int64
		typ            string
		amount         float64
		currency       string
		description    *string
		rrule          string
		nextOccurrence string
	}

	var due []scheduled
	for rows.Next() {
		var s scheduled
		if err := rows.Scan(&s.id, &s.accountID, &s.categoryID, &s.userID, &s.typ,
			&s.amount, &s.currency, &s.description, &s.rrule, &s.nextOccurrence); err != nil {
			log.Printf("scheduler: scan error: %v", err)
			continue
		}
		due = append(due, s)
	}

	for _, s := range due {
		tx, err := db.Begin()
		if err != nil {
			log.Printf("scheduler: begin tx error for scheduled %d: %v", s.id, err)
			continue
		}

		_, err = tx.Exec(
			`INSERT INTO transactions (account_id, category_id, user_id, type, amount, currency, description, date)
			 VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
			s.accountID, s.categoryID, s.userID, s.typ, s.amount, s.currency, s.description, s.nextOccurrence,
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
		if _, err := tx.Exec("UPDATE scheduled_transactions SET next_occurrence = ? WHERE id = ?", next, s.id); err != nil {
			tx.Rollback()
			log.Printf("scheduler: advance next_occurrence error for scheduled %d: %v", s.id, err)
			continue
		}

		if err := tx.Commit(); err != nil {
			log.Printf("scheduler: commit error for scheduled %d: %v", s.id, err)
			continue
		}

		log.Printf("scheduler: materialized scheduled %d, next: %s", s.id, next)
	}
}

// advanceDate calculates the next occurrence from the current date and rrule.
// Supports simplified RRULE: FREQ=DAILY, FREQ=WEEKLY, FREQ=MONTHLY, FREQ=YEARLY
// with optional INTERVAL=n.
func advanceDate(current string, rrule string) string {
	t, err := time.Parse("2006-01-02", current)
	if err != nil {
		return current
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

	return t.Format("2006-01-02")
}
