package db

import (
	"database/sql"
	"path/filepath"
	"testing"

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
