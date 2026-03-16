package api

import (
	"encoding/json"
	"net/http"
	"strconv"

	"github.com/pstivanin/insitu-ledger/backend/internal/auth"
)

// AdminMiddleware checks that the current user has is_admin=1.
func (s *Server) AdminMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		userID := UserIDFromContext(r.Context())
		var isAdmin bool
		err := s.DB.QueryRow("SELECT is_admin FROM users WHERE id = ?", userID).Scan(&isAdmin)
		if err != nil || !isAdmin {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		next.ServeHTTP(w, r)
	})
}

type adminCreateUserRequest struct {
	Username string `json:"username"`
	Email    string `json:"email"`
	Name     string `json:"name"`
	Password string `json:"password"`
}

type adminUpdateUserRequest struct {
	Username *string `json:"username,omitempty"`
	Email    *string `json:"email,omitempty"`
	Name     *string `json:"name,omitempty"`
}

// handleAdminListUsers returns all users.
func (s *Server) handleAdminListUsers(w http.ResponseWriter, r *http.Request) {
	rows, err := s.DB.Query(
		`SELECT id, username, email, name, is_admin, force_password_change, totp_enabled, created_at
		 FROM users ORDER BY created_at`,
	)
	if err != nil {
		http.Error(w, "query error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	var users []map[string]any
	for rows.Next() {
		var id int64
		var username, email, name, createdAt string
		var isAdmin, forcePC, totpEnabled bool
		rows.Scan(&id, &username, &email, &name, &isAdmin, &forcePC, &totpEnabled, &createdAt)
		users = append(users, map[string]any{
			"id": id, "username": username, "email": email, "name": name,
			"is_admin": isAdmin, "force_password_change": forcePC,
			"totp_enabled": totpEnabled, "created_at": createdAt,
		})
	}
	if users == nil {
		users = []map[string]any{}
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(users)
}

// handleAdminCreateUser creates a new user (admin only).
func (s *Server) handleAdminCreateUser(w http.ResponseWriter, r *http.Request) {
	var req adminCreateUserRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	if req.Username == "" || req.Email == "" || req.Name == "" || req.Password == "" {
		http.Error(w, "username, email, name, and password are required", http.StatusBadRequest)
		return
	}

	hash, err := auth.HashPassword(req.Password)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	result, err := s.DB.Exec(
		"INSERT INTO users (username, email, name, password_hash, force_password_change) VALUES (?, ?, ?, ?, 1)",
		req.Username, req.Email, req.Name, hash,
	)
	if err != nil {
		http.Error(w, "username or email already in use", http.StatusConflict)
		return
	}

	id, _ := result.LastInsertId()
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(map[string]any{"id": id})
}

// handleAdminUpdateUser lets admin change a user's name and email.
func (s *Server) handleAdminUpdateUser(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}

	var req adminUpdateUserRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	if req.Username != nil {
		_, err := s.DB.Exec("UPDATE users SET username = ? WHERE id = ?", *req.Username, id)
		if err != nil {
			http.Error(w, "username already in use", http.StatusConflict)
			return
		}
	}
	if req.Email != nil {
		_, err := s.DB.Exec("UPDATE users SET email = ? WHERE id = ?", *req.Email, id)
		if err != nil {
			http.Error(w, "email already in use", http.StatusConflict)
			return
		}
	}
	if req.Name != nil {
		s.DB.Exec("UPDATE users SET name = ? WHERE id = ?", *req.Name, id)
	}

	w.WriteHeader(http.StatusNoContent)
}

// handleAdminDeleteUser deletes a user and all their data.
func (s *Server) handleAdminDeleteUser(w http.ResponseWriter, r *http.Request) {
	adminID := UserIDFromContext(r.Context())
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}

	if id == adminID {
		http.Error(w, "cannot delete yourself", http.StatusBadRequest)
		return
	}

	s.DB.Exec("DELETE FROM sessions WHERE user_id = ?", id)
	s.DB.Exec("DELETE FROM users WHERE id = ?", id)

	w.WriteHeader(http.StatusNoContent)
}

// handleAdminResetPassword sets a temporary password and forces change on next login.
func (s *Server) handleAdminResetPassword(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}

	var req struct {
		NewPassword string `json:"new_password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	if len(req.NewPassword) < 8 {
		http.Error(w, "password must be at least 8 characters", http.StatusBadRequest)
		return
	}

	hash, err := auth.HashPassword(req.NewPassword)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	s.DB.Exec("UPDATE users SET password_hash = ?, force_password_change = 1 WHERE id = ?", hash, id)
	s.DB.Exec("DELETE FROM sessions WHERE user_id = ?", id)

	w.WriteHeader(http.StatusNoContent)
}

// handleAdminToggleAdmin promotes or demotes a user.
func (s *Server) handleAdminToggleAdmin(w http.ResponseWriter, r *http.Request) {
	adminID := UserIDFromContext(r.Context())
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}

	if id == adminID {
		http.Error(w, "cannot change your own admin status", http.StatusBadRequest)
		return
	}

	s.DB.Exec("UPDATE users SET is_admin = CASE WHEN is_admin = 1 THEN 0 ELSE 1 END WHERE id = ?", id)
	w.WriteHeader(http.StatusNoContent)
}

// handleAdminDisableTOTP lets admin disable 2FA for a user (e.g. if they lost their device).
func (s *Server) handleAdminDisableTOTP(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}

	s.DB.Exec("UPDATE users SET totp_enabled = 0, totp_secret = NULL WHERE id = ?", id)
	w.WriteHeader(http.StatusNoContent)
}
