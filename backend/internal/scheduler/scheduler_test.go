package scheduler

import (
	"database/sql"
	"testing"
	"time"

	_ "modernc.org/sqlite"
)

// minimal subset of the production schema needed to exercise processDue: we
// intentionally do not pull in the full schema to keep the test self-contained.
const testSchema = `
CREATE TABLE users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL
);
CREATE TABLE accounts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    balance REAL NOT NULL DEFAULT 0
);
CREATE TABLE categories (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    type TEXT NOT NULL
);
CREATE TABLE transactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    category_id INTEGER NOT NULL REFERENCES categories(id) ON DELETE RESTRICT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_by_user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    type TEXT NOT NULL,
    amount REAL NOT NULL,
    currency TEXT NOT NULL DEFAULT 'EUR',
    description TEXT,
    note TEXT,
    date TEXT NOT NULL,
    deleted_at DATETIME,
    sync_version INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE scheduled_transactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    category_id INTEGER NOT NULL REFERENCES categories(id) ON DELETE RESTRICT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_by_user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    type TEXT NOT NULL,
    amount REAL NOT NULL,
    currency TEXT NOT NULL DEFAULT 'EUR',
    description TEXT,
    note TEXT,
    rrule TEXT NOT NULL,
    next_occurrence TEXT NOT NULL,
    active INTEGER NOT NULL DEFAULT 1,
    max_occurrences INTEGER,
    occurrence_count INTEGER NOT NULL DEFAULT 0,
    deleted_at DATETIME,
    sync_version INTEGER NOT NULL DEFAULT 0
);
`

func newTestDB(t *testing.T) *sql.DB {
	t.Helper()
	db, err := sql.Open("sqlite", "file::memory:?_pragma=foreign_keys(on)")
	if err != nil {
		t.Fatalf("open db: %v", err)
	}
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(testSchema); err != nil {
		db.Close()
		t.Fatalf("apply schema: %v", err)
	}
	t.Cleanup(func() { db.Close() })
	return db
}

// TestProcessDuePreservesCoOwnerAttribution: when a co-owner schedules a
// recurring transaction on a shared account, the materialized child must carry
// created_by_user_id forward (the co-owner), while user_id stays as the legacy
// account-owner pointer. Regression test for the v1.15.0 share-model bug where
// processDue dropped the column and silently NULL'd attribution.
func TestProcessDuePreservesCoOwnerAttribution(t *testing.T) {
	db := newTestDB(t)

	// Owner = 1, co-owner = 2.
	if _, err := db.Exec(`INSERT INTO users (name) VALUES ('Owner'), ('CoOwner')`); err != nil {
		t.Fatalf("seed users: %v", err)
	}
	if _, err := db.Exec(`INSERT INTO accounts (user_id, name) VALUES (1, 'Joint')`); err != nil {
		t.Fatalf("seed account: %v", err)
	}
	if _, err := db.Exec(`INSERT INTO categories (user_id, name, type) VALUES (1, 'Groceries', 'expense')`); err != nil {
		t.Fatalf("seed category: %v", err)
	}

	pastDate := time.Now().Add(-24 * time.Hour).Format("2006-01-02T15:04")
	if _, err := db.Exec(
		`INSERT INTO scheduled_transactions
		 (account_id, category_id, user_id, created_by_user_id, type, amount, currency, rrule, next_occurrence)
		 VALUES (1, 1, 1, 2, 'expense', 12.50, 'EUR', 'FREQ=DAILY', ?)`,
		pastDate,
	); err != nil {
		t.Fatalf("seed scheduled: %v", err)
	}

	processDue(db)

	var userID int64
	var createdBy sql.NullInt64
	if err := db.QueryRow(
		`SELECT user_id, created_by_user_id FROM transactions ORDER BY id DESC LIMIT 1`,
	).Scan(&userID, &createdBy); err != nil {
		t.Fatalf("scan materialized transaction: %v", err)
	}
	if userID != 1 {
		t.Errorf("user_id = %d, want 1 (account owner)", userID)
	}
	if !createdBy.Valid || createdBy.Int64 != 2 {
		t.Errorf("created_by_user_id = %v, want 2 (co-owner who scheduled it)", createdBy)
	}
}

// TestProcessDueForwardsNullCreatedBy: a scheduled row whose creator was deleted
// has created_by_user_id = NULL (FK SET NULL). The materialized child must also
// be NULL — we forward what we read, we don't fabricate.
func TestProcessDueForwardsNullCreatedBy(t *testing.T) {
	db := newTestDB(t)

	if _, err := db.Exec(`INSERT INTO users (name) VALUES ('Owner')`); err != nil {
		t.Fatalf("seed user: %v", err)
	}
	if _, err := db.Exec(`INSERT INTO accounts (user_id, name) VALUES (1, 'Solo')`); err != nil {
		t.Fatalf("seed account: %v", err)
	}
	if _, err := db.Exec(`INSERT INTO categories (user_id, name, type) VALUES (1, 'Salary', 'income')`); err != nil {
		t.Fatalf("seed category: %v", err)
	}

	pastDate := time.Now().Add(-24 * time.Hour).Format("2006-01-02T15:04")
	if _, err := db.Exec(
		`INSERT INTO scheduled_transactions
		 (account_id, category_id, user_id, created_by_user_id, type, amount, currency, rrule, next_occurrence)
		 VALUES (1, 1, 1, NULL, 'income', 100, 'EUR', 'FREQ=MONTHLY', ?)`,
		pastDate,
	); err != nil {
		t.Fatalf("seed scheduled: %v", err)
	}

	processDue(db)

	var createdBy sql.NullInt64
	if err := db.QueryRow(
		`SELECT created_by_user_id FROM transactions ORDER BY id DESC LIMIT 1`,
	).Scan(&createdBy); err != nil {
		t.Fatalf("scan: %v", err)
	}
	if createdBy.Valid {
		t.Errorf("created_by_user_id = %d, want NULL", createdBy.Int64)
	}
}
