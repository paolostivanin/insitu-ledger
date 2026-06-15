package api

import (
	"database/sql"
	"encoding/json"
	"log"
	"net/http"
	"strconv"
)

type categoryRequest struct {
	ParentID *int64  `json:"parent_id"`
	Name     string  `json:"name"`
	Type     string  `json:"type"`
	Icon     *string `json:"icon"`
	Color    *string `json:"color"`
	// Optional client-generated UUID for idempotent CREATE; see
	// createTransactionRequest.ClientID for the rationale.
	ClientID string `json:"client_id,omitempty"`
}

func (s *Server) handleListCategories(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	// Aggregate mode (no owner_id) returns categories from every accessible
	// owner so a guest can render Alice's transactions with Alice's category
	// names. Filter mode (owner_id present) returns just that owner's set.
	var (
		uids          []int64
		readOnlyByUID = map[int64]bool{}
	)
	ownerStr := r.URL.Query().Get("owner_id")
	if ownerStr == "" {
		ownerIDs, err := listAccessibleOwnerIDs(userID, s.DB)
		if err != nil {
			http.Error(w, "query error", http.StatusInternalServerError)
			return
		}
		uids = ownerIDs
		for _, uid := range uids {
			readOnlyByUID[uid] = uid != userID
		}
	} else {
		targetUserID, isOwn, err := resolveTargetOwner(r, userID, s.DB)
		if err != nil {
			writeAuthError(w, err)
			return
		}
		uids = []int64{targetUserID}
		readOnlyByUID[targetUserID] = !isOwn
	}

	rows, err := s.DB.Query(
		`SELECT id, user_id, parent_id, name, type, icon, color, created_at, updated_at, sync_version
		 FROM categories WHERE deleted_at IS NULL AND user_id IN (`+sqlInPlaceholders(len(uids))+`)
		 ORDER BY name`,
		idsToArgs(uids)...,
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
			"sync_version": syncVersion, "read_only": readOnlyByUID[uid],
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

	if req.ClientID != "" {
		var existingID int64
		err := s.DB.QueryRow(
			`SELECT id FROM categories WHERE user_id = ? AND client_id = ? LIMIT 1`,
			targetUserID, req.ClientID,
		).Scan(&existingID)
		if err == nil {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusOK)
			json.NewEncoder(w).Encode(map[string]any{"id": existingID})
			return
		} else if err != sql.ErrNoRows {
			log.Printf("idempotency lookup error: %v", err)
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
	}

	result, err := s.DB.Exec(
		`INSERT INTO categories (user_id, parent_id, name, type, icon, color, client_id)
		 VALUES (?, ?, ?, ?, ?, ?, ?)`,
		targetUserID, req.ParentID, req.Name, req.Type, req.Icon, req.Color, nullableString(req.ClientID),
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

	// Confirm the category exists and belongs to the caller BEFORE any
	// dependency check. Otherwise a non-owner could probe for the existence
	// of someone else's category via the 409-vs-404 timing.
	var owns int
	if err := s.DB.QueryRow(
		`SELECT 1 FROM categories WHERE id = ? AND user_id = ? AND deleted_at IS NULL`,
		id, targetUserID,
	).Scan(&owns); err != nil {
		http.Error(w, "category not found", http.StatusNotFound)
		return
	}

	// Block deletion when live transactions OR undeleted scheduled
	// transactions still reference the category. Materialized scheduled
	// occurrences would otherwise point at a soft-deleted FK.
	var txnCount, schedCount int
	if err := s.DB.QueryRow(
		`SELECT COUNT(*) FROM transactions
		 WHERE category_id = ? AND user_id = ? AND deleted_at IS NULL`, id, targetUserID,
	).Scan(&txnCount); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if txnCount > 0 {
		http.Error(w, "cannot delete category with existing transactions", http.StatusConflict)
		return
	}
	if err := s.DB.QueryRow(
		`SELECT COUNT(*) FROM scheduled_transactions
		 WHERE category_id = ? AND user_id = ? AND deleted_at IS NULL`, id, targetUserID,
	).Scan(&schedCount); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if schedCount > 0 {
		http.Error(w, "cannot delete category with scheduled transactions", http.StatusConflict)
		return
	}

	if _, err := s.DB.Exec(
		`UPDATE categories SET deleted_at = datetime('now') WHERE id = ? AND user_id = ? AND deleted_at IS NULL`,
		id, targetUserID,
	); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
