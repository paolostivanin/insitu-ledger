package scheduler

import (
	"database/sql"
	"errors"
	"testing"
	"time"
)

// processOne runs after queryDue read the schedule outside its transaction.
// These tests replay that window deterministically: query, change the row the
// way a concurrent request would, then materialize the stale copy.

func seedDueSchedule(t *testing.T, db *sql.DB) {
	t.Helper()
	for _, q := range []string{
		`INSERT INTO users (name) VALUES ('Owner')`,
		`INSERT INTO accounts (user_id, name) VALUES (1, 'Wallet')`,
		`INSERT INTO categories (user_id, name, type) VALUES (1, 'Old', 'expense')`,
		`INSERT INTO categories (user_id, name, type) VALUES (1, 'New', 'expense')`,
	} {
		if _, err := db.Exec(q); err != nil {
			t.Fatalf("seed: %v", err)
		}
	}
	past := time.Now().UTC().Add(-48 * time.Hour).Format("2006-01-02T15:04")
	if _, err := db.Exec(
		`INSERT INTO scheduled_transactions (account_id, category_id, user_id, created_by_user_id, type, amount, currency, description, rrule, next_occurrence)
		 VALUES (1, 1, 1, 1, 'expense', 10, 'EUR', 'Old name', 'FREQ=MONTHLY', ?)`, past,
	); err != nil {
		t.Fatalf("seed schedule: %v", err)
	}
}

func queryOneDue(t *testing.T, db *sql.DB) scheduled {
	t.Helper()
	due, err := queryDue(db)
	if err != nil || len(due) != 1 {
		t.Fatalf("queryDue = %d rows, err %v; want 1", len(due), err)
	}
	return due[0]
}

type staleState struct {
	active, count int64
	deleted       bool
	next          string
	txns          int64
	balance       float64
}

func readStaleState(t *testing.T, db *sql.DB) staleState {
	t.Helper()
	var s staleState
	var deletedAt sql.NullString
	if err := db.QueryRow(`SELECT active, occurrence_count, deleted_at, next_occurrence FROM scheduled_transactions WHERE id = 1`).
		Scan(&s.active, &s.count, &deletedAt, &s.next); err != nil {
		t.Fatalf("read schedule: %v", err)
	}
	s.deleted = deletedAt.Valid
	if err := db.QueryRow(`SELECT COUNT(*) FROM transactions`).Scan(&s.txns); err != nil {
		t.Fatalf("count txns: %v", err)
	}
	if err := db.QueryRow(`SELECT balance FROM accounts WHERE id = 1`).Scan(&s.balance); err != nil {
		t.Fatalf("read balance: %v", err)
	}
	return s
}

func TestProcessOneStaleCopy_DeletedScheduleStaysDeleted(t *testing.T) {
	db := newTestDB(t)
	seedDueSchedule(t, db)
	stale := queryOneDue(t, db)
	if _, err := db.Exec(`UPDATE scheduled_transactions SET deleted_at = datetime('now') WHERE id = 1`); err != nil {
		t.Fatal(err)
	}
	before := readStaleState(t, db)

	if _, err := processOne(db, stale, time.Now(), nil); !errors.Is(err, errScheduleChanged) {
		t.Fatalf("processOne err = %v, want errScheduleChanged", err)
	}
	if after := readStaleState(t, db); after != before {
		t.Fatalf("stale tick changed a deleted schedule:\n before %+v\n after  %+v", before, after)
	}
}

func TestProcessOneStaleCopy_PausedScheduleStaysPaused(t *testing.T) {
	db := newTestDB(t)
	seedDueSchedule(t, db)
	stale := queryOneDue(t, db)
	if _, err := db.Exec(`UPDATE scheduled_transactions SET active = 0 WHERE id = 1`); err != nil {
		t.Fatal(err)
	}
	before := readStaleState(t, db)

	if _, err := processOne(db, stale, time.Now(), nil); !errors.Is(err, errScheduleChanged) {
		t.Fatalf("processOne err = %v, want errScheduleChanged", err)
	}
	if after := readStaleState(t, db); after != before {
		t.Fatalf("stale tick changed a paused schedule:\n before %+v\n after  %+v", before, after)
	}
}

func TestProcessOneStaleCopy_AlreadyAdvancedIsNotMaterializedTwice(t *testing.T) {
	db := newTestDB(t)
	seedDueSchedule(t, db)
	stale := queryOneDue(t, db)
	// Another tick (or instance) materialized this occurrence first.
	if _, err := processOne(db, stale, time.Now(), nil); err != nil {
		t.Fatalf("first processOne: %v", err)
	}
	before := readStaleState(t, db)

	if _, err := processOne(db, stale, time.Now(), nil); !errors.Is(err, errScheduleChanged) {
		t.Fatalf("second processOne err = %v, want errScheduleChanged", err)
	}
	if after := readStaleState(t, db); after != before {
		t.Fatalf("stale copy re-materialized:\n before %+v\n after  %+v", before, after)
	}
}

func TestProcessOneStaleCopy_UsesCurrentNameAndCategory(t *testing.T) {
	db := newTestDB(t)
	seedDueSchedule(t, db)
	stale := queryOneDue(t, db)
	// A bulk "also update the linked schedule" edit lands in the window.
	if _, err := db.Exec(`UPDATE scheduled_transactions SET description = 'New name', category_id = 2 WHERE id = 1`); err != nil {
		t.Fatal(err)
	}

	if _, err := processOne(db, stale, time.Now(), nil); err != nil {
		t.Fatalf("processOne: %v", err)
	}
	var desc string
	var cat int64
	if err := db.QueryRow(`SELECT description, category_id FROM transactions WHERE scheduled_id = 1`).Scan(&desc, &cat); err != nil {
		t.Fatalf("read materialized entry: %v", err)
	}
	if desc != "New name" || cat != 2 {
		t.Fatalf("materialized entry = (%q, %d), want (\"New name\", 2)", desc, cat)
	}
}

func TestProcessDue_CatchUpStillWorksWithGuard(t *testing.T) {
	db := newTestDB(t)
	seedDueSchedule(t, db)
	// Three days overdue, daily: every pass re-reads the row it just advanced,
	// so the guard must not stop the catch-up.
	past := time.Now().UTC().Add(-72 * time.Hour).Format("2006-01-02T15:04")
	if _, err := db.Exec(`UPDATE scheduled_transactions SET rrule = 'FREQ=DAILY', next_occurrence = ? WHERE id = 1`, past); err != nil {
		t.Fatal(err)
	}
	processDue(db, nil)
	s := readStaleState(t, db)
	if s.txns < 3 || s.deleted || s.active != 1 {
		t.Fatalf("processDue state = %+v, want ≥3 materialized entries and a live schedule", s)
	}
	if s.count != s.txns {
		t.Fatalf("occurrence_count %d != materialized entries %d", s.count, s.txns)
	}
	if s.balance != -10*float64(s.txns) {
		t.Fatalf("balance %v != -10 × %d entries", s.balance, s.txns)
	}
}
