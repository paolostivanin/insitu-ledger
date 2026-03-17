package api

import (
	"encoding/json"
	"net/http"
	"strconv"
)

type accountRequest struct {
	Name     string `json:"name"`
	Currency string `json:"currency"`
}

func (s *Server) handleListAccounts(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	rows, err := s.DB.Query(
		`SELECT id, user_id, name, currency, balance, created_at, updated_at, sync_version
		 FROM accounts WHERE user_id = ? AND deleted_at IS NULL ORDER BY name`,
		userID,
	)
	if err != nil {
		http.Error(w, "query error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	var accts []map[string]any
	for rows.Next() {
		var id, uid, syncVersion int64
		var name, currency, createdAt, updatedAt string
		var balance float64

		if err := rows.Scan(&id, &uid, &name, &currency, &balance, &createdAt, &updatedAt, &syncVersion); err != nil {
			http.Error(w, "scan error", http.StatusInternalServerError)
			return
		}
		accts = append(accts, map[string]any{
			"id": id, "user_id": uid, "name": name, "currency": currency,
			"balance": balance, "created_at": createdAt, "updated_at": updatedAt,
			"sync_version": syncVersion,
		})
	}

	if accts == nil {
		accts = []map[string]any{}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(accts)
}

func (s *Server) handleCreateAccount(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())
	var req accountRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	if err := validateLength("name", req.Name, 100); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	if req.Currency == "" {
		req.Currency = "EUR"
	}

	result, err := s.DB.Exec(
		"INSERT INTO accounts (user_id, name, currency, balance) VALUES (?, ?, ?, 0)",
		userID, req.Name, req.Currency,
	)
	if err != nil {
		http.Error(w, "failed to create account", http.StatusInternalServerError)
		return
	}

	id, _ := result.LastInsertId()
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(map[string]any{"id": id})
}

func (s *Server) handleUpdateAccount(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}

	var req accountRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	_, err = s.DB.Exec(
		"UPDATE accounts SET name=?, currency=? WHERE id=? AND user_id=?",
		req.Name, req.Currency, id, userID,
	)
	if err != nil {
		http.Error(w, "update failed", http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleDeleteAccount(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}

	result, err := s.DB.Exec("UPDATE accounts SET deleted_at = datetime('now') WHERE id = ? AND user_id = ? AND deleted_at IS NULL", id, userID)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	rows, _ := result.RowsAffected()
	if rows == 0 {
		http.Error(w, "account not found", http.StatusNotFound)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
