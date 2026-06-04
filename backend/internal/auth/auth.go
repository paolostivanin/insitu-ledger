package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"net/http"
	"strings"
	"time"

	"golang.org/x/crypto/bcrypt"
)

// hashToken returns the lowercase hex SHA-256 of a bearer token. We store
// only the hash in the sessions table so that a database leak does not
// hand attackers any active sessions.
func hashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

var (
	ErrInvalidCredentials = errors.New("invalid credentials")
	ErrUnauthorized       = errors.New("unauthorized")
)

// TokenTTL is the lifetime of a freshly-issued or renewed session token.
// ValidateToken slides the expiry forward by this amount when fewer than
// renewalWindow remain, so an actively-used client never gets logged out for
// idle reasons.
const TokenTTL = 30 * 24 * time.Hour

// renewalWindow is the "near-expiry" cutoff: validations inside this window
// extend the token. 14 days gives a wide grace period — anyone who touches
// the app even once every couple of weeks stays logged in indefinitely —
// while still bounding the UPDATE frequency on the sessions table.
const renewalWindow = 14 * 24 * time.Hour

// Store persists session tokens in SQLite so they survive server restarts.
type Store struct {
	db *sql.DB
}

func NewStore(db *sql.DB) *Store {
	s := &Store{db: db}
	// Clean up expired tokens on startup
	s.cleanup()
	return s
}

// HashPassword hashes a plaintext password using bcrypt.
func HashPassword(password string) (string, error) {
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return "", err
	}
	return string(hash), nil
}

// CheckPassword compares a plaintext password against a bcrypt hash.
func CheckPassword(password, hash string) bool {
	return bcrypt.CompareHashAndPassword([]byte(hash), []byte(password)) == nil
}

// CreateToken generates a new session token for the given user and persists it.
// The plaintext token is returned to the caller (and on to the client); only
// its SHA-256 hash is stored in the database.
func (s *Store) CreateToken(userID int64) (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	token := hex.EncodeToString(b)
	expiresAt := time.Now().Add(TokenTTL)

	_, err := s.db.Exec(
		"INSERT INTO sessions (token, user_id, expires_at) VALUES (?, ?, ?)",
		hashToken(token), userID, expiresAt.UTC().Format(time.RFC3339),
	)
	if err != nil {
		return "", err
	}
	return token, nil
}

// ValidateToken checks if a token is valid and returns the associated user ID.
func (s *Store) ValidateToken(token string) (int64, error) {
	var userID int64
	var expiresAt string
	err := s.db.QueryRow(
		"SELECT user_id, expires_at FROM sessions WHERE token = ?", hashToken(token),
	).Scan(&userID, &expiresAt)
	if err == sql.ErrNoRows {
		return 0, ErrUnauthorized
	}
	if err != nil {
		return 0, ErrUnauthorized
	}

	expiry, err := time.Parse(time.RFC3339, expiresAt)
	if err != nil {
		// Try alternate SQLite datetime format
		expiry, err = time.Parse("2006-01-02 15:04:05", expiresAt)
		if err != nil {
			s.RevokeToken(token)
			return 0, ErrUnauthorized
		}
	}

	now := time.Now().UTC()
	if now.After(expiry) {
		s.RevokeToken(token)
		return 0, ErrUnauthorized
	}

	// Slide the expiry forward when the token is near the end of its window
	// so actively-used clients (Android sync every 15 min, browser usage) do
	// not get a surprise 401 on day 30. The 7-day window means at most one
	// UPDATE per ~3 weeks of continuous use.
	if expiry.Sub(now) < renewalWindow {
		newExpiry := now.Add(TokenTTL)
		s.db.Exec(
			"UPDATE sessions SET expires_at = ? WHERE token = ?",
			newExpiry.Format(time.RFC3339), hashToken(token),
		)
	}

	return userID, nil
}

// RevokeToken removes a token from the database.
func (s *Store) RevokeToken(token string) {
	s.db.Exec("DELETE FROM sessions WHERE token = ?", hashToken(token))
}

// RevokeAllForUser removes all session tokens for the given user.
func (s *Store) RevokeAllForUser(userID int64) {
	s.db.Exec("DELETE FROM sessions WHERE user_id = ?", userID)
}

// cleanup removes all expired tokens.
func (s *Store) cleanup() {
	s.db.Exec("DELETE FROM sessions WHERE expires_at < ?", time.Now().UTC().Format(time.RFC3339))
	s.cleanupTrusted()
}

// ExtractToken pulls the bearer token from an HTTP request.
func ExtractToken(r *http.Request) (string, error) {
	auth := r.Header.Get("Authorization")
	if auth == "" {
		return "", ErrUnauthorized
	}
	parts := strings.SplitN(auth, " ", 2)
	if len(parts) != 2 || strings.ToLower(parts[0]) != "bearer" {
		return "", ErrUnauthorized
	}
	return parts[1], nil
}
