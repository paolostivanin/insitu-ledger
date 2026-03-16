package api

import (
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"time"
)

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
