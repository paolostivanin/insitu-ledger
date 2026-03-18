package api

import (
	"encoding/json"
	"net/http"
	"strconv"
)

type sharedAccessRequest struct {
	GuestEmail string `json:"guest_email"`
	Permission string `json:"permission"` // "read" or "write"
}

func (s *Server) handleListSharedAccess(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	rows, err := s.DB.Query(
		`SELECT sa.id, sa.owner_user_id, sa.guest_user_id, sa.permission, u.name, u.email
		 FROM shared_access sa
		 JOIN users u ON sa.guest_user_id = u.id
		 WHERE sa.owner_user_id = ?`, userID,
	)
	if err != nil {
		http.Error(w, "query error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	var items []map[string]any
	for rows.Next() {
		var id, ownerID, guestID int64
		var permission, guestName, guestEmail string
		rows.Scan(&id, &ownerID, &guestID, &permission, &guestName, &guestEmail)
		items = append(items, map[string]any{
			"id": id, "owner_user_id": ownerID, "guest_user_id": guestID,
			"permission": permission, "guest_name": guestName, "guest_email": guestEmail,
		})
	}

	if items == nil {
		items = []map[string]any{}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(items)
}

func (s *Server) handleCreateSharedAccess(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())
	var req sharedAccessRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	if req.Permission != "read" && req.Permission != "write" {
		http.Error(w, "permission must be 'read' or 'write'", http.StatusBadRequest)
		return
	}

	// Find guest user by email
	var guestID int64
	err := s.DB.QueryRow("SELECT id FROM users WHERE email = ?", req.GuestEmail).Scan(&guestID)
	if err != nil {
		http.Error(w, "user not found", http.StatusNotFound)
		return
	}

	if guestID == userID {
		http.Error(w, "cannot share with yourself", http.StatusBadRequest)
		return
	}

	result, err := s.DB.Exec(
		"INSERT INTO shared_access (owner_user_id, guest_user_id, permission) VALUES (?, ?, ?)",
		userID, guestID, req.Permission,
	)
	if err != nil {
		http.Error(w, "already shared with this user", http.StatusConflict)
		return
	}

	id, _ := result.LastInsertId()
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(map[string]any{"id": id})
}

func (s *Server) handleListAccessibleOwners(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	rows, err := s.DB.Query(
		`SELECT sa.owner_user_id, u.name, u.email, sa.permission
		 FROM shared_access sa
		 JOIN users u ON sa.owner_user_id = u.id
		 WHERE sa.guest_user_id = ?`, userID,
	)
	if err != nil {
		http.Error(w, "query error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	var items []map[string]any
	for rows.Next() {
		var ownerID int64
		var name, email, permission string
		rows.Scan(&ownerID, &name, &email, &permission)
		items = append(items, map[string]any{
			"owner_user_id": ownerID, "name": name, "email": email, "permission": permission,
		})
	}

	if items == nil {
		items = []map[string]any{}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(items)
}

func (s *Server) handleDeleteSharedAccess(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}

	result, err := s.DB.Exec("DELETE FROM shared_access WHERE id = ? AND owner_user_id = ?", id, userID)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	rows, _ := result.RowsAffected()
	if rows == 0 {
		http.Error(w, "shared access not found", http.StatusNotFound)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
