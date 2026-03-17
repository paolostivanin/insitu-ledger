package api

import (
	"encoding/json"
	"net/http"
	"strconv"
)

type scheduledRequest struct {
	AccountID      int64   `json:"account_id"`
	CategoryID     int64   `json:"category_id"`
	Type           string  `json:"type"`
	Amount         float64 `json:"amount"`
	Currency       string  `json:"currency"`
	Description    *string `json:"description"`
	RRule          string  `json:"rrule"`
	NextOccurrence string  `json:"next_occurrence"`
}

func (s *Server) handleListScheduled(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	rows, err := s.DB.Query(
		`SELECT id, account_id, category_id, user_id, type, amount, currency,
		        description, rrule, next_occurrence, active, created_at, updated_at, sync_version
		 FROM scheduled_transactions WHERE user_id = ? AND deleted_at IS NULL ORDER BY next_occurrence`,
		userID,
	)
	if err != nil {
		http.Error(w, "query error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	var items []map[string]any
	for rows.Next() {
		var id, accountID, categoryID, uid, syncVersion int64
		var active int
		var typ, currency, rrule, nextOcc, createdAt, updatedAt string
		var amount float64
		var description *string

		if err := rows.Scan(&id, &accountID, &categoryID, &uid, &typ, &amount, &currency,
			&description, &rrule, &nextOcc, &active, &createdAt, &updatedAt, &syncVersion); err != nil {
			http.Error(w, "scan error", http.StatusInternalServerError)
			return
		}
		items = append(items, map[string]any{
			"id": id, "account_id": accountID, "category_id": categoryID,
			"user_id": uid, "type": typ, "amount": amount, "currency": currency,
			"description": description, "rrule": rrule, "next_occurrence": truncDate(nextOcc),
			"active": active == 1, "created_at": createdAt, "updated_at": updatedAt,
			"sync_version": syncVersion,
		})
	}

	if items == nil {
		items = []map[string]any{}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(items)
}

func (s *Server) handleCreateScheduled(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())
	var req scheduledRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	if req.Currency == "" {
		req.Currency = "EUR"
	}

	if err := validateDate(req.NextOccurrence); err != nil {
		http.Error(w, "invalid next_occurrence: "+err.Error(), http.StatusBadRequest)
		return
	}

	result, err := s.DB.Exec(
		`INSERT INTO scheduled_transactions (account_id, category_id, user_id, type, amount, currency, description, rrule, next_occurrence)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		req.AccountID, req.CategoryID, userID, req.Type, req.Amount, req.Currency, req.Description, req.RRule, req.NextOccurrence,
	)
	if err != nil {
		http.Error(w, "failed to create scheduled transaction", http.StatusInternalServerError)
		return
	}

	id, _ := result.LastInsertId()
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(map[string]any{"id": id})
}

func (s *Server) handleUpdateScheduled(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}

	var req scheduledRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	if err := validateDate(req.NextOccurrence); err != nil {
		http.Error(w, "invalid next_occurrence: "+err.Error(), http.StatusBadRequest)
		return
	}

	_, err = s.DB.Exec(
		`UPDATE scheduled_transactions SET account_id=?, category_id=?, type=?, amount=?, currency=?,
		 description=?, rrule=?, next_occurrence=? WHERE id=? AND user_id=?`,
		req.AccountID, req.CategoryID, req.Type, req.Amount, req.Currency,
		req.Description, req.RRule, req.NextOccurrence, id, userID,
	)
	if err != nil {
		http.Error(w, "update failed", http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleDeleteScheduled(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}

	result, err := s.DB.Exec("UPDATE scheduled_transactions SET deleted_at = datetime('now') WHERE id = ? AND user_id = ? AND deleted_at IS NULL", id, userID)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	rows, _ := result.RowsAffected()
	if rows == 0 {
		http.Error(w, "scheduled transaction not found", http.StatusNotFound)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
