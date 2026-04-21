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
	Note           *string `json:"note"`
	RRule          string  `json:"rrule"`
	NextOccurrence string  `json:"next_occurrence"`
	MaxOccurrences *int64  `json:"max_occurrences"`
}

func (s *Server) handleListScheduled(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	targetUserID, _, err := resolveTargetOwner(r, userID, s.DB)
	if err != nil {
		writeAuthError(w, err)
		return
	}

	accIDs, err := listAccessibleAccountIDs(userID, targetUserID, s.DB)
	if err != nil {
		http.Error(w, "query error", http.StatusInternalServerError)
		return
	}

	args := append([]any{targetUserID}, idsToArgs(accIDs)...)
	rows, err := s.DB.Query(
		`SELECT id, account_id, category_id, user_id, type, amount, currency,
		        description, note, rrule, next_occurrence, active, max_occurrences, occurrence_count,
		        created_at, updated_at, sync_version
		 FROM scheduled_transactions WHERE user_id = ? AND deleted_at IS NULL
		   AND account_id IN (`+sqlInPlaceholders(len(accIDs))+`) ORDER BY next_occurrence`,
		args...,
	)
	if err != nil {
		http.Error(w, "query error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	var items []map[string]any
	for rows.Next() {
		var id, accountID, categoryID, uid, syncVersion, occurrenceCount int64
		var active int
		var typ, currency, rrule, nextOcc, createdAt, updatedAt string
		var amount float64
		var description, note *string
		var maxOccurrences *int64

		if err := rows.Scan(&id, &accountID, &categoryID, &uid, &typ, &amount, &currency,
			&description, &note, &rrule, &nextOcc, &active, &maxOccurrences, &occurrenceCount,
			&createdAt, &updatedAt, &syncVersion); err != nil {
			http.Error(w, "scan error", http.StatusInternalServerError)
			return
		}
		items = append(items, map[string]any{
			"id": id, "account_id": accountID, "category_id": categoryID,
			"user_id": uid, "type": typ, "amount": amount, "currency": currency,
			"description": description, "note": note, "rrule": rrule, "next_occurrence": nextOcc,
			"active": active == 1, "max_occurrences": maxOccurrences, "occurrence_count": occurrenceCount,
			"created_at": createdAt, "updated_at": updatedAt,
			"sync_version": syncVersion,
		})
	}

	if err := rows.Err(); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
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

	if req.AccountID == 0 {
		http.Error(w, "account_id is required", http.StatusBadRequest)
		return
	}
	targetUserID, permission, err := checkAccountAccess(userID, req.AccountID, s.DB)
	if err != nil {
		writeAuthError(w, err)
		return
	}
	if permission != "write" {
		http.Error(w, "forbidden: read-only access", http.StatusForbidden)
		return
	}

	if req.Currency == "" {
		req.Currency = "EUR"
	}

	if err := validateDatetime(req.NextOccurrence); err != nil {
		http.Error(w, "invalid next_occurrence: "+err.Error(), http.StatusBadRequest)
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

	result, err := s.DB.Exec(
		`INSERT INTO scheduled_transactions (account_id, category_id, user_id, type, amount, currency, description, note, rrule, next_occurrence, max_occurrences)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		req.AccountID, req.CategoryID, targetUserID, req.Type, req.Amount, req.Currency, req.Description, req.Note, req.RRule, req.NextOccurrence, req.MaxOccurrences,
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

	var oldAccountID, targetUserID int64
	err = s.DB.QueryRow(
		"SELECT account_id, user_id FROM scheduled_transactions WHERE id = ? AND deleted_at IS NULL",
		id,
	).Scan(&oldAccountID, &targetUserID)
	if err != nil {
		http.Error(w, "scheduled transaction not found", http.StatusNotFound)
		return
	}
	if _, perm, err := checkAccountAccess(userID, oldAccountID, s.DB); err != nil || perm != "write" {
		http.Error(w, "forbidden: read-only access", http.StatusForbidden)
		return
	}
	if req.AccountID != 0 && req.AccountID != oldAccountID {
		newOwner, perm, err := checkAccountAccess(userID, req.AccountID, s.DB)
		if err != nil || perm != "write" {
			http.Error(w, "forbidden: read-only access", http.StatusForbidden)
			return
		}
		if newOwner != targetUserID {
			http.Error(w, "forbidden: cannot move scheduled transaction across owners", http.StatusForbidden)
			return
		}
	}

	if err := validateDatetime(req.NextOccurrence); err != nil {
		http.Error(w, "invalid next_occurrence: "+err.Error(), http.StatusBadRequest)
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

	_, err = s.DB.Exec(
		`UPDATE scheduled_transactions SET account_id=?, category_id=?, type=?, amount=?, currency=?,
		 description=?, note=?, rrule=?, next_occurrence=?, max_occurrences=? WHERE id=? AND user_id=?`,
		req.AccountID, req.CategoryID, req.Type, req.Amount, req.Currency,
		req.Description, req.Note, req.RRule, req.NextOccurrence, req.MaxOccurrences, id, targetUserID,
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

	var accountID int64
	err = s.DB.QueryRow(
		"SELECT account_id FROM scheduled_transactions WHERE id = ? AND deleted_at IS NULL", id,
	).Scan(&accountID)
	if err != nil {
		http.Error(w, "scheduled transaction not found", http.StatusNotFound)
		return
	}
	if _, perm, err := checkAccountAccess(userID, accountID, s.DB); err != nil || perm != "write" {
		http.Error(w, "forbidden: read-only access", http.StatusForbidden)
		return
	}

	result, err := s.DB.Exec("UPDATE scheduled_transactions SET deleted_at = datetime('now') WHERE id = ? AND deleted_at IS NULL", id)
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
		http.Error(w, "scheduled transaction not found", http.StatusNotFound)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
