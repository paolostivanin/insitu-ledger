package db

import (
	"database/sql"
	_ "embed"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"

	"golang.org/x/crypto/bcrypt"
	_ "modernc.org/sqlite"
)

//go:embed schema.sql
var schemaSQL string

// Open opens (or creates) the SQLite database at the given path,
// applies the schema, and configures WAL mode.
func Open(dataDir string) (*sql.DB, error) {
	if err := os.MkdirAll(dataDir, 0750); err != nil {
		return nil, fmt.Errorf("create data dir: %w", err)
	}

	dbPath := filepath.Join(dataDir, "insitu-ledger.db")
	dsn := fmt.Sprintf("file:%s?_pragma=journal_mode(wal)&_pragma=foreign_keys(on)&_pragma=busy_timeout(5000)&_pragma=synchronous(normal)", dbPath)

	conn, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("open database: %w", err)
	}

	// WAL mode allows concurrent readers with a single writer.
	// SQLite serializes writes internally, so multiple connections are safe.
	conn.SetMaxOpenConns(4)
	conn.SetMaxIdleConns(4)

	if _, err := conn.Exec(schemaSQL); err != nil {
		conn.Close()
		return nil, fmt.Errorf("apply schema: %w", err)
	}

	// Migrations for existing databases (ALTER TABLE ADD COLUMN is idempotent-safe: ignore "duplicate column" errors)
	migrations := []string{
		"ALTER TABLE scheduled_transactions ADD COLUMN max_occurrences INTEGER",
		"ALTER TABLE scheduled_transactions ADD COLUMN occurrence_count INTEGER NOT NULL DEFAULT 0",
	}
	for _, m := range migrations {
		if _, err := conn.Exec(m); err != nil {
			// Only ignore "duplicate column" errors — everything else is a real problem.
			if !strings.Contains(err.Error(), "duplicate column") {
				conn.Close()
				return nil, fmt.Errorf("migration error: %w", err)
			}
		}
	}

	seedDefaultAdmin(conn)

	return conn, nil
}

// seedDefaultAdmin creates the default admin user if no users exist.
func seedDefaultAdmin(db *sql.DB) {
	var count int
	db.QueryRow("SELECT COUNT(*) FROM users").Scan(&count)
	if count > 0 {
		return
	}

	hash, err := bcrypt.GenerateFromPassword([]byte("admin"), bcrypt.DefaultCost)
	if err != nil {
		log.Printf("seed admin: failed to hash password: %v", err)
		return
	}

	_, err = db.Exec(
		`INSERT INTO users (username, email, name, password_hash, is_admin, force_password_change)
		 VALUES ('admin', 'admin@localhost', 'Administrator', ?, 1, 1)`,
		string(hash),
	)
	if err != nil {
		log.Printf("seed admin: failed to create user: %v", err)
		return
	}

	log.Println("Created default admin user (login: admin@localhost / admin)")
}
