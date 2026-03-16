package api

import (
	"encoding/json"
	"net/http"
	"strconv"
)

type categoryRequest struct {
	ParentID *int64  `json:"parent_id"`
	Name     string  `json:"name"`
	Type     string  `json:"type"`
	Icon     *string `json:"icon"`
	Color    *string `json:"color"`
}

func (s *Server) handleListCategories(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	rows, err := s.DB.Query(
		`SELECT id, user_id, parent_id, name, type, icon, color, created_at, updated_at, sync_version
		 FROM categories WHERE user_id = ? AND deleted_at IS NULL ORDER BY name`,
		userID,
	)
	if err != nil {
		http.Error(w, "query error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	var cats []map[string]any
	for rows.Next() {
		var id, uid, syncVersion int64
		var parentID *int64
		var name, typ, createdAt, updatedAt string
		var icon, color *string

		if err := rows.Scan(&id, &uid, &parentID, &name, &typ, &icon, &color, &createdAt, &updatedAt, &syncVersion); err != nil {
			http.Error(w, "scan error", http.StatusInternalServerError)
			return
		}
		cats = append(cats, map[string]any{
			"id": id, "user_id": uid, "parent_id": parentID, "name": name, "type": typ,
			"icon": icon, "color": color, "created_at": createdAt, "updated_at": updatedAt,
			"sync_version": syncVersion,
		})
	}

	if cats == nil {
		cats = []map[string]any{}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(cats)
}

func (s *Server) handleCreateCategory(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())
	var req categoryRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	if req.Type != "income" && req.Type != "expense" {
		http.Error(w, "type must be 'income' or 'expense'", http.StatusBadRequest)
		return
	}

	result, err := s.DB.Exec(
		`INSERT INTO categories (user_id, parent_id, name, type, icon, color)
		 VALUES (?, ?, ?, ?, ?, ?)`,
		userID, req.ParentID, req.Name, req.Type, req.Icon, req.Color,
	)
	if err != nil {
		http.Error(w, "failed to create category", http.StatusInternalServerError)
		return
	}

	id, _ := result.LastInsertId()
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(map[string]any{"id": id})
}

func (s *Server) handleUpdateCategory(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}

	var req categoryRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	_, err = s.DB.Exec(
		`UPDATE categories SET parent_id=?, name=?, type=?, icon=?, color=?
		 WHERE id=? AND user_id=?`,
		req.ParentID, req.Name, req.Type, req.Icon, req.Color, id, userID,
	)
	if err != nil {
		http.Error(w, "update failed", http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleDeleteCategory(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}

	// Check if category has transactions
	var count int
	s.DB.QueryRow(
		"SELECT COUNT(*) FROM transactions WHERE category_id = ? AND deleted_at IS NULL", id,
	).Scan(&count)
	if count > 0 {
		http.Error(w, "cannot delete category with existing transactions", http.StatusConflict)
		return
	}

	s.DB.Exec("UPDATE categories SET deleted_at = datetime('now') WHERE id = ? AND user_id = ?", id, userID)
	w.WriteHeader(http.StatusNoContent)
}
