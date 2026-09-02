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
    sync_version INTEGER NOT NULL DEFAULT 0,
    client_id TEXT
);
CREATE UNIQUE INDEX idx_transactions_client_id ON transactions(created_by_user_id, client_id) WHERE client_id IS NOT NULL;
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

// TestProcessDueWritesClientID: every materialized row must carry the
// deterministic "sched-<id>-<date>" client_id so a hypothetical re-fire of the
// same (scheduled, occurrence) is rejected by the UNIQUE partial index instead
// of producing a duplicate transaction.
func TestProcessDueWritesClientID(t *testing.T) {
	db := newTestDB(t)
	if _, err := db.Exec(`INSERT INTO users (name) VALUES ('Owner')`); err != nil {
		t.Fatalf("seed user: %v", err)
	}
	if _, err := db.Exec(`INSERT INTO accounts (user_id, name) VALUES (1, 'Solo')`); err != nil {
		t.Fatalf("seed account: %v", err)
	}
	if _, err := db.Exec(`INSERT INTO categories (user_id, name, type) VALUES (1, 'X', 'expense')`); err != nil {
		t.Fatalf("seed cat: %v", err)
	}
	// Use UTC + 24h offset so the naive datetime() comparison works regardless
	// of the test host's TZ (see TestProcessDuePreservesCoOwnerAttribution for
	// the same trick).
	pastDate := time.Now().UTC().Add(-24 * time.Hour).Format("2006-01-02T15:04")
	res, err := db.Exec(
		`INSERT INTO scheduled_transactions
		 (account_id, category_id, user_id, created_by_user_id, type, amount, currency, rrule, next_occurrence)
		 VALUES (1, 1, 1, 1, 'expense', 5.0, 'EUR', 'FREQ=DAILY', ?)`,
		pastDate,
	)
	if err != nil {
		t.Fatalf("seed scheduled: %v", err)
	}
	schedID, _ := res.LastInsertId()

	processDue(db)

	var clientID sql.NullString
	if err := db.QueryRow(`SELECT client_id FROM transactions ORDER BY id DESC LIMIT 1`).Scan(&clientID); err != nil {
		t.Fatalf("scan: %v", err)
	}
	wantPrefix := "sched-"
	if !clientID.Valid || clientID.String[:len(wantPrefix)] != wantPrefix {
		t.Errorf("client_id = %v, want sched-* prefix", clientID)
	}
	// Validate exact format: "sched-<id>-<date>".
	want := "sched-1-" + pastDate
	if clientID.String != want {
		t.Errorf("client_id = %q, want %q", clientID.String, want)
	}
	_ = schedID
}

// TestProcessDueRefireDeduplicates: if the same (scheduled, occurrence) is
// already materialized in transactions (matching client_id), processDue must
// NOT insert a second row. The transaction rolls back, and the
// next_occurrence is NOT advanced (so the operator can investigate).
func TestProcessDueRefireDeduplicates(t *testing.T) {
	db := newTestDB(t)
	if _, err := db.Exec(`INSERT INTO users (name) VALUES ('Owner')`); err != nil {
		t.Fatalf("seed user: %v", err)
	}
	if _, err := db.Exec(`INSERT INTO accounts (user_id, name) VALUES (1, 'Solo')`); err != nil {
		t.Fatalf("seed account: %v", err)
	}
	if _, err := db.Exec(`INSERT INTO categories (user_id, name, type) VALUES (1, 'X', 'expense')`); err != nil {
		t.Fatalf("seed cat: %v", err)
	}
	// Use UTC + 24h offset so the naive datetime() comparison works regardless
	// of the test host's TZ (see TestProcessDuePreservesCoOwnerAttribution for
	// the same trick).
	pastDate := time.Now().UTC().Add(-24 * time.Hour).Format("2006-01-02T15:04")
	if _, err := db.Exec(
		`INSERT INTO scheduled_transactions
		 (account_id, category_id, user_id, created_by_user_id, type, amount, currency, rrule, next_occurrence)
		 VALUES (1, 1, 1, 1, 'expense', 5.0, 'EUR', 'FREQ=DAILY', ?)`,
		pastDate,
	); err != nil {
		t.Fatalf("seed scheduled: %v", err)
	}
	// Pre-seed the materialized child with the matching deterministic client_id.
	if _, err := db.Exec(
		`INSERT INTO transactions
		 (account_id, category_id, user_id, created_by_user_id, type, amount, currency, date, client_id)
		 VALUES (1, 1, 1, 1, 'expense', 5.0, 'EUR', ?, ?)`,
		pastDate, "sched-1-"+pastDate,
	); err != nil {
		t.Fatalf("pre-seed: %v", err)
	}

	processDue(db)

	var count int
	if err := db.QueryRow(`SELECT COUNT(*) FROM transactions`).Scan(&count); err != nil {
		t.Fatalf("count: %v", err)
	}
	if count != 1 {
		t.Errorf("transactions count = %d, want 1 (no duplicate)", count)
	}
	// next_occurrence should NOT have been advanced (the INSERT failed → rollback).
	var nextOcc string
	if err := db.QueryRow(`SELECT next_occurrence FROM scheduled_transactions WHERE id = 1`).Scan(&nextOcc); err != nil {
		t.Fatalf("read scheduled: %v", err)
	}
	if nextOcc != pastDate {
		t.Errorf("next_occurrence = %q, want %q (unchanged after rollback)", nextOcc, pastDate)
	}
}

// TestProcessDueDateOnlySchedule: a date-only next_occurrence must still be
// picked up by the datetime() comparison (parses as UTC midnight).
func TestProcessDueDateOnlySchedule(t *testing.T) {
	db := newTestDB(t)
	if _, err := db.Exec(`INSERT INTO users (name) VALUES ('Owner')`); err != nil {
		t.Fatalf("seed user: %v", err)
	}
	if _, err := db.Exec(`INSERT INTO accounts (user_id, name) VALUES (1, 'Solo')`); err != nil {
		t.Fatalf("seed account: %v", err)
	}
	if _, err := db.Exec(`INSERT INTO categories (user_id, name, type) VALUES (1, 'X', 'expense')`); err != nil {
		t.Fatalf("seed cat: %v", err)
	}
	// Yesterday's date (date-only) — must be picked up as due.
	yesterday := time.Now().UTC().Add(-24 * time.Hour).Format("2006-01-02")
	if _, err := db.Exec(
		`INSERT INTO scheduled_transactions
		 (account_id, category_id, user_id, created_by_user_id, type, amount, currency, rrule, next_occurrence)
		 VALUES (1, 1, 1, 1, 'expense', 5.0, 'EUR', 'FREQ=DAILY', ?)`,
		yesterday,
	); err != nil {
		t.Fatalf("seed scheduled: %v", err)
	}

	processDue(db)

	var count int
	if err := db.QueryRow(`SELECT COUNT(*) FROM transactions`).Scan(&count); err != nil {
		t.Fatalf("count: %v", err)
	}
	if count != 1 {
		t.Errorf("transactions count = %d, want 1 (date-only schedule materialized)", count)
	}
}

// TestAdvanceDateFormatMatrix: advanceDate must preserve the input shape on
// emit — RFC3339 stays RFC3339 (with offset), no-seconds-offset upgrades to
// RFC3339, naive stays naive, date-only stays date-only.
func TestAdvanceDateFormatMatrix(t *testing.T) {
	cases := []struct {
		name        string
		input       string
		wantPattern string // checked with HasPrefix + HasSuffix-like logic below
		wantSuffix  string
	}{
		{"rfc3339 with offset", "2026-03-18T09:00:00+02:00", "2026-04-18T09:00:00", "+02:00"},
		{"no-seconds offset upgrades", "2026-03-18T09:00+02:00", "2026-04-18T09:00:00", "+02:00"},
		{"naive stays naive", "2026-03-18T09:00", "2026-04-18T09:00", ""},
		{"date-only stays date-only", "2026-03-18", "2026-04-18", ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, _ := advanceDate(tc.input, "FREQ=MONTHLY")
			if len(got) < len(tc.wantPattern) || got[:len(tc.wantPattern)] != tc.wantPattern {
				t.Errorf("got = %q, want prefix %q", got, tc.wantPattern)
			}
			if tc.wantSuffix != "" {
				if len(got) < len(tc.wantSuffix) || got[len(got)-len(tc.wantSuffix):] != tc.wantSuffix {
					t.Errorf("got = %q, want suffix %q", got, tc.wantSuffix)
				}
			}
		})
	}
}

// TestAdvanceDateMonthEndClamps: a MONTHLY advance must clamp the day-of-month
// to the target month's last valid day instead of overflowing forward. Before
// the fix, time.AddDate turned 2026-01-31 + 1 month into 2026-03-03 — skipping
// February entirely and disagreeing with java.time.plusMonths on Android.
func TestAdvanceDateMonthEndClamps(t *testing.T) {
	cases := []struct {
		name  string
		input string
		rrule string
		want  string
	}{
		{"jan 31 clamps to feb 28", "2026-01-31", "FREQ=MONTHLY", "2026-02-28"},
		{"jan 31 clamps to feb 29 in a leap year", "2024-01-31", "FREQ=MONTHLY", "2024-02-29"},
		{"jan 31 + 3 months clamps to apr 30", "2026-01-31", "FREQ=MONTHLY;INTERVAL=3", "2026-04-30"},
		{"mar 31 clamps to apr 30", "2026-03-31", "FREQ=MONTHLY", "2026-04-30"},
		{"jan 30 clamps to feb 28", "2026-01-30", "FREQ=MONTHLY", "2026-02-28"},
		{"mid-month does not clamp", "2026-05-15", "FREQ=MONTHLY", "2026-06-15"},
		{"dec 31 rolls the year without clamping", "2026-12-31", "FREQ=MONTHLY", "2027-01-31"},
		{"nov 30 + 2 months crosses the year", "2026-11-30", "FREQ=MONTHLY;INTERVAL=2", "2027-01-30"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, _ := advanceDate(tc.input, tc.rrule)
			if got != tc.want {
				t.Errorf("advanceDate(%q, %q) = %q, want %q", tc.input, tc.rrule, got, tc.want)
			}
		})
	}
}

// TestAdvanceDateYearlyLeapDayClamps: YEARLY is implemented as 12*interval
// months, so Feb 29 clamps to Feb 28 in a non-leap target year and survives
// intact when the target year is itself a leap year.
func TestAdvanceDateYearlyLeapDayClamps(t *testing.T) {
	cases := []struct {
		input string
		rrule string
		want  string
	}{
		{"2024-02-29", "FREQ=YEARLY", "2025-02-28"},
		{"2024-02-29", "FREQ=YEARLY;INTERVAL=4", "2028-02-29"},
		{"2026-06-11", "FREQ=YEARLY", "2027-06-11"},
	}
	for _, tc := range cases {
		got, _ := advanceDate(tc.input, tc.rrule)
		if got != tc.want {
			t.Errorf("advanceDate(%q, %q) = %q, want %q", tc.input, tc.rrule, got, tc.want)
		}
	}
}

// TestAdvanceDateClampPreservesShapeAndOffset: clamping goes through a
// time.Date rebuild, which is where a Location or a clock time is easiest to
// drop. Complements TestAdvanceDateFormatMatrix, which only exercises a
// non-clamping date.
func TestAdvanceDateClampPreservesShapeAndOffset(t *testing.T) {
	cases := []struct {
		name  string
		input string
		want  string
	}{
		{"rfc3339 offset survives the clamp", "2026-01-31T09:00:00+02:00", "2026-02-28T09:00:00+02:00"},
		{"naive shape survives the clamp", "2026-01-31T09:00", "2026-02-28T09:00"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, _ := advanceDate(tc.input, "FREQ=MONTHLY")
			if got != tc.want {
				t.Errorf("advanceDate(%q) = %q, want %q", tc.input, got, tc.want)
			}
		})
	}
}

// TestAdvanceDateClampDrifts documents the accepted trade-off: the clamp is
// sticky, so a schedule anchored on the 31st permanently moves to the 28th
// after passing through February. This matches java.time.plusMonths on
// Android. True RFC 5545 semantics would keep the 31st anchor and skip short
// months, which needs an anchor-day column — see TODO.
func TestAdvanceDateClampDrifts(t *testing.T) {
	feb, _ := advanceDate("2026-01-31", "FREQ=MONTHLY")
	if feb != "2026-02-28" {
		t.Fatalf("first advance = %q, want %q", feb, "2026-02-28")
	}
	mar, _ := advanceDate(feb, "FREQ=MONTHLY")
	if mar != "2026-03-28" {
		t.Errorf("second advance = %q, want %q (clamp is sticky, not restored to the 31st)", mar, "2026-03-28")
	}
}

// TestAdvanceDateArbitraryInterval: INTERVAL was always parsed, but only the
// values the six UI presets emitted (1, 2, 3) were ever exercised. Custom
// recurrence lets a user pick any N, so cover the whole FREQ matrix.
func TestAdvanceDateArbitraryInterval(t *testing.T) {
	cases := []struct {
		rrule string
		want  string
	}{
		{"FREQ=DAILY;INTERVAL=10", "2026-01-15"},
		{"FREQ=WEEKLY;INTERVAL=5", "2026-02-09"},
		{"FREQ=MONTHLY;INTERVAL=7", "2026-08-05"},
		{"FREQ=YEARLY;INTERVAL=2", "2028-01-05"},
	}
	for _, tc := range cases {
		got, _ := advanceDate("2026-01-05", tc.rrule)
		if got != tc.want {
			t.Errorf("advanceDate(%q, %q) = %q, want %q", "2026-01-05", tc.rrule, got, tc.want)
		}
	}
}

// TestParseUntilDateOnlyInclusive: per RFC 5545, a date-only UNTIL value is
// inclusive of the whole day, so it must parse as the last instant of that day
// rather than midnight (which would exclude any later time on the same date).
func TestParseUntilDateOnlyInclusive(t *testing.T) {
	want := time.Date(2026, 12, 31, 0, 0, 0, 0, time.UTC).Add(24*time.Hour - time.Nanosecond)
	for _, s := range []string{"20261231", "2026-12-31"} {
		got, ok := ParseUntil(s)
		if !ok {
			t.Errorf("ParseUntil(%q) failed", s)
			continue
		}
		if !got.Equal(want) {
			t.Errorf("ParseUntil(%q) = %v, want %v", s, got, want)
		}
	}
}

// TestParseUntilExplicitTimePreserved: when UNTIL carries an explicit time, it
// must be honored verbatim, not extended to end-of-day.
func TestParseUntilExplicitTimePreserved(t *testing.T) {
	cases := map[string]time.Time{
		"20261231T235959Z":    time.Date(2026, 12, 31, 23, 59, 59, 0, time.UTC),
		"20261231T120000":     time.Date(2026, 12, 31, 12, 0, 0, 0, time.UTC),
		"2026-12-31T15:30":    time.Date(2026, 12, 31, 15, 30, 0, 0, time.UTC),
		"2026-12-31T15:30:45": time.Date(2026, 12, 31, 15, 30, 45, 0, time.UTC),
	}
	for s, want := range cases {
		got, ok := ParseUntil(s)
		if !ok {
			t.Errorf("ParseUntil(%q) failed", s)
			continue
		}
		if !got.Equal(want) {
			t.Errorf("ParseUntil(%q) = %v, want %v", s, got, want)
		}
	}
}

// TestAdvanceDateDailyUntilDateOnlyIncludesUntilDay: regression test for the
// date-only UNTIL inclusivity bug. Before the fix, a daily schedule with
// UNTIL=20261231 would deactivate after Dec 30 fires, because the advanced
// Dec 31 09:00 was incorrectly flagged as past UNTIL (compared to midnight).
func TestAdvanceDateDailyUntilDateOnlyIncludesUntilDay(t *testing.T) {
	next, pastUntil := advanceDate("2026-12-30T09:00", "FREQ=DAILY;UNTIL=20261231")
	if next != "2026-12-31T09:00" {
		t.Errorf("next = %q, want %q", next, "2026-12-31T09:00")
	}
	if pastUntil {
		t.Errorf("pastUntil = true, want false (Dec 31 is inclusive)")
	}

	next, pastUntil = advanceDate("2026-12-31T09:00", "FREQ=DAILY;UNTIL=20261231")
	if next != "2027-01-01T09:00" {
		t.Errorf("next = %q, want %q", next, "2027-01-01T09:00")
	}
	if !pastUntil {
		t.Errorf("pastUntil = false, want true (Jan 1 is past Dec 31)")
	}
}
