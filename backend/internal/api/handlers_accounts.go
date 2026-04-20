package api

import (
	"encoding/json"
	"net/http"
	"strconv"
)

type accountRequest struct {
	Name     string   `json:"name"`
	Currency string   `json:"currency"`
	Balance  *float64 `json:"balance,omitempty"`
}

func (s *Server) handleListAccounts(w http.ResponseWriter, r *http.Request) {
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

	rows, err := s.DB.Query(
		`SELECT id, user_id, name, currency, balance, created_at, updated_at, sync_version
		 FROM accounts WHERE user_id = ? AND deleted_at IS NULL ORDER BY name`,
		targetUserID,
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

	if err := rows.Err(); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	if accts == nil {
		accts = []map[string]any{}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(accts)
}

func (s *Server) handleCreateAccount(w http.ResponseWriter, r *http.Request) {
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
	balance := 0.0
	if req.Balance != nil {
		balance = *req.Balance
	}

	result, err := s.DB.Exec(
		"INSERT INTO accounts (user_id, name, currency, balance) VALUES (?, ?, ?, ?)",
		targetUserID, req.Name, req.Currency, balance,
	)
	if err != nil {
		http.Error(w, "failed to create account", http.StatusInternalServerError)
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

func (s *Server) handleUpdateAccount(w http.ResponseWriter, r *http.Request) {
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

	var req accountRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	_, err = s.DB.Exec(
		"UPDATE accounts SET name=?, currency=? WHERE id=? AND user_id=?",
		req.Name, req.Currency, id, targetUserID,
	)
	if err != nil {
		http.Error(w, "update failed", http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleDeleteAccount(w http.ResponseWriter, r *http.Request) {
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

	result, err := s.DB.Exec("UPDATE accounts SET deleted_at = datetime('now') WHERE id = ? AND user_id = ? AND deleted_at IS NULL", id, targetUserID)
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
		http.Error(w, "account not found", http.StatusNotFound)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
