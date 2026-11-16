package db

import (
	"database/sql"
	"path/filepath"
	"regexp"
	"testing"

	_ "modernc.org/sqlite"
)

// markerColumnsRE matches the recurring-entry marker columns at the end of the
// transactions table in schema.sql, so a test can derive the exact
// pre-marker schema from the current one.
var markerColumnsRE = regexp.MustCompile(`(?s)client_id TEXT,\n\s*-- Recurring-entry marker.*?occurrence_total INTEGER\n\);`)

func preMarkerSchema(t *testing.T) string {
	t.Helper()
	if n := len(markerColumnsRE.FindAllStringIndex(schemaSQL, -1)); n != 1 {
		t.Fatalf("expected the marker block once in schema.sql, found %d", n)
	}
	return markerColumnsRE.ReplaceAllString(schemaSQL, "client_id TEXT\n);")
}

func transactionColumns(t *testing.T, conn *sql.DB) map[string]bool {
	t.Helper()
	rows, err := conn.Query(`SELECT name FROM pragma_table_info('transactions')`)
	if err != nil {
		t.Fatalf("table_info: %v", err)
	}
	defer rows.Close()
	cols := map[string]bool{}
	for rows.Next() {
		var name string
		if err := rows.Scan(&name); err != nil {
			t.Fatalf("scan column: %v", err)
		}
		cols[name] = true
	}
	return cols
}

func syncVersion(t *testing.T, conn *sql.DB) int64 {
	t.Helper()
	var v int64
	if err := conn.QueryRow(`SELECT version FROM sync_meta WHERE id = 1`).Scan(&v); err != nil {
		t.Fatalf("sync_meta: %v", err)
	}
	return v
}

// TestFreshSchemaHasRecurringMarker: a brand-new database gets the three
// marker columns straight from schema.sql, plus the scheduled_id index.
func TestFreshSchemaHasRecurringMarker(t *testing.T) {
	conn, err := Open(t.TempDir())
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	defer conn.Close()
	cols := transactionColumns(t, conn)
	for _, c := range []string{"scheduled_id", "occurrence", "occurrence_total"} {
		if !cols[c] {
			t.Errorf("fresh transactions table missing %s", c)
		}
	}
	var idx int
	if err := conn.QueryRow(
		`SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'idx_transactions_scheduled'`,
	).Scan(&idx); err != nil || idx != 1 {
		t.Errorf("idx_transactions_scheduled: count %d, err %v", idx, err)
	}
}

// TestRecurringMarkerMigration: on a pre-marker database Open adds the
// columns and backfills scheduled_id from the scheduler's deterministic
// "sched-<id>-<date>" client_id, only when that schedule row exists and has
// the same owner. occurrence / occurrence_total stay NULL for history. A
// second Open changes nothing (not even sync versions).
func TestRecurringMarkerMigration(t *testing.T) {
	dir := t.TempDir()
	seed, err := sql.Open("sqlite", "file:"+filepath.Join(dir, "insitu-ledger.db")+"?_pragma=foreign_keys(on)")
	if err != nil {
		t.Fatalf("open seed db: %v", err)
	}
	if _, err := seed.Exec(preMarkerSchema(t)); err != nil {
		t.Fatalf("apply pre-marker schema: %v", err)
	}
	if cols := transactionColumns(t, seed); cols["scheduled_id"] || !cols["client_id"] {
		t.Fatalf("seed schema is not pre-marker: %v", cols)
	}
	if _, err := seed.Exec(`
		INSERT INTO users (id, username, email, name, password_hash) VALUES
		    (1, 'owner', 'o@x', 'Owner', 'h'),
		    (2, 'other', 'x@x', 'Other', 'h');
		INSERT INTO accounts (id, user_id, name) VALUES (1, 1, 'Wallet'), (2, 2, 'Theirs');
		INSERT INTO categories (id, user_id, name, type) VALUES (1, 1, 'Rent', 'expense'), (2, 2, 'X', 'expense');
		INSERT INTO scheduled_transactions (id, account_id, category_id, user_id, created_by_user_id, type, amount, rrule, next_occurrence) VALUES
		    (1, 1, 1, 1, 1, 'expense', 800, 'FREQ=MONTHLY', '2026-03-01T09:00:00+01:00'),
		    (12, 1, 1, 1, 1, 'expense', 50, 'FREQ=WEEKLY', '2026-03-01'),
		    (3, 2, 2, 2, 2, 'expense', 5, 'FREQ=DAILY', '2026-03-01');
		-- Exhausted schedules are soft-deleted, not removed: still linkable.
		INSERT INTO scheduled_transactions (id, account_id, category_id, user_id, created_by_user_id, type, amount, rrule, next_occurrence, max_occurrences, occurrence_count, active, deleted_at) VALUES
		    (2, 1, 1, 1, 1, 'expense', 20, 'FREQ=DAILY', '2026-01-06', 1, 1, 0, '2026-01-05 10:00:00');
		INSERT INTO transactions (id, account_id, category_id, user_id, created_by_user_id, type, amount, date, client_id) VALUES
		    (1, 1, 1, 1, 1, 'expense', 800, '2026-02-01T09:00:00+01:00', 'sched-1-2026-02-01T09:00:00+01:00'),
		    (2, 1, 1, 1, 1, 'expense', 20, '2026-01-05', 'sched-2-2026-01-05'),
		    (3, 1, 1, 1, 1, 'expense', 50, '2026-02-22', 'sched-12-2026-02-22'),
		    (4, 1, 1, 1, 1, 'expense', 9, '2026-01-05', 'sched-99-2026-01-05'),
		    (5, 1, 1, 1, 1, 'expense', 9, '2026-01-05', 'sched-3-2026-01-05'),
		    (6, 1, 1, 1, 1, 'expense', 9, '2026-01-05', 'sched-1a-2026-01-05'),
		    (7, 1, 1, 1, 1, 'expense', 9, '2026-01-05', 'SCHED-1-2026-01-05'),
		    (8, 1, 1, 1, 1, 'expense', 9, '2026-01-05', '2b1e7c1e-1f7a-4c39-9a53-0f3b2f4f9d10'),
		    (9, 1, 1, 1, 1, 'expense', 9, '2026-01-05', NULL);
	`); err != nil {
		t.Fatalf("seed rows: %v", err)
	}
	var beforeSV int64
	if err := seed.QueryRow(`SELECT sync_version FROM transactions WHERE id = 1`).Scan(&beforeSV); err != nil {
		t.Fatalf("read sync_version: %v", err)
	}
	seed.Close()

	conn, err := Open(dir)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	cols := transactionColumns(t, conn)
	for _, c := range []string{"scheduled_id", "occurrence", "occurrence_total"} {
		if !cols[c] {
			t.Fatalf("migration did not add %s", c)
		}
	}

	want := map[int64]sql.NullInt64{
		1: {Int64: 1, Valid: true},  // live schedule
		2: {Int64: 2, Valid: true},  // soft-deleted (exhausted) schedule
		3: {Int64: 12, Valid: true}, // multi-digit id, not read as 1
		4: {},                       // schedule does not exist
		5: {},                       // schedule belongs to another owner
		6: {},                       // id part is not all digits
		7: {},                       // prefix match is case-sensitive
		8: {},                       // client UUID
		9: {},                       // no client_id
	}
	check := func(conn *sql.DB, phase string) {
		t.Helper()
		for id, w := range want {
			var sid, occ, total sql.NullInt64
			if err := conn.QueryRow(
				`SELECT scheduled_id, occurrence, occurrence_total FROM transactions WHERE id = ?`, id,
			).Scan(&sid, &occ, &total); err != nil {
				t.Fatalf("%s: read txn %d: %v", phase, id, err)
			}
			if sid != w {
				t.Errorf("%s: txn %d scheduled_id = %+v, want %+v", phase, id, sid, w)
			}
			// Numbered from the schedule's occurrence_count: schedule 2 (one-time,
			// 1 of 1 created) gives txn 2 position 1 of 1. Schedules 1 and 12
			// report occurrence_count 0, so their entries can't be numbered.
			wantOcc, wantTotal := sql.NullInt64{}, sql.NullInt64{}
			if id == 2 {
				wantOcc, wantTotal = sql.NullInt64{Int64: 1, Valid: true}, sql.NullInt64{Int64: 1, Valid: true}
			}
			if occ != wantOcc || total != wantTotal {
				t.Errorf("%s: txn %d occurrence/total = %+v/%+v, want %+v/%+v", phase, id, occ, total, wantOcc, wantTotal)
			}
		}
	}
	check(conn, "first open")

	// Linked rows got a new sync_version so incremental clients re-pull them.
	var afterSV int64
	if err := conn.QueryRow(`SELECT sync_version FROM transactions WHERE id = 1`).Scan(&afterSV); err != nil {
		t.Fatalf("read sync_version: %v", err)
	}
	if afterSV <= beforeSV {
		t.Errorf("backfilled row sync_version = %d, want > %d", afterSV, beforeSV)
	}
	versionAfterFirst := syncVersion(t, conn)
	conn.Close()

	// Idempotent: a second Open touches no rows.
	again, err := Open(dir)
	if err != nil {
		t.Fatalf("re-open: %v", err)
	}
	defer again.Close()
	check(again, "second open")
	if v := syncVersion(t, again); v != versionAfterFirst {
		t.Errorf("second Open bumped sync_meta %d -> %d; backfill is not idempotent", versionAfterFirst, v)
	}

	// The ALTER-added column carries the FK: hard-deleting a schedule
	// (e.g. admin user deletion cascade) clears the link instead of failing.
	if _, err := again.Exec(`DELETE FROM scheduled_transactions WHERE id = 12`); err != nil {
		t.Fatalf("hard-delete schedule: %v", err)
	}
	var sid sql.NullInt64
	if err := again.QueryRow(`SELECT scheduled_id FROM transactions WHERE id = 3`).Scan(&sid); err != nil {
		t.Fatalf("read txn 3: %v", err)
	}
	if sid.Valid {
		t.Errorf("after schedule hard-delete: scheduled_id = %d, want NULL", sid.Int64)
	}
}

// TestRecurringMarkerNumbersHistory: entries a schedule created before the
// position was stored get it back by counting from the schedule's
// occurrence_count (the linked entries are its newest ones).
func TestRecurringMarkerNumbersHistory(t *testing.T) {
	dir := t.TempDir()
	conn, err := Open(dir)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if _, err := conn.Exec(`
		INSERT INTO users (id, username, email, name, password_hash) VALUES (5, 'owner', 'o@x', 'Owner', 'h');
		INSERT INTO accounts (id, user_id, name) VALUES (1, 5, 'Wallet');
		INSERT INTO categories (id, user_id, name, type) VALUES (1, 5, 'Gadgets', 'expense');
		INSERT INTO scheduled_transactions (id, account_id, category_id, user_id, created_by_user_id, type, amount, rrule, next_occurrence, max_occurrences, occurrence_count, active, deleted_at) VALUES
		    -- finished 5-part series; only its last three entries carry the sched- client_id
		    (7, 1, 1, 5, 5, 'expense', 100, 'FREQ=MONTHLY', '2026-10-01T09:30:00+02:00', 5, 5, 0, '2026-09-01 09:30:00'),
		    -- open-ended, two created
		    (8, 1, 1, 5, 5, 'expense', 25, 'FREQ=WEEKLY', '2026-11-20', NULL, 2, 1, NULL),
		    -- 3-part series: one old entry without a position, one new entry with it
		    (9, 1, 1, 5, 5, 'expense', 40, 'FREQ=MONTHLY', '2027-01-13T10:30:00+01:00', 3, 2, 1, NULL),
		    -- inconsistent: entry linked but the schedule reports none created
		    (10, 1, 1, 5, 5, 'expense', 5, 'FREQ=DAILY', '2026-11-20', NULL, 0, 1, NULL);
		INSERT INTO transactions (id, account_id, category_id, user_id, created_by_user_id, type, amount, date, scheduled_id, occurrence, occurrence_total, deleted_at) VALUES
		    (1, 1, 1, 5, 5, 'expense', 100, '2026-07-01T09:30:00+02:00', 7, NULL, NULL, NULL),
		    (2, 1, 1, 5, 5, 'expense', 100, '2026-08-01T09:30:00+02:00', 7, NULL, NULL, '2026-08-02 10:00:00'),
		    (3, 1, 1, 5, 5, 'expense', 100, '2026-09-01T09:30:00+02:00', 7, NULL, NULL, NULL),
		    (4, 1, 1, 5, 5, 'expense', 25, '2026-11-13', 8, NULL, NULL, NULL),
		    (5, 1, 1, 5, 5, 'expense', 40, '2026-11-13T10:30:00+01:00', 9, NULL, NULL, NULL),
		    (6, 1, 1, 5, 5, 'expense', 40, '2026-12-13T10:30:00+01:00', 9, 2, 3, NULL),
		    (7, 1, 1, 5, 5, 'expense', 5, '2026-11-19', 10, NULL, NULL, NULL),
		    (8, 1, 1, 5, 5, 'expense', 9, '2026-11-19', NULL, NULL, NULL, NULL);
	`); err != nil {
		t.Fatalf("seed: %v", err)
	}
	conn.Close()

	// Re-open: the migration step numbers the history.
	conn, err = Open(dir)
	if err != nil {
		t.Fatalf("re-open: %v", err)
	}
	type pos struct{ occ, total sql.NullInt64 }
	n := func(v int64) sql.NullInt64 { return sql.NullInt64{Int64: v, Valid: true} }
	want := map[int64]pos{
		1: {n(3), n(5)}, // counted back from occurrence_count 5
		2: {n(4), n(5)}, // soft-deleted entries count too
		3: {n(5), n(5)},
		4: {n(2), sql.NullInt64{}}, // open-ended: position, no total
		5: {n(1), n(3)},            // older than the new entry at position 2
		6: {n(2), n(3)},            // already numbered by the scheduler: untouched
		7: {},                      // would be 0: left NULL
		8: {},                      // ordinary entry
	}
	check := func(c *sql.DB, phase string) {
		t.Helper()
		for id, w := range want {
			var got pos
			if err := c.QueryRow(`SELECT occurrence, occurrence_total FROM transactions WHERE id = ?`, id).
				Scan(&got.occ, &got.total); err != nil {
				t.Fatalf("%s: read txn %d: %v", phase, id, err)
			}
			if got != w {
				t.Errorf("%s: txn %d = %+v/%+v, want %+v/%+v", phase, id, got.occ, got.total, w.occ, w.total)
			}
		}
	}
	check(conn, "after numbering")
	version := syncVersion(t, conn)
	conn.Close()

	again, err := Open(dir)
	if err != nil {
		t.Fatalf("third open: %v", err)
	}
	defer again.Close()
	check(again, "third open")
	if v := syncVersion(t, again); v != version {
		t.Errorf("numbering is not idempotent: sync_meta %d -> %d", version, v)
	}
}

// TestNormalizeScheduledDeletedAt: deleted_at values the scheduler stored in
// Go's time.Time.String() form become UTC "YYYY-MM-DD HH:MM:SS"; standard
// values, NULLs and unparseable text are left alone, and a re-open is a no-op.
func TestNormalizeScheduledDeletedAt(t *testing.T) {
	dir := t.TempDir()
	conn, err := Open(dir)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if _, err := conn.Exec(`
		INSERT INTO users (id, username, email, name, password_hash) VALUES (5, 'owner', 'o@x', 'Owner', 'h');
		INSERT INTO accounts (id, user_id, name) VALUES (1, 5, 'Wallet');
		INSERT INTO categories (id, user_id, name, type) VALUES (1, 5, 'Gadgets', 'expense');
		INSERT INTO scheduled_transactions (id, account_id, category_id, user_id, type, amount, rrule, next_occurrence, active, deleted_at) VALUES
		    (1, 1, 1, 5, 'expense', 1, 'FREQ=MONTHLY', '2026-12-13', 0, '2026-11-13 14:50:41.891332017 +0100 CET m=+3733860.011538766'),
		    (2, 1, 1, 5, 'expense', 1, 'FREQ=MONTHLY', '2026-10-01', 0, '2026-09-01 09:30:05.242051313 +0200 CEST m=+3453240.027580910'),
		    (3, 1, 1, 5, 'expense', 1, 'FREQ=MONTHLY', '2026-10-01', 0, '2026-09-01 07:30:05'),
		    (4, 1, 1, 5, 'expense', 1, 'FREQ=MONTHLY', '2026-10-01', 1, NULL),
		    (5, 1, 1, 5, 'expense', 1, 'FREQ=MONTHLY', '2026-10-01', 0, '2026-09-01 07:30:05 garbage');
	`); err != nil {
		t.Fatalf("seed: %v", err)
	}
	conn.Close()

	want := map[int64]sql.NullString{
		1: {String: "2026-11-13 13:50:41", Valid: true}, // CET is UTC+1
		2: {String: "2026-09-01 07:30:05", Valid: true}, // CEST is UTC+2
		3: {String: "2026-09-01 07:30:05", Valid: true},
		4: {},
		5: {String: "2026-09-01 07:30:05 garbage", Valid: true},
	}
	check := func(c *sql.DB, phase string) {
		t.Helper()
		for id, w := range want {
			var got sql.NullString
			// Raw stored text (the driver reformats DATETIME columns on read).
			if err := c.QueryRow(`SELECT CAST(deleted_at AS TEXT) FROM scheduled_transactions WHERE id = ?`, id).Scan(&got); err != nil {
				t.Fatalf("%s: read %d: %v", phase, id, err)
			}
			if got != w {
				t.Errorf("%s: schedule %d deleted_at = %+v, want %+v", phase, id, got, w)
			}
		}
	}

	conn, err = Open(dir)
	if err != nil {
		t.Fatalf("re-open: %v", err)
	}
	check(conn, "after normalize")
	version := syncVersion(t, conn)
	conn.Close()

	again, err := Open(dir)
	if err != nil {
		t.Fatalf("third open: %v", err)
	}
	defer again.Close()
	check(again, "third open")
	if v := syncVersion(t, again); v != version {
		t.Errorf("normalize is not idempotent: sync_meta %d -> %d", version, v)
	}
}
