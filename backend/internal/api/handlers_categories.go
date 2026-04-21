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

	targetUserID, isOwn, err := resolveTargetOwner(r, userID, s.DB)
	if err != nil {
		writeAuthError(w, err)
		return
	}

	rows, err := s.DB.Query(
		`SELECT id, user_id, parent_id, name, type, icon, color, created_at, updated_at, sync_version
		 FROM categories WHERE user_id = ? AND deleted_at IS NULL ORDER BY name`,
		targetUserID,
	)
	if err != nil {
		http.Error(w, "query error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	cats := []map[string]any{}
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
			"sync_version": syncVersion, "read_only": !isOwn,
		})
	}

	if err := rows.Err(); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(cats)
}

func (s *Server) handleCreateCategory(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	targetUserID, isOwn, err := resolveTargetOwner(r, userID, s.DB)
	if err != nil {
		writeAuthError(w, err)
		return
	}
	if !isOwn {
		http.Error(w, "forbidden: categories are not shared", http.StatusForbidden)
		return
	}

	var req categoryRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	if err := validateLength("name", req.Name, 100); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	if req.Type != "income" && req.Type != "expense" {
		http.Error(w, "type must be 'income' or 'expense'", http.StatusBadRequest)
		return
	}

	result, err := s.DB.Exec(
		`INSERT INTO categories (user_id, parent_id, name, type, icon, color)
		 VALUES (?, ?, ?, ?, ?, ?)`,
		targetUserID, req.ParentID, req.Name, req.Type, req.Icon, req.Color,
	)
	if err != nil {
		http.Error(w, "failed to create category", http.StatusInternalServerError)
		return
	}

	id, err := result.LastInsertId()
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(map[string]any{"id": id})
}

func (s *Server) handleUpdateCategory(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	targetUserID, isOwn, err := resolveTargetOwner(r, userID, s.DB)
	if err != nil {
		writeAuthError(w, err)
		return
	}
	if !isOwn {
		http.Error(w, "forbidden: categories are not shared", http.StatusForbidden)
		return
	}

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
		req.ParentID, req.Name, req.Type, req.Icon, req.Color, id, targetUserID,
	)
	if err != nil {
		http.Error(w, "update failed", http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleDeleteCategory(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	targetUserID, isOwn, err := resolveTargetOwner(r, userID, s.DB)
	if err != nil {
		writeAuthError(w, err)
		return
	}
	if !isOwn {
		http.Error(w, "forbidden: categories are not shared", http.StatusForbidden)
		return
	}

	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}

	// Check if category has transactions
	var count int
	if err := s.DB.QueryRow(
		"SELECT COUNT(*) FROM transactions WHERE category_id = ? AND deleted_at IS NULL", id,
	).Scan(&count); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if count > 0 {
		http.Error(w, "cannot delete category with existing transactions", http.StatusConflict)
		return
	}

	result, err := s.DB.Exec("UPDATE categories SET deleted_at = datetime('now') WHERE id = ? AND user_id = ? AND deleted_at IS NULL", id, targetUserID)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	rows, err := result.RowsAffected()
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if rows == 0 {
		http.Error(w, "category not found", http.StatusNotFound)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
