package auth

import (
	"database/sql"
	"net/http/httptest"
	"testing"

	_ "modernc.org/sqlite"
)

func setupTestDB(t *testing.T) *sql.DB {
	t.Helper()
	db, err := sql.Open("sqlite", "file::memory:?_pragma=foreign_keys(on)")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	db.Exec(`CREATE TABLE sessions (
		token TEXT PRIMARY KEY,
		user_id INTEGER NOT NULL,
		expires_at DATETIME NOT NULL,
		created_at DATETIME NOT NULL DEFAULT (datetime('now'))
	)`)
	return db
}

func TestHashAndCheckPassword(t *testing.T) {
	hash, err := HashPassword("mypassword")
	if err != nil {
		t.Fatal(err)
	}
	if !CheckPassword("mypassword", hash) {
		t.Error("CheckPassword should return true for correct password")
	}
	if CheckPassword("wrongpassword", hash) {
		t.Error("CheckPassword should return false for wrong password")
	}
}

func TestCreateAndValidateToken(t *testing.T) {
	db := setupTestDB(t)
	defer db.Close()
	store := NewStore(db)

	token, err := store.CreateToken(42)
	if err != nil {
		t.Fatal(err)
	}
	if token == "" {
		t.Fatal("token should not be empty")
	}

	userID, err := store.ValidateToken(token)
	if err != nil {
		t.Fatal(err)
	}
	if userID != 42 {
		t.Errorf("userID = %d, want 42", userID)
	}
}

func TestRevokeToken(t *testing.T) {
	db := setupTestDB(t)
	defer db.Close()
	store := NewStore(db)

	token, _ := store.CreateToken(1)
	store.RevokeToken(token)

	_, err := store.ValidateToken(token)
	if err == nil {
		t.Error("revoked token should not validate")
	}
}

func TestInvalidToken(t *testing.T) {
	db := setupTestDB(t)
	defer db.Close()
	store := NewStore(db)

	_, err := store.ValidateToken("nonexistent-token")
	if err == nil {
		t.Error("nonexistent token should not validate")
	}
}

func TestExtractToken(t *testing.T) {
	tests := []struct {
		name    string
		header  string
		want    string
		wantErr bool
	}{
		{"valid", "Bearer abc123", "abc123", false},
		{"empty", "", "", true},
		{"no bearer", "Token abc123", "", true},
		{"bearer only", "Bearer", "", true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			req := httptest.NewRequest("GET", "/", nil)
			if tt.header != "" {
				req.Header.Set("Authorization", tt.header)
			}
			got, err := ExtractToken(req)
			if tt.wantErr && err == nil {
				t.Error("expected error")
			}
			if !tt.wantErr && err != nil {
				t.Errorf("unexpected error: %v", err)
			}
			if got != tt.want {
				t.Errorf("got %q, want %q", got, tt.want)
			}
		})
	}
}
