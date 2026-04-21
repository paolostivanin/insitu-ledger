package api

import (
	"encoding/json"
	"log"
	"net/http"
	"strconv"
	"time"
)

type createTransactionRequest struct {
	AccountID   int64   `json:"account_id"`
	CategoryID  int64   `json:"category_id"`
	Type        string  `json:"type"`
	Amount      float64 `json:"amount"`
	Currency    string  `json:"currency"`
	Description *string `json:"description"`
	Note        *string `json:"note"`
	Date        string  `json:"date"`
}

func (s *Server) handleListTransactions(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	accIDs, err := scopedAccountIDs(r, userID, s.DB)
	if err != nil {
		writeAuthError(w, err)
		return
	}

	// Optional query params for filtering
	from := r.URL.Query().Get("from")  // YYYY-MM-DD
	to := r.URL.Query().Get("to")      // YYYY-MM-DD
	catID := r.URL.Query().Get("category_id")

	limitVal, offsetVal, err := parsePagination(r.URL.Query().Get("limit"), r.URL.Query().Get("offset"))
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	sortColumn, sortDirection, needsCategoryJoin, err := parseSortParams(
		r.URL.Query().Get("sort_by"), r.URL.Query().Get("sort_dir"),
	)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	if from != "" {
		if err := validateDate(from); err != nil {
			http.Error(w, "invalid 'from' date: "+err.Error(), http.StatusBadRequest)
			return
		}
	}
	if to != "" {
		if err := validateDate(to); err != nil {
			http.Error(w, "invalid 'to' date: "+err.Error(), http.StatusBadRequest)
			return
		}
	}

	accInClause := sqlInPlaceholders(len(accIDs))
	// LEFT JOIN users cu projects the creator's display name for the
	// "Added by" UI affordance on shared accounts. account_id IN (...) is
	// the source of truth for authorization; no t.user_id filter is needed.
	selectCols := `t.id, t.account_id, t.category_id, t.user_id, t.type, t.amount, t.currency,
		           t.description, t.note, t.date, t.created_at, t.updated_at, t.sync_version,
		           t.created_by_user_id, cu.name`
	joinCreator := ` LEFT JOIN users cu ON t.created_by_user_id = cu.id`
	var query string
	if needsCategoryJoin {
		query = `SELECT ` + selectCols + ` FROM transactions t` + joinCreator + `
		           JOIN categories c ON t.category_id = c.id
		           WHERE t.deleted_at IS NULL
		             AND t.account_id IN (` + accInClause + `)`
	} else {
		query = `SELECT ` + selectCols + ` FROM transactions t` + joinCreator + `
		           WHERE t.deleted_at IS NULL
		             AND t.account_id IN (` + accInClause + `)`
	}
	args := idsToArgs(accIDs)

	if from != "" {
		query += " AND t.date >= ?"
		args = append(args, from)
	}
	if to != "" {
		query += " AND SUBSTR(t.date, 1, 10) <= ?"
		args = append(args, to)
	}
	if catID != "" {
		query += " AND t.category_id = ?"
		args = append(args, catID)
	}

	query += " ORDER BY " + sortColumn + " " + sortDirection + ", t.id DESC LIMIT ? OFFSET ?"
	args = append(args, limitVal, offsetVal)

	rows, err := s.DB.Query(query, args...)
	if err != nil {
		http.Error(w, "query error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	var txns []map[string]any
	for rows.Next() {
		var id, accountID, categoryID, uid, syncVersion int64
		var typ, currency, date, createdAt, updatedAt string
		var amount float64
		var description, note, createdByName *string
		var createdByUserID *int64

		if err := rows.Scan(&id, &accountID, &categoryID, &uid, &typ, &amount, &currency,
			&description, &note, &date, &createdAt, &updatedAt, &syncVersion,
			&createdByUserID, &createdByName); err != nil {
			http.Error(w, "scan error", http.StatusInternalServerError)
			return
		}
		txns = append(txns, map[string]any{
			"id": id, "account_id": accountID, "category_id": categoryID,
			"user_id": uid, "type": typ, "amount": amount, "currency": currency,
			"description": description, "note": note, "date": date,
			"created_at": createdAt, "updated_at": updatedAt, "sync_version": syncVersion,
			"created_by_user_id": createdByUserID,
			"created_by_name":    createdByName,
		})
	}

	if err := rows.Err(); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	if txns == nil {
		txns = []map[string]any{}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(txns)
}

func (s *Server) handleCreateTransaction(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	var req createTransactionRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	if req.AccountID == 0 {
		http.Error(w, "account_id is required", http.StatusBadRequest)
		return
	}
	targetUserID, err := checkAccountAccess(userID, req.AccountID, s.DB)
	if err != nil {
		writeAuthError(w, err)
		return
	}

	if req.Type != "income" && req.Type != "expense" {
		http.Error(w, "type must be 'income' or 'expense'", http.StatusBadRequest)
		return
	}
	if req.Amount <= 0 {
		http.Error(w, "amount must be positive", http.StatusBadRequest)
		return
	}
	if err := validateDatetime(req.Date); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	if req.Description != nil {
		if err := validateLength("description", *req.Description, 500); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
	}
	if req.Note != nil {
		if err := validateLength("note", *req.Note, 2000); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
	}
	if req.Currency == "" {
		req.Currency = "EUR"
	}

	// If the datetime is in the future, create a one-time scheduled transaction instead.
	// Parse in local time to match the user's intent (frontend sends local times).
	now := time.Now()
	isFuture := false
	if t, err := time.ParseInLocation("2006-01-02T15:04", req.Date, time.Local); err == nil {
		isFuture = t.After(now)
	} else if t, err := time.ParseInLocation("2006-01-02", req.Date, time.Local); err == nil {
		// Date-only: treat as start of day, future only if the entire day is tomorrow+
		isFuture = t.After(now.Truncate(24 * time.Hour))
	}
	if isFuture {
		result, err := s.DB.Exec(
			`INSERT INTO scheduled_transactions (account_id, category_id, user_id, created_by_user_id, type, amount, currency, description, note, rrule, next_occurrence, max_occurrences)
			 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
			req.AccountID, req.CategoryID, targetUserID, userID, req.Type, req.Amount, req.Currency, req.Description, req.Note, "FREQ=DAILY", req.Date, 1,
		)
		if err != nil {
			http.Error(w, "failed to create scheduled transaction", http.StatusInternalServerError)
			return
		}
		id, err := result.LastInsertId()
		if err != nil {
			log.Printf("LastInsertId error: %v", err)
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(map[string]any{"id": id, "scheduled": true})
		return
	}

	tx, err := s.DB.Begin()
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	result, err := tx.Exec(
		`INSERT INTO transactions (account_id, category_id, user_id, created_by_user_id, type, amount, currency, description, note, date)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		req.AccountID, req.CategoryID, targetUserID, userID, req.Type, req.Amount, req.Currency, req.Description, req.Note, req.Date,
	)
	if err != nil {
		http.Error(w, "failed to create transaction", http.StatusInternalServerError)
		return
	}

	sign := 1.0
	if req.Type == "expense" {
		sign = -1.0
	}
	if _, err := tx.Exec("UPDATE accounts SET balance = balance + ? WHERE id = ?", req.Amount*sign, req.AccountID); err != nil {
		http.Error(w, "failed to update balance", http.StatusInternalServerError)
		return
	}

	if err := tx.Commit(); err != nil {
		log.Printf("commit error: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	id, err := result.LastInsertId()
	if err != nil {
		log.Printf("LastInsertId error: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(map[string]any{"id": id})
}

func (s *Server) handleUpdateTransaction(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}

	var req createTransactionRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	if err := validateDatetime(req.Date); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	if req.Description != nil {
		if err := validateLength("description", *req.Description, 500); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
	}
	if req.Note != nil {
		if err := validateLength("note", *req.Note, 2000); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
	}

	tx, err := s.DB.Begin()
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	// Look up the existing transaction (no user filter — auth comes from the account check below).
	var oldAmount float64
	var oldType string
	var oldAccountID, targetUserID int64
	err = tx.QueryRow(
		"SELECT amount, type, account_id, user_id FROM transactions WHERE id = ? AND deleted_at IS NULL",
		id,
	).Scan(&oldAmount, &oldType, &oldAccountID, &targetUserID)
	if err != nil {
		http.Error(w, "transaction not found", http.StatusNotFound)
		return
	}

	// Caller must have access to BOTH the old account and (if changing) the new account.
	if _, err := checkAccountAccess(userID, oldAccountID, tx); err != nil {
		http.Error(w, errForbidden, http.StatusForbidden)
		return
	}
	if req.AccountID != 0 && req.AccountID != oldAccountID {
		newOwner, err := checkAccountAccess(userID, req.AccountID, tx)
		if err != nil {
			http.Error(w, errForbidden, http.StatusForbidden)
			return
		}
		if newOwner != targetUserID {
			http.Error(w, "forbidden: cannot move transaction across owners", http.StatusForbidden)
			return
		}
	}

	// Reverse old balance effect
	oldSign := 1.0
	if oldType == "expense" {
		oldSign = -1.0
	}
	if _, err := tx.Exec("UPDATE accounts SET balance = balance - ? WHERE id = ?", oldAmount*oldSign, oldAccountID); err != nil {
		http.Error(w, "failed to update balance", http.StatusInternalServerError)
		return
	}

	// Apply update
	if _, err := tx.Exec(
		`UPDATE transactions SET account_id=?, category_id=?, type=?, amount=?, currency=?, description=?, note=?, date=?
		 WHERE id=? AND user_id=?`,
		req.AccountID, req.CategoryID, req.Type, req.Amount, req.Currency, req.Description, req.Note, req.Date, id, targetUserID,
	); err != nil {
		http.Error(w, "update failed", http.StatusInternalServerError)
		return
	}

	// Apply new balance effect
	newSign := 1.0
	if req.Type == "expense" {
		newSign = -1.0
	}
	if _, err := tx.Exec("UPDATE accounts SET balance = balance + ? WHERE id = ?", req.Amount*newSign, req.AccountID); err != nil {
		http.Error(w, "failed to update balance", http.StatusInternalServerError)
		return
	}

	if err := tx.Commit(); err != nil {
		log.Printf("commit error: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleAutocompleteTransactions(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	q := r.URL.Query().Get("q")
	if q == "" {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode([]any{})
		return
	}

	accIDs, err := scopedAccountIDs(r, userID, s.DB)
	if err != nil {
		writeAuthError(w, err)
		return
	}

	args := append([]any{q + "%"}, idsToArgs(accIDs)...)
	rows, err := s.DB.Query(
		`SELECT description, category_id FROM transactions
		 WHERE deleted_at IS NULL AND description IS NOT NULL
		   AND description LIKE ? COLLATE NOCASE
		   AND account_id IN (`+sqlInPlaceholders(len(accIDs))+`)
		 GROUP BY description COLLATE NOCASE
		 ORDER BY MAX(date) DESC
		 LIMIT 10`,
		args...,
	)
	if err != nil {
		http.Error(w, "query error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	type suggestion struct {
		Description string `json:"description"`
		CategoryID  int64  `json:"category_id"`
	}
	var results []suggestion
	for rows.Next() {
		var s suggestion
		if err := rows.Scan(&s.Description, &s.CategoryID); err != nil {
			http.Error(w, "scan error", http.StatusInternalServerError)
			return
		}
		results = append(results, s)
	}
	if err := rows.Err(); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if results == nil {
		results = []suggestion{}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(results)
}

func (s *Server) handleDeleteTransaction(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}

	tx, err := s.DB.Begin()
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	// Soft delete — reverse balance. Auth via the txn's account.
	var amount float64
	var typ string
	var accountID int64
	err = tx.QueryRow(
		"SELECT amount, type, account_id FROM transactions WHERE id = ? AND deleted_at IS NULL",
		id,
	).Scan(&amount, &typ, &accountID)
	if err != nil {
		http.Error(w, "transaction not found", http.StatusNotFound)
		return
	}

	if _, err := checkAccountAccess(userID, accountID, tx); err != nil {
		http.Error(w, errForbidden, http.StatusForbidden)
		return
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

	if err := tx.Commit(); err != nil {
		log.Printf("commit error: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusNoContent)
}
