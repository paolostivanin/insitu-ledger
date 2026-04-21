package api

import (
	"database/sql"
	"encoding/json"
	"net/http"
)

type preferencesResponse struct {
	DefaultAccountID *int64 `json:"default_account_id"`
}

type updatePreferencesRequest struct {
	DefaultAccountID *int64 `json:"default_account_id"`
}

// handleGetPreferences returns the user's preferences. The default_account_id
// is returned as null when unset, when the referenced account no longer exists
// or has been soft-deleted, or when a previously-shared account is no longer
// accessible to the user.
func (s *Server) handleGetPreferences(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	var defaultAccountID sql.NullInt64
	if err := s.DB.QueryRow(
		"SELECT default_account_id FROM users WHERE id = ?", userID,
	).Scan(&defaultAccountID); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	resp := preferencesResponse{}
	if defaultAccountID.Valid {
		if _, err := checkAccountAccess(userID, defaultAccountID.Int64, s.DB); err == nil {
			id := defaultAccountID.Int64
			resp.DefaultAccountID = &id
		}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}

// handleUpdatePreferences updates the user's preferences. The default_account_id
// must reference an account the user can read (own or shared); pass null to clear.
func (s *Server) handleUpdatePreferences(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	var req updatePreferencesRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	if req.DefaultAccountID != nil {
		if _, err := checkAccountAccess(userID, *req.DefaultAccountID, s.DB); err != nil {
			http.Error(w, "default account not accessible", http.StatusBadRequest)
			return
		}
	}

	if _, err := s.DB.Exec(
		"UPDATE users SET default_account_id = ? WHERE id = ?",
		req.DefaultAccountID, userID,
	); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusNoContent)
}
