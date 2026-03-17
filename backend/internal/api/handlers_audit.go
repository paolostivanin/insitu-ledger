package api

import (
	"database/sql"
	"encoding/json"
	"log"
	"net/http"
	"strconv"
)

func writeAuditLog(db *sql.DB, adminID int64, action string, targetUserID *int64, details, ip string) {
	if _, err := db.Exec(
		"INSERT INTO audit_logs (admin_user_id, action, target_user_id, details, ip_address) VALUES (?, ?, ?, ?, ?)",
		adminID, action, targetUserID, details, ip,
	); err != nil {
		log.Printf("audit log write failed: %v", err)
	}
}

func clientIP(r *http.Request) string {
	if fwd := r.Header.Get("X-Forwarded-For"); fwd != "" {
		return fwd
	}
	return r.RemoteAddr
}

func (s *Server) handleAdminAuditLogs(w http.ResponseWriter, r *http.Request) {
	limitVal, offsetVal, err := parsePagination(r.URL.Query().Get("limit"), r.URL.Query().Get("offset"))
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	query := `SELECT al.id, al.admin_user_id, u.username, al.action, al.target_user_id, al.details, al.ip_address, al.created_at
		FROM audit_logs al
		JOIN users u ON u.id = al.admin_user_id
		ORDER BY al.created_at DESC
		LIMIT ? OFFSET ?`
	args := []any{limitVal, offsetVal}

	rows, err := s.DB.Query(query, args...)
	if err != nil {
		http.Error(w, "query error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	var logs []map[string]any
	for rows.Next() {
		var id, adminUserID int64
		var adminUsername, action, createdAt string
		var targetUserID *int64
		var details, ipAddress *string

		if err := rows.Scan(&id, &adminUserID, &adminUsername, &action, &targetUserID, &details, &ipAddress, &createdAt); err != nil {
			http.Error(w, "scan error", http.StatusInternalServerError)
			return
		}

		entry := map[string]any{
			"id":             id,
			"admin_user_id":  adminUserID,
			"admin_username": adminUsername,
			"action":         action,
			"target_user_id": targetUserID,
			"details":        details,
			"ip_address":     ipAddress,
			"created_at":     createdAt,
		}
		logs = append(logs, entry)
	}

	if logs == nil {
		logs = []map[string]any{}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(logs)
}

func int64Ptr(id int64) *int64 {
	return &id
}

func strconvInt64(s string) int64 {
	v, _ := strconv.ParseInt(s, 10, 64)
	return v
}
