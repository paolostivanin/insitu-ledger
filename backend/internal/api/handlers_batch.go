package api

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strings"
)

type batchDeleteRequest struct {
	IDs []int64 `json:"ids"`
}

type batchUpdateCategoryRequest struct {
	IDs        []int64 `json:"ids"`
	CategoryID int64   `json:"category_id"`
}

func (s *Server) handleBatchDeleteTransactions(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	targetUserID, permission, err := resolveTargetUserID(r, userID, s.DB)
	if err != nil {
		if err.Error() == "forbidden: no shared access" {
			http.Error(w, err.Error(), http.StatusForbidden)
		} else {
			http.Error(w, err.Error(), http.StatusBadRequest)
		}
		return
	}
	if permission != "write" {
		http.Error(w, "forbidden: read-only access", http.StatusForbidden)
		return
	}

	var req batchDeleteRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	if len(req.IDs) == 0 {
		http.Error(w, "ids required", http.StatusBadRequest)
		return
	}

	tx, err := s.DB.Begin()
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	for _, id := range req.IDs {
		var amount float64
		var typ string
		var accountID int64
		err := tx.QueryRow(
			"SELECT amount, type, account_id FROM transactions WHERE id = ? AND user_id = ? AND deleted_at IS NULL",
			id, targetUserID,
		).Scan(&amount, &typ, &accountID)
		if err != nil {
			continue
		}

		if _, err := tx.Exec("UPDATE transactions SET deleted_at = datetime('now') WHERE id = ?", id); err != nil {
			http.Error(w, "delete failed", http.StatusInternalServerError)
			return
		}

		sign := 1.0
		if typ == "expense" {
			sign = -1.0
		}
		if _, err := tx.Exec("UPDATE accounts SET balance = balance - ? WHERE id = ?", amount*sign, accountID); err != nil {
			http.Error(w, "failed to update balance", http.StatusInternalServerError)
			return
		}
	}

	if err := tx.Commit(); err != nil {
		log.Printf("commit error: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleBatchUpdateCategory(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	targetUserID, permission, err := resolveTargetUserID(r, userID, s.DB)
	if err != nil {
		if err.Error() == "forbidden: no shared access" {
			http.Error(w, err.Error(), http.StatusForbidden)
		} else {
			http.Error(w, err.Error(), http.StatusBadRequest)
		}
		return
	}
	if permission != "write" {
		http.Error(w, "forbidden: read-only access", http.StatusForbidden)
		return
	}

	var req batchUpdateCategoryRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	if len(req.IDs) == 0 || req.CategoryID == 0 {
		http.Error(w, "ids and category_id required", http.StatusBadRequest)
		return
	}

	// Verify category belongs to user
	var catExists bool
	err = s.DB.QueryRow("SELECT 1 FROM categories WHERE id = ? AND user_id = ? AND deleted_at IS NULL", req.CategoryID, targetUserID).Scan(&catExists)
	if err != nil {
		http.Error(w, "category not found", http.StatusBadRequest)
		return
	}

	placeholders := make([]string, len(req.IDs))
	args := make([]any, 0, len(req.IDs)+2)
	args = append(args, req.CategoryID)
	for i, id := range req.IDs {
		placeholders[i] = "?"
		args = append(args, id)
	}
	args = append(args, targetUserID)

	query := fmt.Sprintf(
		"UPDATE transactions SET category_id = ? WHERE id IN (%s) AND user_id = ? AND deleted_at IS NULL",
		strings.Join(placeholders, ","),
	)

	if _, err := s.DB.Exec(query, args...); err != nil {
		http.Error(w, "update failed", http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusNoContent)
}
