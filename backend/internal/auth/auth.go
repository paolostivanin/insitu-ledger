package auth

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"net/http"
	"strings"
	"time"

	"golang.org/x/crypto/bcrypt"
)

var (
	ErrInvalidCredentials = errors.New("invalid credentials")
	ErrUnauthorized       = errors.New("unauthorized")
)

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
func (s *Store) CreateToken(userID int64) (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	token := hex.EncodeToString(b)
	expiresAt := time.Now().Add(30 * 24 * time.Hour) // 30 days

	_, err := s.db.Exec(
		"INSERT INTO sessions (token, user_id, expires_at) VALUES (?, ?, ?)",
		token, userID, expiresAt.UTC().Format(time.RFC3339),
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
		"SELECT user_id, expires_at FROM sessions WHERE token = ?", token,
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

	if time.Now().UTC().After(expiry) {
		s.RevokeToken(token)
		return 0, ErrUnauthorized
	}

	return userID, nil
}

// RevokeToken removes a token from the database.
func (s *Store) RevokeToken(token string) {
	s.db.Exec("DELETE FROM sessions WHERE token = ?", token)
}

// cleanup removes all expired tokens.
func (s *Store) cleanup() {
	s.db.Exec("DELETE FROM sessions WHERE expires_at < ?", time.Now().UTC().Format(time.RFC3339))
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
