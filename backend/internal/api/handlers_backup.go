package api

import (
	"encoding/json"
	"fmt"
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
	writeAuditLog(s.DB, adminID, "update_backup_settings", nil, fmt.Sprintf("enabled=%v freq=%s retention=%d", req.Enabled, req.Frequency, req.RetentionCount), clientIP(r))

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
	defer os.Remove(backupPath)

	writeAuditLog(s.DB, adminID, "backup_download", nil, "", clientIP(r))

	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=insitu-backup-%s.db", time.Now().Format("2006-01-02")))
	http.ServeFile(w, r, backupPath)
}
