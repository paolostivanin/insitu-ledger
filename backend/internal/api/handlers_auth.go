package api

import (
	"bytes"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"image/png"
	"net/http"
	"strconv"
	"strings"

	"github.com/pquerna/otp/totp"
	"github.com/pstivanin/insitu-ledger/backend/internal/auth"
)

type loginRequest struct {
	Login    string `json:"login"` // username or email
	Password string `json:"password"`
	TOTPCode string `json:"totp_code,omitempty"`
}

type changePasswordRequest struct {
	CurrentPassword string `json:"current_password"`
	NewPassword     string `json:"new_password"`
}

type updateProfileRequest struct {
	Username string `json:"username,omitempty"`
	Email    string `json:"email,omitempty"`
	Name     string `json:"name,omitempty"`
}

type authResponse struct {
	Token               string `json:"token,omitempty"`
	UserID              int64  `json:"user_id"`
	Name                string `json:"name"`
	IsAdmin             bool   `json:"is_admin"`
	ForcePasswordChange bool   `json:"force_password_change"`
	TOTPEnabled         bool   `json:"totp_enabled"`
	TOTPRequired        bool   `json:"totp_required,omitempty"`
}

func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	ip := r.RemoteAddr
	if s.TrustProxy {
		if fwd := r.Header.Get("X-Forwarded-For"); fwd != "" {
			if i := strings.Index(fwd, ","); i != -1 {
				ip = strings.TrimSpace(fwd[:i])
			} else {
				ip = strings.TrimSpace(fwd)
			}
		}
	}
	if !s.LoginRateLimiter.Allow(ip) {
		http.Error(w, "too many login attempts, try again later", http.StatusTooManyRequests)
		return
	}

	var req loginRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	var userID int64
	var name, passwordHash string
	var isAdmin, forcePasswordChange, totpEnabled bool
	var totpSecret *string
	err := s.DB.QueryRow(
		`SELECT id, name, password_hash, is_admin, force_password_change, totp_enabled, totp_secret
		 FROM users WHERE email = ? OR username = ?`, req.Login, req.Login,
	).Scan(&userID, &name, &passwordHash, &isAdmin, &forcePasswordChange, &totpEnabled, &totpSecret)
	if err == sql.ErrNoRows {
		// Prevent timing-based user enumeration: run bcrypt against a dummy
		// hash so the response time is similar to a valid-user-wrong-password path.
		auth.CheckPassword(req.Password, "$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ012")
		http.Error(w, "invalid credentials", http.StatusUnauthorized)
		return
	}
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	if !auth.CheckPassword(req.Password, passwordHash) {
		http.Error(w, "invalid credentials", http.StatusUnauthorized)
		return
	}

	// If 2FA is enabled, verify the TOTP code
	if totpEnabled && totpSecret != nil {
		if req.TOTPCode == "" {
			// Tell the client that 2FA is required
			w.Header().Set("Content-Type", "application/json")
			json.NewEncoder(w).Encode(authResponse{
				UserID:       userID,
				Name:         name,
				TOTPRequired: true,
			})
			return
		}
		// Per-user TOTP throttle. 6 digits + ±1 step window are guessable in
		// hours of unattended brute force, so cap failed attempts.
		totpKey := strconv.FormatInt(userID, 10)
		if !s.TOTPRateLimiter.Allow(totpKey) {
			http.Error(w, "too many 2FA attempts, try again later", http.StatusTooManyRequests)
			return
		}
		if !totp.Validate(req.TOTPCode, *totpSecret) {
			http.Error(w, "invalid 2FA code", http.StatusUnauthorized)
			return
		}
		s.TOTPRateLimiter.Reset(totpKey)
	}

	token, err := s.AuthStore.CreateToken(userID)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(authResponse{
		Token:               token,
		UserID:              userID,
		Name:                name,
		IsAdmin:             isAdmin,
		ForcePasswordChange: forcePasswordChange,
		TOTPEnabled:         totpEnabled,
	})
}

func (s *Server) handleLogout(w http.ResponseWriter, r *http.Request) {
	token, err := auth.ExtractToken(r)
	if err != nil {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	s.AuthStore.RevokeToken(token)
	w.WriteHeader(http.StatusNoContent)
}

// handleChangePassword lets any user change their own password.
func (s *Server) handleChangePassword(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())
	var req changePasswordRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	if len(req.NewPassword) < 8 {
		http.Error(w, "new password must be at least 8 characters", http.StatusBadRequest)
		return
	}

	var currentHash string
	err := s.DB.QueryRow("SELECT password_hash FROM users WHERE id = ?", userID).Scan(&currentHash)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	if !auth.CheckPassword(req.CurrentPassword, currentHash) {
		http.Error(w, "current password is incorrect", http.StatusUnauthorized)
		return
	}

	newHash, err := auth.HashPassword(req.NewPassword)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	if _, err := s.DB.Exec("UPDATE users SET password_hash = ?, force_password_change = 0 WHERE id = ?", newHash, userID); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	// Invalidate all existing sessions so stolen/old tokens can't be reused.
	s.AuthStore.RevokeAllForUser(userID)
	w.WriteHeader(http.StatusNoContent)
}

// handleUpdateProfile lets a user update their username, email, and name.
func (s *Server) handleUpdateProfile(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	var req updateProfileRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	if req.Username != "" {
		if err := validateLength("username", req.Username, 100); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		_, err := s.DB.Exec("UPDATE users SET username = ? WHERE id = ?", req.Username, userID)
		if err != nil {
			http.Error(w, "username already in use", http.StatusConflict)
			return
		}
	}

	if req.Email != "" {
		if err := validateLength("email", req.Email, 255); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		_, err := s.DB.Exec("UPDATE users SET email = ? WHERE id = ?", req.Email, userID)
		if err != nil {
			http.Error(w, "email already in use", http.StatusConflict)
			return
		}
	}

	if req.Name != "" {
		if err := validateLength("name", req.Name, 100); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		if _, err := s.DB.Exec("UPDATE users SET name = ? WHERE id = ?", req.Name, userID); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
	}

	w.WriteHeader(http.StatusNoContent)
}

// handleGetMe returns the current user's profile.
func (s *Server) handleGetMe(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())
	var username, name, email string
	var isAdmin, forcePasswordChange, totpEnabled bool
	err := s.DB.QueryRow(
		"SELECT username, name, email, is_admin, force_password_change, totp_enabled FROM users WHERE id = ?", userID,
	).Scan(&username, &name, &email, &isAdmin, &forcePasswordChange, &totpEnabled)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"id":                    userID,
		"username":              username,
		"name":                  name,
		"email":                 email,
		"is_admin":              isAdmin,
		"force_password_change": forcePasswordChange,
		"totp_enabled":          totpEnabled,
	})
}

// --- 2FA (TOTP) ---

// handleTOTPSetup generates a new TOTP secret and returns a QR code.
func (s *Server) handleTOTPSetup(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	var email string
	if err := s.DB.QueryRow("SELECT email FROM users WHERE id = ?", userID).Scan(&email); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	key, err := totp.Generate(totp.GenerateOpts{
		Issuer:      "InSitu Ledger",
		AccountName: email,
	})
	if err != nil {
		http.Error(w, "failed to generate TOTP secret", http.StatusInternalServerError)
		return
	}

	// Store the secret (not yet enabled — user must verify first)
	if _, err := s.DB.Exec("UPDATE users SET totp_secret = ? WHERE id = ?", key.Secret(), userID); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	// Generate QR code as base64 PNG
	img, err := key.Image(200, 200)
	if err != nil {
		http.Error(w, "failed to generate QR code", http.StatusInternalServerError)
		return
	}

	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		http.Error(w, "failed to encode QR code", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"secret":   key.Secret(),
		"qr_code":  "data:image/png;base64," + base64.StdEncoding.EncodeToString(buf.Bytes()),
		"otpauth":  key.URL(),
	})
}

// handleTOTPVerify verifies a TOTP code and enables 2FA.
func (s *Server) handleTOTPVerify(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	var req struct {
		Code string `json:"code"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	var secret *string
	if err := s.DB.QueryRow("SELECT totp_secret FROM users WHERE id = ?", userID).Scan(&secret); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if secret == nil {
		http.Error(w, "2FA not set up yet", http.StatusBadRequest)
		return
	}

	if !totp.Validate(req.Code, *secret) {
		http.Error(w, "invalid code — try again", http.StatusBadRequest)
		return
	}

	if _, err := s.DB.Exec("UPDATE users SET totp_enabled = 1 WHERE id = ?", userID); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// handleTOTPReset resets 2FA: clears the old secret and generates a new one.
// The user must verify the new code via handleTOTPVerify to re-enable.
func (s *Server) handleTOTPReset(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	var req struct {
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	// Require password confirmation
	var hash, email string
	if err := s.DB.QueryRow("SELECT password_hash, email FROM users WHERE id = ?", userID).Scan(&hash, &email); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if !auth.CheckPassword(req.Password, hash) {
		http.Error(w, "incorrect password", http.StatusUnauthorized)
		return
	}

	// Disable current 2FA
	if _, err := s.DB.Exec("UPDATE users SET totp_enabled = 0, totp_secret = NULL WHERE id = ?", userID); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	// Generate new TOTP secret
	key, err := totp.Generate(totp.GenerateOpts{
		Issuer:      "InSitu Ledger",
		AccountName: email,
	})
	if err != nil {
		http.Error(w, "failed to generate TOTP secret", http.StatusInternalServerError)
		return
	}

	if _, err := s.DB.Exec("UPDATE users SET totp_secret = ? WHERE id = ?", key.Secret(), userID); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	img, err := key.Image(200, 200)
	if err != nil {
		http.Error(w, "failed to generate QR code", http.StatusInternalServerError)
		return
	}

	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		http.Error(w, "failed to encode QR code", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"secret":  key.Secret(),
		"qr_code": "data:image/png;base64," + base64.StdEncoding.EncodeToString(buf.Bytes()),
		"otpauth": key.URL(),
	})
}
