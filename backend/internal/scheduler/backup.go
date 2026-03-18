package scheduler

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// StartBackup launches a goroutine that performs automatic database backups
// based on the schedule configured in backup_settings.
func StartBackup(ctx context.Context, db *sql.DB, dataDir string, checkInterval time.Duration) {
	backupDir := filepath.Join(dataDir, "backups")
	if err := os.MkdirAll(backupDir, 0750); err != nil {
		log.Printf("backup-scheduler: failed to create backup dir: %v", err)
		return
	}

	go func() {
		ticker := time.NewTicker(checkInterval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				log.Println("backup-scheduler: shutting down")
				return
			case <-ticker.C:
				runBackupIfDue(db, backupDir)
			}
		}
	}()
}

func runBackupIfDue(db *sql.DB, backupDir string) {
	var enabled bool
	var frequency string
	var retentionCount int
	var lastBackupAt *string

	err := db.QueryRow(
		"SELECT enabled, frequency, retention_count, last_backup_at FROM backup_settings WHERE id = 1",
	).Scan(&enabled, &frequency, &retentionCount, &lastBackupAt)
	if err != nil {
		return
	}

	if !enabled {
		return
	}

	if !isDue(lastBackupAt, frequency) {
		return
	}

	// Perform backup
	backupPath := filepath.Join(backupDir, fmt.Sprintf("insitu-backup-%s.db", time.Now().Format("2006-01-02_150405")))
	if _, err := db.Exec("VACUUM INTO ?", backupPath); err != nil {
		log.Printf("backup-scheduler: backup failed: %v", err)
		return
	}

	// Update last_backup_at
	db.Exec("UPDATE backup_settings SET last_backup_at = datetime('now'), updated_at = datetime('now') WHERE id = 1")

	log.Printf("backup-scheduler: backup created: %s", backupPath)

	// Prune old backups
	pruneBackups(backupDir, retentionCount)
}

func isDue(lastBackupAt *string, frequency string) bool {
	if lastBackupAt == nil || *lastBackupAt == "" {
		return true
	}

	last, err := time.Parse("2006-01-02 15:04:05", *lastBackupAt)
	if err != nil {
		return true
	}

	now := time.Now()
	switch frequency {
	case "daily":
		return now.Sub(last) >= 24*time.Hour
	case "weekly":
		return now.Sub(last) >= 7*24*time.Hour
	case "monthly":
		return now.Sub(last) >= 30*24*time.Hour
	default:
		return now.Sub(last) >= 24*time.Hour
	}
}

func pruneBackups(backupDir string, keep int) {
	entries, err := os.ReadDir(backupDir)
	if err != nil {
		return
	}

	var backups []string
	for _, e := range entries {
		if !e.IsDir() && strings.HasPrefix(e.Name(), "insitu-backup-") && strings.HasSuffix(e.Name(), ".db") {
			backups = append(backups, filepath.Join(backupDir, e.Name()))
		}
	}

	if len(backups) <= keep {
		return
	}

	sort.Strings(backups)
	for _, path := range backups[:len(backups)-keep] {
		if err := os.Remove(path); err != nil {
			log.Printf("backup-scheduler: failed to remove old backup %s: %v", path, err)
		} else {
			log.Printf("backup-scheduler: pruned old backup: %s", path)
		}
	}
}
