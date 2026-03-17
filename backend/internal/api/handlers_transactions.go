package api

import (
	"encoding/json"
	"log"
	"net/http"
	"strconv"
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

	// Optional query params for filtering
	from := r.URL.Query().Get("from")       // YYYY-MM-DD
	to := r.URL.Query().Get("to")           // YYYY-MM-DD
	catID := r.URL.Query().Get("category_id")
	limit := r.URL.Query().Get("limit")
	offset := r.URL.Query().Get("offset")

	query := `SELECT id, account_id, category_id, user_id, type, amount, currency,
	           description, date, created_at, updated_at, sync_version
	           FROM transactions WHERE user_id = ? AND deleted_at IS NULL`
	args := []any{userID}

	if from != "" {
		query += " AND date >= ?"
		args = append(args, from)
	}
	if to != "" {
		query += " AND date <= ?"
		args = append(args, to)
	}
	if catID != "" {
		query += " AND category_id = ?"
		args = append(args, catID)
	}

	query += " ORDER BY date DESC"

	if limit == "" {
		limit = "50"
	}
	query += " LIMIT ?"
	args = append(args, limit)

	if offset != "" {
		query += " OFFSET ?"
		args = append(args, offset)
	}

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
			"description": description, "date": truncDate(date),
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
	if req.Currency == "" {
		req.Currency = "EUR"
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
		req.AccountID, req.CategoryID, userID, req.Type, req.Amount, req.Currency, req.Description, req.Date,
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
		id, userID,
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
		req.AccountID, req.CategoryID, req.Type, req.Amount, req.Currency, req.Description, req.Date, id, userID,
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

	rows, err := s.DB.Query(
		`SELECT description, category_id FROM transactions
		 WHERE user_id = ? AND deleted_at IS NULL AND description IS NOT NULL
		   AND description LIKE ? COLLATE NOCASE
		 GROUP BY description COLLATE NOCASE
		 ORDER BY MAX(date) DESC
		 LIMIT 10`,
		userID, q+"%",
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
		id, userID,
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
