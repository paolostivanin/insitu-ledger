package api

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"time"
)

func (s *Server) handleGetBackupSettings(w http.ResponseWriter, r *http.Request) {
	var enabled bool
	var frequency string
	var retentionCount int
	var lastBackupAt *string

	err := s.DB.QueryRow(
		"SELECT enabled, frequency, retention_count, last_backup_at FROM backup_settings WHERE id = 1",
	).Scan(&enabled, &frequency, &retentionCount, &lastBackupAt)
	if err != nil {
		http.Error(w, "failed to read backup settings", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"enabled":         enabled,
		"frequency":       frequency,
		"retention_count": retentionCount,
		"last_backup_at":  lastBackupAt,
	})
}

func (s *Server) handleUpdateBackupSettings(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Enabled        bool   `json:"enabled"`
		Frequency      string `json:"frequency"`
		RetentionCount int    `json:"retention_count"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	if req.Frequency != "daily" && req.Frequency != "weekly" && req.Frequency != "monthly" {
		http.Error(w, "frequency must be daily, weekly, or monthly", http.StatusBadRequest)
		return
	}
	if req.RetentionCount < 1 {
		req.RetentionCount = 1
	}

	_, err := s.DB.Exec(
		"UPDATE backup_settings SET enabled = ?, frequency = ?, retention_count = ?, updated_at = datetime('now') WHERE id = 1",
		req.Enabled, req.Frequency, req.RetentionCount,
	)
	if err != nil {
		http.Error(w, "failed to update backup settings", http.StatusInternalServerError)
		return
	}

	adminID := UserIDFromContext(r.Context())
	writeAuditLog(s.DB, adminID, "update_backup_settings", nil, fmt.Sprintf("enabled=%v freq=%s retention=%d", req.Enabled, req.Frequency, req.RetentionCount), s.clientIP(r))

	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleAdminBackup(w http.ResponseWriter, r *http.Request) {
	adminID := UserIDFromContext(r.Context())

	// Create temp file for backup
	tmpDir := os.TempDir()
	backupPath := filepath.Join(tmpDir, fmt.Sprintf("insitu-backup-%d.db", time.Now().Unix()))

	// Use VACUUM INTO for a clean, consistent copy
	_, err := s.DB.Exec("VACUUM INTO ?", backupPath)
	if err != nil {
		log.Printf("backup error: %v", err)
		http.Error(w, "backup failed", http.StatusInternalServerError)
		return
	}

	// Open the file, then remove the directory entry. The OS keeps the data
	// accessible via the file handle until it is closed, avoiding a race
	// between serving and cleanup.
	f, err := os.Open(backupPath)
	if err != nil {
		http.Error(w, "backup failed", http.StatusInternalServerError)
		return
	}
	defer f.Close()
	os.Remove(backupPath)

	fi, err := f.Stat()
	if err != nil {
		http.Error(w, "backup failed", http.StatusInternalServerError)
		return
	}

	writeAuditLog(s.DB, adminID, "backup_download", nil, "", s.clientIP(r))

	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=insitu-backup-%s.db", time.Now().Format("2006-01-02")))
	w.Header().Set("Content-Length", fmt.Sprintf("%d", fi.Size()))
	io.Copy(w, f)
}

// maxRestoreUploadSize caps a restore upload at 200 MB. SQLite databases for
// this app are small (KB to a few MB even with years of history); 200 MB is a
// generous safety ceiling, not an expected size.
const maxRestoreUploadSize = 200 << 20

// sqliteMagic is the 16-byte header SQLite writes at the start of every DB
// file. Cheap first-pass validation before we commit to opening the file.
var sqliteMagic = []byte("SQLite format 3\x00")

// requiredRestoreTables lists tables that must exist in an uploaded backup
// for it to be considered a valid InSitu Ledger DB. We don't compare full
// schema (column-by-column) — that would block restoring older backups across
// minor schema migrations. The migrations in db.Open are additive and
// idempotent, so a restored DB missing a recent ADD COLUMN will be migrated
// up on the next startup.
var requiredRestoreTables = []string{
	"users", "accounts", "categories", "transactions",
	"scheduled_transactions", "sessions", "backup_settings",
	"audit_logs", "sync_meta",
}

func (s *Server) handleAdminRestore(w http.ResponseWriter, r *http.Request) {
	if s.DataDir == "" {
		http.Error(w, "restore not configured", http.StatusInternalServerError)
		return
	}
	adminID := UserIDFromContext(r.Context())

	r.Body = http.MaxBytesReader(w, r.Body, maxRestoreUploadSize)
	if err := r.ParseMultipartForm(8 << 20); err != nil {
		http.Error(w, "upload too large or malformed", http.StatusRequestEntityTooLarge)
		return
	}
	file, _, err := r.FormFile("file")
	if err != nil {
		http.Error(w, "missing file field", http.StatusBadRequest)
		return
	}
	defer file.Close()

	// Stream to a temp file in dataDir so the final os.Rename is atomic
	// (rename across filesystems is not).
	stagingPath := filepath.Join(s.DataDir, fmt.Sprintf(".restore-staging-%d.db", time.Now().UnixNano()))
	staging, err := os.OpenFile(stagingPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0600)
	if err != nil {
		http.Error(w, "failed to stage upload", http.StatusInternalServerError)
		return
	}
	cleanupStaging := func() { os.Remove(stagingPath) }
	if _, err := io.Copy(staging, file); err != nil {
		staging.Close()
		cleanupStaging()
		http.Error(w, "failed to read upload", http.StatusBadRequest)
		return
	}
	if err := staging.Close(); err != nil {
		cleanupStaging()
		http.Error(w, "failed to write staging file", http.StatusInternalServerError)
		return
	}

	if err := validateRestoreFile(stagingPath); err != nil {
		cleanupStaging()
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	// Snapshot the current DB before we overwrite it. VACUUM INTO produces a
	// consistent single-file copy without touching WAL/SHM sidecars.
	backupDir := filepath.Join(s.DataDir, "backups")
	if err := os.MkdirAll(backupDir, 0750); err != nil {
		cleanupStaging()
		http.Error(w, "failed to create backups dir", http.StatusInternalServerError)
		return
	}
	preRestorePath := filepath.Join(backupDir, fmt.Sprintf("pre-restore-%s.db", time.Now().Format("2006-01-02_150405")))
	if _, err := s.DB.Exec("VACUUM INTO ?", preRestorePath); err != nil {
		cleanupStaging()
		log.Printf("restore: pre-restore snapshot failed: %v", err)
		http.Error(w, "failed to snapshot current DB", http.StatusInternalServerError)
		return
	}

	livePath := filepath.Join(s.DataDir, "insitu-ledger.db")

	// Close the live pool so SQLite releases file handles before we rename.
	// Other goroutines (scheduler, rate limiters) may still try to use s.DB
	// after this point, but we're about to terminate the process anyway.
	if err := s.DB.Close(); err != nil {
		log.Printf("restore: closing live DB pool: %v", err)
	}

	// Remove WAL/SHM sidecars from the OLD DB. Leaving them around would let
	// SQLite mistakenly recover the OLD WAL into the NEW main file on next
	// open, corrupting the restored data.
	for _, suffix := range []string{"-wal", "-shm"} {
		_ = os.Remove(livePath + suffix)
	}

	if err := os.Rename(stagingPath, livePath); err != nil {
		log.Printf("restore: rename staging over live DB: %v", err)
		http.Error(w, "failed to install restored DB", http.StatusInternalServerError)
		return
	}

	// Audit-log the restore in the freshly restored DB so the trail lives
	// alongside the data the operator will use after restart.
	if auditConn, err := sql.Open("sqlite", "file:"+livePath+"?_pragma=journal_mode(wal)&_pragma=foreign_keys(on)"); err == nil {
		writeAuditLog(auditConn, adminID, "restore", nil, fmt.Sprintf("pre-restore snapshot: %s", filepath.Base(preRestorePath)), s.clientIP(r))
		auditConn.Close()
	} else {
		log.Printf("restore: audit log open failed: %v", err)
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"status":              "restored",
		"restarting":          true,
		"pre_restore_snapshot": filepath.Base(preRestorePath),
	})
	if f, ok := w.(http.Flusher); ok {
		f.Flush()
	}

	// Trigger restart after the response has flushed. The default hook in
	// main.go sends SIGTERM; tests inject a no-op. The small delay gives the
	// HTTP layer time to actually send the body before the server starts
	// shutting down.
	if s.OnRestoreComplete != nil {
		hook := s.OnRestoreComplete
		time.AfterFunc(200*time.Millisecond, hook)
	}
}

func validateRestoreFile(path string) error {
	f, err := os.Open(path)
	if err != nil {
		return fmt.Errorf("open upload: %w", err)
	}
	header := make([]byte, 16)
	n, _ := io.ReadFull(f, header)
	f.Close()
	if n < 16 || !bytes.Equal(header, sqliteMagic) {
		return fmt.Errorf("not a SQLite database file")
	}

	// Read-only, immutable-ish open: we don't want our validation to create
	// a -wal sidecar next to the staging file.
	dsn := fmt.Sprintf("file:%s?mode=ro&_pragma=query_only(true)", path)
	conn, err := sql.Open("sqlite", dsn)
	if err != nil {
		return fmt.Errorf("open SQLite database: %w", err)
	}
	defer conn.Close()

	var integrity string
	if err := conn.QueryRow("PRAGMA integrity_check").Scan(&integrity); err != nil {
		return fmt.Errorf("integrity check failed: %w", err)
	}
	if integrity != "ok" {
		return fmt.Errorf("database integrity check failed: %s", integrity)
	}

	for _, table := range requiredRestoreTables {
		var name string
		err := conn.QueryRow(
			"SELECT name FROM sqlite_master WHERE type='table' AND name=?",
			table,
		).Scan(&name)
		if err == sql.ErrNoRows {
			return fmt.Errorf("backup is missing required table %q", table)
		}
		if err != nil {
			return fmt.Errorf("schema check: %w", err)
		}
	}

	var adminCount int
	if err := conn.QueryRow("SELECT COUNT(*) FROM users WHERE is_admin = 1").Scan(&adminCount); err != nil {
		return fmt.Errorf("admin user check: %w", err)
	}
	if adminCount == 0 {
		return fmt.Errorf("backup contains no admin users; restoring would lock you out")
	}

	return nil
}
