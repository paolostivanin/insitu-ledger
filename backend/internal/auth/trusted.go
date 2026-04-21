package auth

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"time"
)

type TrustedDevice struct {
	ID         int64     `json:"id"`
	Label      string    `json:"label"`
	CreatedAt  time.Time `json:"created_at"`
	LastUsedAt time.Time `json:"last_used_at"`
	ExpiresAt  time.Time `json:"expires_at"`
}

// CreateTrustedDevice generates a new trusted-device token for the user and
// persists its SHA-256 hash. The plaintext token is returned to the caller
// and is meant to be set as a long-lived HttpOnly cookie.
func (s *Store) CreateTrustedDevice(userID int64, label string, ttl time.Duration) (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	token := hex.EncodeToString(b)
	expiresAt := time.Now().Add(ttl)

	_, err := s.db.Exec(
		`INSERT INTO trusted_devices (token_hash, user_id, label, expires_at)
		 VALUES (?, ?, ?, ?)`,
		hashToken(token), userID, label, expiresAt.UTC().Format(time.RFC3339),
	)
	if err != nil {
		return "", err
	}
	return token, nil
}

// ValidateTrustedDevice returns true iff the token belongs to the given user
// and has not expired. On a hit, last_used_at is bumped. A miss / mismatch /
// expired token returns (false, nil) — not an error — so the caller can fall
// back to the standard TOTP prompt.
func (s *Store) ValidateTrustedDevice(token string, userID int64) (bool, error) {
	if token == "" {
		return false, nil
	}
	var id int64
	var expiresAt string
	err := s.db.QueryRow(
		`SELECT id, expires_at FROM trusted_devices
		 WHERE token_hash = ? AND user_id = ?`,
		hashToken(token), userID,
	).Scan(&id, &expiresAt)
	if err == sql.ErrNoRows {
		return false, nil
	}
	if err != nil {
		return false, err
	}

	expiry, err := time.Parse(time.RFC3339, expiresAt)
	if err != nil {
		expiry, err = time.Parse("2006-01-02 15:04:05", expiresAt)
		if err != nil {
			s.db.Exec("DELETE FROM trusted_devices WHERE id = ?", id)
			return false, nil
		}
	}
	if time.Now().UTC().After(expiry) {
		s.db.Exec("DELETE FROM trusted_devices WHERE id = ?", id)
		return false, nil
	}

	s.db.Exec(
		"UPDATE trusted_devices SET last_used_at = ? WHERE id = ?",
		time.Now().UTC().Format(time.RFC3339), id,
	)
	return true, nil
}

// ListTrustedDevices returns all (non-expired) trusted devices for a user,
// most-recently-used first. The token hash is never returned.
func (s *Store) ListTrustedDevices(userID int64) ([]TrustedDevice, error) {
	rows, err := s.db.Query(
		`SELECT id, COALESCE(label, ''), created_at, last_used_at, expires_at
		 FROM trusted_devices
		 WHERE user_id = ? AND expires_at > ?
		 ORDER BY last_used_at DESC`,
		userID, time.Now().UTC().Format(time.RFC3339),
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]TrustedDevice, 0)
	for rows.Next() {
		var d TrustedDevice
		var created, lastUsed, expires string
		if err := rows.Scan(&d.ID, &d.Label, &created, &lastUsed, &expires); err != nil {
			return nil, err
		}
		d.CreatedAt = parseTimeFlex(created)
		d.LastUsedAt = parseTimeFlex(lastUsed)
		d.ExpiresAt = parseTimeFlex(expires)
		out = append(out, d)
	}
	return out, rows.Err()
}

// RevokeTrustedDevice deletes a single trusted device, scoped to the calling
// user so a stolen device id cannot revoke someone else's trust.
func (s *Store) RevokeTrustedDevice(userID, id int64) error {
	_, err := s.db.Exec(
		"DELETE FROM trusted_devices WHERE id = ? AND user_id = ?", id, userID,
	)
	return err
}

// RevokeAllTrustedDevicesForUser removes every trusted-device grant for a
// user. Called on password change and on 2FA reset.
func (s *Store) RevokeAllTrustedDevicesForUser(userID int64) {
	s.db.Exec("DELETE FROM trusted_devices WHERE user_id = ?", userID)
}

// cleanupTrusted removes expired trusted-device rows. Invoked from the
// existing Store.cleanup() on startup.
func (s *Store) cleanupTrusted() {
	s.db.Exec(
		"DELETE FROM trusted_devices WHERE expires_at < ?",
		time.Now().UTC().Format(time.RFC3339),
	)
}

func parseTimeFlex(s string) time.Time {
	if t, err := time.Parse(time.RFC3339, s); err == nil {
		return t
	}
	if t, err := time.Parse("2006-01-02 15:04:05", s); err == nil {
		return t
	}
	return time.Time{}
}
