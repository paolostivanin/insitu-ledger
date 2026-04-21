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

	// Fetch all candidate transactions (no user filter — auth is per-account below).
	placeholders := make([]string, len(req.IDs))
	args := make([]any, 0, len(req.IDs))
	for i, id := range req.IDs {
		placeholders[i] = "?"
		args = append(args, id)
	}

	fetchQuery := fmt.Sprintf(
		"SELECT id, amount, type, account_id FROM transactions WHERE id IN (%s) AND deleted_at IS NULL",
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

	// Verify the caller has access to every affected account.
	checked := map[int64]bool{}
	for _, t := range found {
		if checked[t.accountID] {
			continue
		}
		if _, err := checkAccountAccess(userID, t.accountID, tx); err != nil {
			http.Error(w, errForbidden, http.StatusForbidden)
			return
		}
		checked[t.accountID] = true
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

	var req batchUpdateCategoryRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	if len(req.IDs) == 0 || req.CategoryID == 0 {
		http.Error(w, "ids and category_id required", http.StatusBadRequest)
		return
	}

	tx, err := s.DB.Begin()
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	// Fetch candidate transactions and group by user_id+account_id for auth.
	placeholders := make([]string, len(req.IDs))
	idArgs := make([]any, 0, len(req.IDs))
	for i, id := range req.IDs {
		placeholders[i] = "?"
		idArgs = append(idArgs, id)
	}
	rows, err := tx.Query(
		fmt.Sprintf("SELECT id, user_id, account_id FROM transactions WHERE id IN (%s) AND deleted_at IS NULL",
			strings.Join(placeholders, ",")),
		idArgs...,
	)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	type txnRef struct{ id, ownerID, accountID int64 }
	var found []txnRef
	for rows.Next() {
		var t txnRef
		if err := rows.Scan(&t.id, &t.ownerID, &t.accountID); err == nil {
			found = append(found, t)
		}
	}
	rows.Close()
	if len(found) == 0 {
		w.WriteHeader(http.StatusNoContent)
		return
	}

	// Validate every transaction is in an accessible account, and collect owners.
	owners := map[int64]bool{}
	checked := map[int64]bool{}
	for _, t := range found {
		if !checked[t.accountID] {
			if _, err := checkAccountAccess(userID, t.accountID, tx); err != nil {
				http.Error(w, errForbidden, http.StatusForbidden)
				return
			}
			checked[t.accountID] = true
		}
		owners[t.ownerID] = true
	}

	// The new category must belong to a single common owner across all affected transactions.
	if len(owners) != 1 {
		http.Error(w, "forbidden: transactions span multiple owners", http.StatusForbidden)
		return
	}
	var ownerID int64
	for o := range owners {
		ownerID = o
	}
	var catExists int
	if err := tx.QueryRow(
		"SELECT 1 FROM categories WHERE id = ? AND user_id = ? AND deleted_at IS NULL",
		req.CategoryID, ownerID,
	).Scan(&catExists); err != nil {
		http.Error(w, "category not found", http.StatusBadRequest)
		return
	}

	updArgs := make([]any, 0, len(req.IDs)+1)
	updArgs = append(updArgs, req.CategoryID)
	updArgs = append(updArgs, idArgs...)
	if _, err := tx.Exec(
		fmt.Sprintf("UPDATE transactions SET category_id = ? WHERE id IN (%s) AND deleted_at IS NULL",
			strings.Join(placeholders, ",")),
		updArgs...,
	); err != nil {
		http.Error(w, "update failed", http.StatusInternalServerError)
		return
	}

	if err := tx.Commit(); err != nil {
		log.Printf("batch update commit error: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusNoContent)
}
