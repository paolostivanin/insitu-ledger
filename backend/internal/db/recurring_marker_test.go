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
			if occ.Valid || total.Valid {
				t.Errorf("%s: txn %d occurrence/total = %+v/%+v, want NULL (history)", phase, id, occ, total)
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
