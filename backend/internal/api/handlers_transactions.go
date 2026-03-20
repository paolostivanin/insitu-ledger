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
	Date        string  `json:"date"`
}

func (s *Server) handleListTransactions(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	targetUserID, _, err := resolveTargetUserID(r, userID, s.DB)
	if err != nil {
		if err.Error() == "forbidden: no shared access" {
			http.Error(w, err.Error(), http.StatusForbidden)
		} else {
			http.Error(w, err.Error(), http.StatusBadRequest)
		}
		return
	}

	// Optional query params for filtering
	from := r.URL.Query().Get("from")       // YYYY-MM-DD
	to := r.URL.Query().Get("to")           // YYYY-MM-DD
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

	var query string
	if needsCategoryJoin {
		query = `SELECT t.id, t.account_id, t.category_id, t.user_id, t.type, t.amount, t.currency,
		           t.description, t.date, t.created_at, t.updated_at, t.sync_version
		           FROM transactions t
		           JOIN categories c ON t.category_id = c.id
		           WHERE t.user_id = ? AND t.deleted_at IS NULL`
	} else {
		query = `SELECT t.id, t.account_id, t.category_id, t.user_id, t.type, t.amount, t.currency,
		           t.description, t.date, t.created_at, t.updated_at, t.sync_version
		           FROM transactions t WHERE t.user_id = ? AND t.deleted_at IS NULL`
	}
	args := []any{targetUserID}

	if from != "" {
		query += " AND SUBSTR(t.date, 1, 10) >= ?"
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
		var description *string

		if err := rows.Scan(&id, &accountID, &categoryID, &uid, &typ, &amount, &currency,
			&description, &date, &createdAt, &updatedAt, &syncVersion); err != nil {
			http.Error(w, "scan error", http.StatusInternalServerError)
			return
		}
		txns = append(txns, map[string]any{
			"id": id, "account_id": accountID, "category_id": categoryID,
			"user_id": uid, "type": typ, "amount": amount, "currency": currency,
			"description": description, "date": date,
			"created_at": createdAt, "updated_at": updatedAt, "sync_version": syncVersion,
		})
	}

	if txns == nil {
		txns = []map[string]any{}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(txns)
}

func (s *Server) handleCreateTransaction(w http.ResponseWriter, r *http.Request) {
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

	var req createTransactionRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
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
	if req.Currency == "" {
		req.Currency = "EUR"
	}

	// If the datetime is in the future, create a one-time scheduled transaction instead
	now := time.Now()
	isFuture := false
	if t, err := time.Parse("2006-01-02T15:04", req.Date); err == nil {
		isFuture = t.After(now)
	} else if t, err := time.Parse("2006-01-02", req.Date); err == nil {
		// Date-only: treat as start of day, future only if the entire day is tomorrow+
		isFuture = t.After(now.Truncate(24 * time.Hour))
	}
	if isFuture {
		result, err := s.DB.Exec(
			`INSERT INTO scheduled_transactions (account_id, category_id, user_id, type, amount, currency, description, rrule, next_occurrence, max_occurrences)
			 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
			req.AccountID, req.CategoryID, targetUserID, req.Type, req.Amount, req.Currency, req.Description, "FREQ=DAILY", req.Date, 1,
		)
		if err != nil {
			http.Error(w, "failed to create scheduled transaction", http.StatusInternalServerError)
			return
		}
		id, _ := result.LastInsertId()
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
		`INSERT INTO transactions (account_id, category_id, user_id, type, amount, currency, description, date)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		req.AccountID, req.CategoryID, targetUserID, req.Type, req.Amount, req.Currency, req.Description, req.Date,
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

	id, _ := result.LastInsertId()
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(map[string]any{"id": id})
}

func (s *Server) handleUpdateTransaction(w http.ResponseWriter, r *http.Request) {
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

	tx, err := s.DB.Begin()
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	// Get old transaction to reverse balance
	var oldAmount float64
	var oldType string
	var oldAccountID int64
	err = tx.QueryRow(
		"SELECT amount, type, account_id FROM transactions WHERE id = ? AND user_id = ? AND deleted_at IS NULL",
		id, targetUserID,
	).Scan(&oldAmount, &oldType, &oldAccountID)
	if err != nil {
		http.Error(w, "transaction not found", http.StatusNotFound)
		return
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
		`UPDATE transactions SET account_id=?, category_id=?, type=?, amount=?, currency=?, description=?, date=?
		 WHERE id=? AND user_id=?`,
		req.AccountID, req.CategoryID, req.Type, req.Amount, req.Currency, req.Description, req.Date, id, targetUserID,
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

	targetUserID, _, err := resolveTargetUserID(r, userID, s.DB)
	if err != nil {
		if err.Error() == "forbidden: no shared access" {
			http.Error(w, err.Error(), http.StatusForbidden)
		} else {
			http.Error(w, err.Error(), http.StatusBadRequest)
		}
		return
	}

	q := r.URL.Query().Get("q")
	if q == "" {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode([]any{})
		return
	}

	rows, err := s.DB.Query(
		`SELECT description, category_id FROM transactions
		 WHERE user_id = ? AND deleted_at IS NULL AND description IS NOT NULL
		   AND description LIKE ? COLLATE NOCASE
		 GROUP BY description COLLATE NOCASE
		 ORDER BY MAX(date) DESC
		 LIMIT 10`,
		targetUserID, q+"%",
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
	if results == nil {
		results = []suggestion{}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(results)
}

func (s *Server) handleDeleteTransaction(w http.ResponseWriter, r *http.Request) {
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

	// Soft delete — reverse balance
	var amount float64
	var typ string
	var accountID int64
	err = tx.QueryRow(
		"SELECT amount, type, account_id FROM transactions WHERE id = ? AND user_id = ? AND deleted_at IS NULL",
		id, targetUserID,
	).Scan(&amount, &typ, &accountID)
	if err != nil {
		http.Error(w, "transaction not found", http.StatusNotFound)
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
