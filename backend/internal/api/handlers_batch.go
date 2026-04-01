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

	// Fetch all matching transactions in one query
	placeholders := make([]string, len(req.IDs))
	args := make([]any, 0, len(req.IDs)+1)
	for i, id := range req.IDs {
		placeholders[i] = "?"
		args = append(args, id)
	}
	args = append(args, targetUserID)

	fetchQuery := fmt.Sprintf(
		"SELECT id, amount, type, account_id FROM transactions WHERE id IN (%s) AND user_id = ? AND deleted_at IS NULL",
		strings.Join(placeholders, ","),
	)
	rows, err := tx.Query(fetchQuery, args...)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	// Accumulate balance deltas per account
	type txnInfo struct {
		id        int64
		amount    float64
		typ       string
		accountID int64
	}
	var found []txnInfo
	for rows.Next() {
		var t txnInfo
		if err := rows.Scan(&t.id, &t.amount, &t.typ, &t.accountID); err != nil {
			rows.Close()
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		found = append(found, t)
	}
	rows.Close()

	if len(found) == 0 {
		tx.Rollback()
		w.WriteHeader(http.StatusNoContent)
		return
	}

	// Soft-delete all matching transactions in one query
	delPlaceholders := make([]string, len(found))
	delArgs := make([]any, len(found))
	for i, t := range found {
		delPlaceholders[i] = "?"
		delArgs[i] = t.id
	}
	delQuery := fmt.Sprintf("UPDATE transactions SET deleted_at = datetime('now') WHERE id IN (%s)", strings.Join(delPlaceholders, ","))
	if _, err := tx.Exec(delQuery, delArgs...); err != nil {
		http.Error(w, "delete failed", http.StatusInternalServerError)
		return
	}

	// Aggregate balance adjustments per account
	balanceDeltas := make(map[int64]float64)
	for _, t := range found {
		sign := 1.0
		if t.typ == "expense" {
			sign = -1.0
		}
		balanceDeltas[t.accountID] -= t.amount * sign
	}
	for accountID, delta := range balanceDeltas {
		if _, err := tx.Exec("UPDATE accounts SET balance = balance + ? WHERE id = ?", delta, accountID); err != nil {
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
