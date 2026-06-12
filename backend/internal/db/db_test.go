package db

import (
	"database/sql"
	"path/filepath"
	"strings"
	"testing"
	"time"

	_ "modernc.org/sqlite"
)

// TestMigrateLegacySharedAccess seeds a v1.12.0-shaped database with the old
// shared_access table populated, runs Open(), and asserts the legacy rows
// have been expanded into one shared_account_access row per account the
// owner had at migration time, and that the legacy table is dropped.
func TestMigrateLegacySharedAccess(t *testing.T) {
	dir := t.TempDir()

	// Pre-create the database file with the legacy schema and seed data.
	dbPath := filepath.Join(dir, "insitu-ledger.db")
	conn, err := sql.Open("sqlite", "file:"+dbPath+"?_pragma=foreign_keys(on)")
	if err != nil {
		t.Fatalf("open seed db: %v", err)
	}
	if _, err := conn.Exec(`
		CREATE TABLE users (
		    id INTEGER PRIMARY KEY AUTOINCREMENT,
		    username TEXT NOT NULL UNIQUE,
		    email TEXT NOT NULL UNIQUE,
		    name TEXT NOT NULL,
		    password_hash TEXT NOT NULL,
		    is_admin INTEGER NOT NULL DEFAULT 0,
		    force_password_change INTEGER NOT NULL DEFAULT 0,
		    totp_secret TEXT,
		    totp_enabled INTEGER NOT NULL DEFAULT 0,
		    created_at DATETIME NOT NULL DEFAULT (datetime('now')),
		    updated_at DATETIME NOT NULL DEFAULT (datetime('now')),
		    sync_version INTEGER NOT NULL DEFAULT 0
		);
		CREATE TABLE accounts (
		    id INTEGER PRIMARY KEY AUTOINCREMENT,
		    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
		    name TEXT NOT NULL,
		    currency TEXT NOT NULL DEFAULT 'EUR',
		    balance REAL NOT NULL DEFAULT 0.0,
		    created_at DATETIME NOT NULL DEFAULT (datetime('now')),
		    updated_at DATETIME NOT NULL DEFAULT (datetime('now')),
		    deleted_at DATETIME,
		    sync_version INTEGER NOT NULL DEFAULT 0
		);
		CREATE TABLE shared_access (
		    id INTEGER PRIMARY KEY AUTOINCREMENT,
		    owner_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
		    guest_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
		    permission TEXT NOT NULL CHECK (permission IN ('read', 'write')),
		    created_at DATETIME NOT NULL DEFAULT (datetime('now')),
		    sync_version INTEGER NOT NULL DEFAULT 0,
		    UNIQUE(owner_user_id, guest_user_id)
		);

		INSERT INTO users (username, email, name, password_hash) VALUES ('owner', 'o@x', 'Owner', 'h');
		INSERT INTO users (username, email, name, password_hash) VALUES ('guest', 'g@x', 'Guest', 'h');
		INSERT INTO accounts (user_id, name) VALUES (1, 'Wallet');
		INSERT INTO accounts (user_id, name) VALUES (1, 'Savings');
		INSERT INTO accounts (user_id, name) VALUES (1, 'Deleted');
		UPDATE accounts SET deleted_at = datetime('now') WHERE name = 'Deleted';
		INSERT INTO shared_access (owner_user_id, guest_user_id, permission) VALUES (1, 2, 'write');
	`); err != nil {
		t.Fatalf("seed legacy schema: %v", err)
	}
	conn.Close()

	// Now run Open(), which should apply the schema, ALTER TABLEs, and the
	// legacy migration.
	migrated, err := Open(dir)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	defer migrated.Close()

	// Legacy table is gone.
	var legacyCount int
	if err := migrated.QueryRow("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='shared_access'").Scan(&legacyCount); err != nil {
		t.Fatalf("probe legacy: %v", err)
	}
	if legacyCount != 0 {
		t.Errorf("shared_access still present after migration")
	}

	// One row per non-deleted account = 2 (Wallet, Savings).
	rows, err := migrated.Query(`
		SELECT a.name, s.permission FROM shared_account_access s
		JOIN accounts a ON a.id = s.account_id
		WHERE s.owner_user_id = 1 AND s.guest_user_id = 2
		ORDER BY a.name`)
	if err != nil {
		t.Fatalf("query expanded shares: %v", err)
	}
	defer rows.Close()

	type row struct{ name, perm string }
	var got []row
	for rows.Next() {
		var r row
		if err := rows.Scan(&r.name, &r.perm); err != nil {
			t.Fatalf("scan: %v", err)
		}
		got = append(got, r)
	}
	if len(got) != 2 {
		t.Fatalf("expected 2 expanded shares (Wallet, Savings), got %d: %+v", len(got), got)
	}
	for _, r := range got {
		if r.perm != "write" {
			t.Errorf("permission for %s = %s, want write", r.name, r.perm)
		}
		if r.name == "Deleted" {
			t.Errorf("soft-deleted account should not have been expanded")
		}
	}

	// users.default_account_id column exists (ALTER TABLE applied).
	if _, err := migrated.Exec("UPDATE users SET default_account_id = NULL WHERE id = 1"); err != nil {
		t.Errorf("default_account_id column missing: %v", err)
	}

	// Re-running Open is a no-op (no legacy table to migrate).
	again, err := Open(dir)
	if err != nil {
		t.Fatalf("re-open: %v", err)
	}
	again.Close()
}

// TestTZMigration verifies the shape-gated TZ backfill:
//   - 16-char naive datetime → ":00" + server offset appended
//   - 19-char naive datetime → server offset appended
//   - date-only → left verbatim (TZ-agnostic by design)
//   - already-offset → left verbatim
//   - already-Z → left verbatim
//   - the migration is idempotent (second Open() touches zero rows)
func TestTZMigration(t *testing.T) {
	dir := t.TempDir()

	// First Open: lets schema.sql build everything cleanly. Migration runs on
	// empty data — no-op.
	conn, err := Open(dir)
	if err != nil {
		t.Fatalf("first Open: %v", err)
	}

	// Seed user/account/category, then insert rows of each date shape.
	if _, err := conn.Exec(`
		INSERT INTO users (username, email, name, password_hash) VALUES ('u', 'u@x', 'U', 'h');
		INSERT INTO accounts (user_id, name) VALUES (1, 'W');
		INSERT INTO categories (user_id, name, type) VALUES (1, 'C', 'expense');
		INSERT INTO transactions (account_id, category_id, user_id, type, amount, date) VALUES
		    (1, 1, 1, 'expense', 1, '2026-06-11T08:41'),
		    (1, 1, 1, 'expense', 2, '2026-06-11T08:41:05'),
		    (1, 1, 1, 'expense', 3, '2026-06-11'),
		    (1, 1, 1, 'expense', 4, '2026-06-11T08:41:00+03:00'),
		    (1, 1, 1, 'expense', 5, '2026-06-11T08:41:00Z');
		INSERT INTO scheduled_transactions (account_id, category_id, user_id, type, amount, rrule, next_occurrence) VALUES
		    (1, 1, 1, 'expense', 1, 'FREQ=DAILY', '2026-06-11T08:41'),
		    (1, 1, 1, 'expense', 2, 'FREQ=DAILY', '2026-06-11');
	`); err != nil {
		t.Fatalf("seed: %v", err)
	}
	conn.Close()

	// Second Open: the TZ migration runs against the seeded rows.
	migrated, err := Open(dir)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}

	wantOffset := time.Now().Format("-07:00") // server's current offset

	checks := []struct {
		id   int
		want string
	}{
		{1, "2026-06-11T08:41:00" + wantOffset},  // 16-char → +seconds +offset
		{2, "2026-06-11T08:41:05" + wantOffset},  // 19-char → +offset only
		{3, "2026-06-11"},                        // date-only → verbatim
		{4, "2026-06-11T08:41:00+03:00"},         // offset-bearing → verbatim
		{5, "2026-06-11T08:41:00Z"},              // Z → verbatim
	}
	for _, c := range checks {
		var got string
		if err := migrated.QueryRow(`SELECT date FROM transactions WHERE id = ?`, c.id).Scan(&got); err != nil {
			t.Fatalf("scan id %d: %v", c.id, err)
		}
		if got != c.want {
			t.Errorf("id %d: date = %q, want %q", c.id, got, c.want)
		}
	}

	// scheduled_transactions: 16-char gets normalized, date-only stays.
	var nextOcc string
	if err := migrated.QueryRow(`SELECT next_occurrence FROM scheduled_transactions WHERE id = 1`).Scan(&nextOcc); err != nil {
		t.Fatalf("scan scheduled 1: %v", err)
	}
	if !strings.HasPrefix(nextOcc, "2026-06-11T08:41:00") || !strings.HasSuffix(nextOcc, wantOffset) {
		t.Errorf("scheduled 1 next_occurrence = %q, want 2026-06-11T08:41:00%s", nextOcc, wantOffset)
	}
	if err := migrated.QueryRow(`SELECT next_occurrence FROM scheduled_transactions WHERE id = 2`).Scan(&nextOcc); err != nil {
		t.Fatalf("scan scheduled 2: %v", err)
	}
	if nextOcc != "2026-06-11" {
		t.Errorf("scheduled 2 next_occurrence = %q, want %q (date-only verbatim)", nextOcc, "2026-06-11")
	}

	// Re-open: migration must be a no-op. Snapshot all dates first; assert
	// they're identical after the third Open.
	snap := map[int]string{}
	rows, err := migrated.Query(`SELECT id, date FROM transactions ORDER BY id`)
	if err != nil {
		t.Fatalf("snapshot query: %v", err)
	}
	for rows.Next() {
		var id int
		var d string
		rows.Scan(&id, &d)
		snap[id] = d
	}
	rows.Close()
	migrated.Close()

	again, err := Open(dir)
	if err != nil {
		t.Fatalf("re-open: %v", err)
	}
	for id, want := range snap {
		var got string
		if err := again.QueryRow(`SELECT date FROM transactions WHERE id = ?`, id).Scan(&got); err != nil {
			t.Fatalf("re-scan id %d: %v", id, err)
		}
		if got != want {
			t.Errorf("re-open id %d: date drifted from %q to %q (migration not idempotent)", id, want, got)
		}
	}
	again.Close()
}
