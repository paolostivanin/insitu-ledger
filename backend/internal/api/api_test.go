package api

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"fmt"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/pstivanin/insitu-ledger/backend/internal/auth"
	_ "modernc.org/sqlite"
)

// setupTestServer creates an in-memory SQLite database, applies the schema,
// seeds an admin user, and returns a configured Server + cleanup func.
func setupTestServer(t *testing.T) (*Server, func()) {
	t.Helper()
	db, err := sql.Open("sqlite", "file::memory:?_pragma=journal_mode(wal)&_pragma=foreign_keys(on)")
	if err != nil {
		t.Fatalf("open db: %v", err)
	}
	db.SetMaxOpenConns(1)

	schema := `
CREATE TABLE IF NOT EXISTS sync_meta (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    version INTEGER NOT NULL DEFAULT 0
);
INSERT OR IGNORE INTO sync_meta (id, version) VALUES (1, 0);

CREATE TABLE IF NOT EXISTS sessions (
    token TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    email TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    is_admin INTEGER NOT NULL DEFAULT 0,
    force_password_change INTEGER NOT NULL DEFAULT 0,
    totp_secret TEXT,
    totp_enabled INTEGER NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT (datetime('now')),
    updated_at DATETIME NOT NULL DEFAULT (datetime('now')),
    sync_version INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS categories (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    parent_id INTEGER REFERENCES categories(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('income', 'expense')),
    icon TEXT,
    color TEXT,
    created_at DATETIME NOT NULL DEFAULT (datetime('now')),
    updated_at DATETIME NOT NULL DEFAULT (datetime('now')),
    deleted_at DATETIME,
    sync_version INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS accounts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    currency TEXT NOT NULL DEFAULT 'EUR',
    balance REAL NOT NULL DEFAULT 0.0,
    created_at DATETIME NOT NULL DEFAULT (datetime('now')),
    updated_at DATETIME NOT NULL DEFAULT (datetime('now')),
    deleted_at DATETIME,
    sync_version INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS transactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    category_id INTEGER NOT NULL REFERENCES categories(id) ON DELETE RESTRICT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type TEXT NOT NULL CHECK (type IN ('income', 'expense')),
    amount REAL NOT NULL CHECK (amount > 0),
    currency TEXT NOT NULL DEFAULT 'EUR',
    description TEXT,
    note TEXT,
    date TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT (datetime('now')),
    updated_at DATETIME NOT NULL DEFAULT (datetime('now')),
    deleted_at DATETIME,
    sync_version INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS scheduled_transactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    category_id INTEGER NOT NULL REFERENCES categories(id) ON DELETE RESTRICT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type TEXT NOT NULL CHECK (type IN ('income', 'expense')),
    amount REAL NOT NULL CHECK (amount > 0),
    currency TEXT NOT NULL DEFAULT 'EUR',
    description TEXT,
    note TEXT,
    rrule TEXT NOT NULL,
    next_occurrence DATE NOT NULL,
    active INTEGER NOT NULL DEFAULT 1,
    max_occurrences INTEGER,
    occurrence_count INTEGER NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT (datetime('now')),
    updated_at DATETIME NOT NULL DEFAULT (datetime('now')),
    deleted_at DATETIME,
    sync_version INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS shared_access (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    guest_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    permission TEXT NOT NULL CHECK (permission IN ('read', 'write')),
    created_at DATETIME NOT NULL DEFAULT (datetime('now')),
    sync_version INTEGER NOT NULL DEFAULT 0,
    UNIQUE(owner_user_id, guest_user_id)
);

CREATE TABLE IF NOT EXISTS audit_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    admin_user_id INTEGER NOT NULL REFERENCES users(id),
    action TEXT NOT NULL,
    target_user_id INTEGER,
    details TEXT,
    ip_address TEXT,
    created_at DATETIME NOT NULL DEFAULT (datetime('now'))
);
`
	if _, err := db.Exec(schema); err != nil {
		t.Fatalf("apply schema: %v", err)
	}

	// Seed admin user (password: "testpassword")
	hash, _ := auth.HashPassword("testpassword")
	db.Exec(
		"INSERT INTO users (username, email, name, password_hash, is_admin) VALUES (?, ?, ?, ?, 1)",
		"admin", "admin@test.com", "Admin", hash,
	)

	s := &Server{
		DB:               db,
		AuthStore:        auth.NewStore(db),
		LoginRateLimiter: NewLoginRateLimiter(),
	}

	return s, func() { db.Close() }
}

// loginAdmin performs a login and returns the bearer token.
func loginAdmin(t *testing.T, handler http.Handler) string {
	t.Helper()
	body := `{"login":"admin","password":"testpassword"}`
	req := httptest.NewRequest("POST", "/api/auth/login", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("login failed: %d %s", w.Code, w.Body.String())
	}
	var resp map[string]any
	json.Unmarshal(w.Body.Bytes(), &resp)
	return resp["token"].(string)
}

func authedRequest(method, path string, body string, token string) *http.Request {
	var reader *strings.Reader
	if body != "" {
		reader = strings.NewReader(body)
	}
	var req *http.Request
	if reader != nil {
		req = httptest.NewRequest(method, path, reader)
		req.Header.Set("Content-Type", "application/json")
	} else {
		req = httptest.NewRequest(method, path, nil)
	}
	req.Header.Set("Authorization", "Bearer "+token)
	return req
}

// ===== Auth Tests =====

func TestLogin(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)

	tests := []struct {
		name     string
		body     string
		wantCode int
	}{
		{"valid login", `{"login":"admin","password":"testpassword"}`, 200},
		{"wrong password", `{"login":"admin","password":"wrong"}`, 401},
		{"unknown user", `{"login":"nobody","password":"test"}`, 401},
		{"empty body", `{}`, 401},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			req := httptest.NewRequest("POST", "/api/auth/login", strings.NewReader(tt.body))
			req.Header.Set("Content-Type", "application/json")
			w := httptest.NewRecorder()
			handler.ServeHTTP(w, req)
			if w.Code != tt.wantCode {
				t.Errorf("got %d, want %d: %s", w.Code, tt.wantCode, w.Body.String())
			}
		})
	}
}

func TestLogout(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	req := authedRequest("POST", "/api/auth/logout", "", token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 204 {
		t.Errorf("logout: got %d, want 204", w.Code)
	}

	// Token should be revoked
	req = authedRequest("GET", "/api/auth/me", "", token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 401 {
		t.Errorf("after logout: got %d, want 401", w.Code)
	}
}

func TestGetMe(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	req := authedRequest("GET", "/api/auth/me", "", token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("get me: got %d", w.Code)
	}
	var resp map[string]any
	json.Unmarshal(w.Body.Bytes(), &resp)
	if resp["username"] != "admin" {
		t.Errorf("username = %v, want admin", resp["username"])
	}
}

func TestUnauthorizedAccess(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)

	req := httptest.NewRequest("GET", "/api/transactions", nil)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 401 {
		t.Errorf("got %d, want 401", w.Code)
	}
}

// ===== Account Tests =====

func TestAccountsCRUD(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	// Create
	req := authedRequest("POST", "/api/accounts", `{"name":"Checking","currency":"EUR","balance":1000}`, token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("create account: got %d: %s", w.Code, w.Body.String())
	}
	var created map[string]any
	json.Unmarshal(w.Body.Bytes(), &created)
	accountID := int(created["id"].(float64))

	// List
	req = authedRequest("GET", "/api/accounts", "", token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("list accounts: got %d", w.Code)
	}
	var accts []map[string]any
	json.Unmarshal(w.Body.Bytes(), &accts)
	if len(accts) != 1 {
		t.Errorf("expected 1 account, got %d", len(accts))
	}

	// Update
	req = authedRequest("PUT", fmt.Sprintf("/api/accounts/%d", accountID), `{"name":"Savings"}`, token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 204 {
		t.Errorf("update account: got %d", w.Code)
	}

	// Delete
	req = authedRequest("DELETE", fmt.Sprintf("/api/accounts/%d", accountID), "", token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 204 {
		t.Errorf("delete account: got %d", w.Code)
	}
}

// ===== Category Tests =====

func TestCategoriesCRUD(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	// Create
	req := authedRequest("POST", "/api/categories", `{"name":"Food","type":"expense"}`, token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("create category: got %d: %s", w.Code, w.Body.String())
	}

	// List
	req = authedRequest("GET", "/api/categories", "", token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("list categories: got %d", w.Code)
	}
	var cats []map[string]any
	json.Unmarshal(w.Body.Bytes(), &cats)
	if len(cats) != 1 {
		t.Errorf("expected 1 category, got %d", len(cats))
	}
}

// ===== Transaction Tests =====

func createTestAccountAndCategory(t *testing.T, handler http.Handler, token string) (int, int) {
	t.Helper()

	req := authedRequest("POST", "/api/accounts", `{"name":"Test Account","currency":"EUR","balance":5000}`, token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var acct map[string]any
	json.Unmarshal(w.Body.Bytes(), &acct)
	acctID := int(acct["id"].(float64))

	req = authedRequest("POST", "/api/categories", `{"name":"Test Cat","type":"expense"}`, token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var cat map[string]any
	json.Unmarshal(w.Body.Bytes(), &cat)
	catID := int(cat["id"].(float64))

	return acctID, catID
}

func TestTransactionsCRUD(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	acctID, catID := createTestAccountAndCategory(t, handler, token)

	// Create
	body := fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"expense","amount":50.00,"date":"2025-01-15","description":"Groceries"}`, acctID, catID)
	req := authedRequest("POST", "/api/transactions", body, token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("create transaction: got %d: %s", w.Code, w.Body.String())
	}
	var created map[string]any
	json.Unmarshal(w.Body.Bytes(), &created)
	txnID := int(created["id"].(float64))

	// List
	req = authedRequest("GET", "/api/transactions", "", token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("list transactions: got %d", w.Code)
	}
	var txns []map[string]any
	json.Unmarshal(w.Body.Bytes(), &txns)
	if len(txns) != 1 {
		t.Errorf("expected 1 transaction, got %d", len(txns))
	}

	// Verify balance updated
	req = authedRequest("GET", "/api/accounts", "", token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var accts []map[string]any
	json.Unmarshal(w.Body.Bytes(), &accts)
	balance := accts[0]["balance"].(float64)
	if balance != -50.0 {
		t.Errorf("balance = %v, want -50", balance)
	}

	// Delete
	req = authedRequest("DELETE", fmt.Sprintf("/api/transactions/%d", txnID), "", token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 204 {
		t.Errorf("delete transaction: got %d", w.Code)
	}

	// Verify balance restored
	req = authedRequest("GET", "/api/accounts", "", token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	json.Unmarshal(w.Body.Bytes(), &accts)
	balance = accts[0]["balance"].(float64)
	if balance != 0.0 {
		t.Errorf("balance after delete = %v, want 0", balance)
	}
}

func TestTransactionNoteRoundTrip(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	acctID, catID := createTestAccountAndCategory(t, handler, token)

	// Create with a note.
	body := fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"expense","amount":10,"date":"2025-01-15","description":"Lunch","note":"Met with client at cafe"}`, acctID, catID)
	req := authedRequest("POST", "/api/transactions", body, token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("create transaction: got %d: %s", w.Code, w.Body.String())
	}

	// List and verify note round-trips.
	req = authedRequest("GET", "/api/transactions", "", token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var txns []map[string]any
	json.Unmarshal(w.Body.Bytes(), &txns)
	if len(txns) != 1 {
		t.Fatalf("expected 1 transaction, got %d", len(txns))
	}
	gotNote, _ := txns[0]["note"].(string)
	if gotNote != "Met with client at cafe" {
		t.Errorf("note round-trip: got %q", gotNote)
	}

	// Reject notes longer than 2000 characters.
	tooLong := strings.Repeat("x", 2001)
	body = fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"expense","amount":1,"date":"2025-01-16","note":"%s"}`, acctID, catID, tooLong)
	req = authedRequest("POST", "/api/transactions", body, token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 400 {
		t.Errorf("oversize note: got %d, want 400", w.Code)
	}
}

// ===== Batch Operations Tests =====

func TestBatchDelete(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	acctID, catID := createTestAccountAndCategory(t, handler, token)

	// Create 3 transactions
	var ids []int
	for i := 0; i < 3; i++ {
		body := fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"expense","amount":100,"date":"2025-01-%02d"}`, acctID, catID, i+1)
		req := authedRequest("POST", "/api/transactions", body, token)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, req)
		var created map[string]any
		json.Unmarshal(w.Body.Bytes(), &created)
		ids = append(ids, int(created["id"].(float64)))
	}

	// Batch delete first 2
	body := fmt.Sprintf(`{"ids":[%d,%d]}`, ids[0], ids[1])
	req := authedRequest("POST", "/api/transactions/batch-delete", body, token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 204 {
		t.Errorf("batch delete: got %d: %s", w.Code, w.Body.String())
	}

	// Verify only 1 remains
	req = authedRequest("GET", "/api/transactions", "", token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var txns []map[string]any
	json.Unmarshal(w.Body.Bytes(), &txns)
	if len(txns) != 1 {
		t.Errorf("expected 1 transaction remaining, got %d", len(txns))
	}
}

func TestBatchUpdateCategory(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	acctID, catID := createTestAccountAndCategory(t, handler, token)

	// Create another category
	req := authedRequest("POST", "/api/categories", `{"name":"Transport","type":"expense"}`, token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var newCat map[string]any
	json.Unmarshal(w.Body.Bytes(), &newCat)
	newCatID := int(newCat["id"].(float64))

	// Create transaction
	body := fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"expense","amount":50,"date":"2025-01-01"}`, acctID, catID)
	req = authedRequest("POST", "/api/transactions", body, token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var created map[string]any
	json.Unmarshal(w.Body.Bytes(), &created)
	txnID := int(created["id"].(float64))

	// Batch update category
	body = fmt.Sprintf(`{"ids":[%d],"category_id":%d}`, txnID, newCatID)
	req = authedRequest("POST", "/api/transactions/batch-update-category", body, token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 204 {
		t.Errorf("batch update category: got %d: %s", w.Code, w.Body.String())
	}
}

// ===== Import/Export Tests =====

func TestExportImportRoundTrip(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	acctID, catID := createTestAccountAndCategory(t, handler, token)

	// Create a transaction
	body := fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"expense","amount":42.50,"date":"2025-06-15","description":"test export"}`, acctID, catID)
	req := authedRequest("POST", "/api/transactions", body, token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("create: %d", w.Code)
	}

	// Export
	req = authedRequest("GET", "/api/transactions/export", "", token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("export: got %d", w.Code)
	}
	csvData := w.Body.String()
	if !strings.Contains(csvData, "42.50") {
		t.Errorf("export CSV missing amount: %s", csvData)
	}
	if !strings.Contains(csvData, "Test Cat") {
		t.Errorf("export CSV missing category name: %s", csvData)
	}

	// Delete the original transaction to test import
	req = authedRequest("GET", "/api/transactions", "", token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var txns []map[string]any
	json.Unmarshal(w.Body.Bytes(), &txns)
	txnID := int(txns[0]["id"].(float64))
	req = authedRequest("DELETE", fmt.Sprintf("/api/transactions/%d", txnID), "", token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	// Import the CSV back
	var buf bytes.Buffer
	writer := multipart.NewWriter(&buf)
	part, _ := writer.CreateFormFile("file", "transactions.csv")
	part.Write([]byte(csvData))
	writer.Close()

	req = httptest.NewRequest("POST", "/api/transactions/import", &buf)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	req.Header.Set("Authorization", "Bearer "+token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("import: got %d: %s", w.Code, w.Body.String())
	}
	var importResult map[string]any
	json.Unmarshal(w.Body.Bytes(), &importResult)
	if importResult["imported"].(float64) != 1 {
		t.Errorf("imported = %v, want 1", importResult["imported"])
	}
}

// ===== Admin Tests =====

func TestAdminCreateAndDeleteUser(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	// Create user
	body := `{"username":"newuser","email":"new@test.com","name":"New User","password":"password123"}`
	req := authedRequest("POST", "/api/admin/users", body, token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("create user: got %d: %s", w.Code, w.Body.String())
	}
	var created map[string]any
	json.Unmarshal(w.Body.Bytes(), &created)
	newUserID := int(created["id"].(float64))

	// List users
	req = authedRequest("GET", "/api/admin/users", "", token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var users []map[string]any
	json.Unmarshal(w.Body.Bytes(), &users)
	if len(users) != 2 {
		t.Errorf("expected 2 users, got %d", len(users))
	}

	// Delete user
	req = authedRequest("DELETE", fmt.Sprintf("/api/admin/users/%d", newUserID), "", token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 204 {
		t.Errorf("delete user: got %d", w.Code)
	}
}

func TestAdminCannotDeleteSelf(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	// Admin user id is 1
	req := authedRequest("DELETE", "/api/admin/users/1", "", token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 400 {
		t.Errorf("delete self: got %d, want 400", w.Code)
	}
}

func TestNonAdminCannotAccessAdminEndpoints(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken := loginAdmin(t, handler)

	// Create a non-admin user
	body := `{"username":"regular","email":"regular@test.com","name":"Regular","password":"password123"}`
	req := authedRequest("POST", "/api/admin/users", body, adminToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	// Login as regular user
	loginBody := `{"login":"regular","password":"password123"}`
	req = httptest.NewRequest("POST", "/api/auth/login", strings.NewReader(loginBody))
	req.Header.Set("Content-Type", "application/json")
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var loginResp map[string]any
	json.Unmarshal(w.Body.Bytes(), &loginResp)
	userToken := loginResp["token"].(string)

	// Try admin endpoint
	req = authedRequest("GET", "/api/admin/users", "", userToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 403 {
		t.Errorf("non-admin accessing admin: got %d, want 403", w.Code)
	}
}

// ===== Audit Log Tests =====

func TestAuditLogsCreated(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	// Create a user (triggers audit log)
	body := `{"username":"audituser","email":"audit@test.com","name":"Audit Test","password":"password123"}`
	req := authedRequest("POST", "/api/admin/users", body, token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	// Check audit logs
	req = authedRequest("GET", "/api/admin/audit-logs", "", token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("audit logs: got %d", w.Code)
	}
	var logs []map[string]any
	json.Unmarshal(w.Body.Bytes(), &logs)
	if len(logs) == 0 {
		t.Error("expected at least 1 audit log entry")
	}
	if logs[0]["action"] != "create_user" {
		t.Errorf("action = %v, want create_user", logs[0]["action"])
	}
}

// ===== User Isolation Tests =====

func TestUserIsolation(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken := loginAdmin(t, handler)

	// Create user2
	body := `{"username":"user2","email":"user2@test.com","name":"User2","password":"password123"}`
	req := authedRequest("POST", "/api/admin/users", body, adminToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	// Login as user2
	loginBody := `{"login":"user2","password":"password123"}`
	req = httptest.NewRequest("POST", "/api/auth/login", strings.NewReader(loginBody))
	req.Header.Set("Content-Type", "application/json")
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var loginResp map[string]any
	json.Unmarshal(w.Body.Bytes(), &loginResp)
	user2Token := loginResp["token"].(string)

	// Admin creates an account
	req = authedRequest("POST", "/api/accounts", `{"name":"Admin Account"}`, adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	// User2 should see no accounts
	req = authedRequest("GET", "/api/accounts", "", user2Token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var accts []map[string]any
	json.Unmarshal(w.Body.Bytes(), &accts)
	if len(accts) != 0 {
		t.Errorf("user2 should see 0 accounts, got %d", len(accts))
	}
}

// ===== Middleware Tests =====

func TestHealthEndpoint(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)

	req := httptest.NewRequest("GET", "/api/health", nil)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Errorf("health: got %d", w.Code)
	}
}

// ===== Sort Options Tests =====

func TestTransactionSortByDate(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)
	acctID, catID := createTestAccountAndCategory(t, handler, token)

	// Create transactions on different dates
	for _, d := range []string{"2025-01-10", "2025-01-05", "2025-01-15"} {
		body := fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"expense","amount":10,"date":"%s"}`, acctID, catID, d)
		req := authedRequest("POST", "/api/transactions", body, token)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, req)
	}

	// Default sort: date DESC
	req := authedRequest("GET", "/api/transactions", "", token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var txns []map[string]any
	json.Unmarshal(w.Body.Bytes(), &txns)
	if len(txns) != 3 {
		t.Fatalf("expected 3 transactions, got %d", len(txns))
	}
	if txns[0]["date"] != "2025-01-15" {
		t.Errorf("first txn date = %v, want 2025-01-15", txns[0]["date"])
	}

	// Sort date ASC
	req = authedRequest("GET", "/api/transactions?sort_by=date&sort_dir=asc", "", token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	json.Unmarshal(w.Body.Bytes(), &txns)
	if txns[0]["date"] != "2025-01-05" {
		t.Errorf("asc first txn date = %v, want 2025-01-05", txns[0]["date"])
	}
}

func TestTransactionSortByAmount(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)
	acctID, catID := createTestAccountAndCategory(t, handler, token)

	for _, a := range []float64{100, 10, 50} {
		body := fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"expense","amount":%.2f,"date":"2025-01-01"}`, acctID, catID, a)
		req := authedRequest("POST", "/api/transactions", body, token)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, req)
	}

	// Sort amount DESC
	req := authedRequest("GET", "/api/transactions?sort_by=amount&sort_dir=desc", "", token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var txns []map[string]any
	json.Unmarshal(w.Body.Bytes(), &txns)
	if txns[0]["amount"].(float64) != 100 {
		t.Errorf("first amount = %v, want 100", txns[0]["amount"])
	}

	// Sort amount ASC
	req = authedRequest("GET", "/api/transactions?sort_by=amount&sort_dir=asc", "", token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	json.Unmarshal(w.Body.Bytes(), &txns)
	if txns[0]["amount"].(float64) != 10 {
		t.Errorf("asc first amount = %v, want 10", txns[0]["amount"])
	}
}

func TestTransactionSortByCategory(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)
	acctID, _ := createTestAccountAndCategory(t, handler, token)

	// Create a second category
	req := authedRequest("POST", "/api/categories", `{"name":"Zebra","type":"expense"}`, token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var cat2 map[string]any
	json.Unmarshal(w.Body.Bytes(), &cat2)
	cat2ID := int(cat2["id"].(float64))

	req = authedRequest("POST", "/api/categories", `{"name":"Apple","type":"expense"}`, token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var cat3 map[string]any
	json.Unmarshal(w.Body.Bytes(), &cat3)
	cat3ID := int(cat3["id"].(float64))

	// Create transactions with different categories
	body := fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"expense","amount":10,"date":"2025-01-01"}`, acctID, cat2ID)
	req = authedRequest("POST", "/api/transactions", body, token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	body = fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"expense","amount":20,"date":"2025-01-01"}`, acctID, cat3ID)
	req = authedRequest("POST", "/api/transactions", body, token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	// Sort by category ASC
	req = authedRequest("GET", "/api/transactions?sort_by=category&sort_dir=asc", "", token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var txns []map[string]any
	json.Unmarshal(w.Body.Bytes(), &txns)
	if len(txns) != 2 {
		t.Fatalf("expected 2 transactions, got %d", len(txns))
	}
	// Apple < Zebra, so the Apple category transaction (amount=20) should be first
	if txns[0]["amount"].(float64) != 20 {
		t.Errorf("asc category sort: first amount = %v, want 20 (Apple)", txns[0]["amount"])
	}
}

func TestTransactionSortInvalid(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	// Invalid sort_by
	req := authedRequest("GET", "/api/transactions?sort_by=invalid", "", token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 400 {
		t.Errorf("invalid sort_by: got %d, want 400", w.Code)
	}

	// Invalid sort_dir
	req = authedRequest("GET", "/api/transactions?sort_dir=invalid", "", token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 400 {
		t.Errorf("invalid sort_dir: got %d, want 400", w.Code)
	}
}

// ===== Shared Access Filtering Tests =====

// setupTwoUsers creates admin + guest user and returns both tokens and guest user ID.
func setupTwoUsers(t *testing.T, handler http.Handler) (adminToken, guestToken string, guestID int) {
	t.Helper()
	adminToken = loginAdmin(t, handler)

	// Create guest user
	body := `{"username":"guest","email":"guest@test.com","name":"Guest","password":"password123"}`
	req := authedRequest("POST", "/api/admin/users", body, adminToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var created map[string]any
	json.Unmarshal(w.Body.Bytes(), &created)
	guestID = int(created["id"].(float64))

	// Login guest
	loginBody := `{"login":"guest","password":"password123"}`
	req = httptest.NewRequest("POST", "/api/auth/login", strings.NewReader(loginBody))
	req.Header.Set("Content-Type", "application/json")
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var loginResp map[string]any
	json.Unmarshal(w.Body.Bytes(), &loginResp)
	guestToken = loginResp["token"].(string)

	return
}

func TestSharedAccessListAccessible(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken, guestToken, _ := setupTwoUsers(t, handler)

	// Guest sees no accessible owners initially
	req := authedRequest("GET", "/api/shared/accessible", "", guestToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("accessible: got %d", w.Code)
	}
	var owners []map[string]any
	json.Unmarshal(w.Body.Bytes(), &owners)
	if len(owners) != 0 {
		t.Errorf("expected 0 accessible owners, got %d", len(owners))
	}

	// Admin shares with guest (read)
	req = authedRequest("POST", "/api/shared", `{"guest_email":"guest@test.com","permission":"read"}`, adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("create share: got %d: %s", w.Code, w.Body.String())
	}

	// Guest now sees admin as accessible owner
	req = authedRequest("GET", "/api/shared/accessible", "", guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	json.Unmarshal(w.Body.Bytes(), &owners)
	if len(owners) != 1 {
		t.Fatalf("expected 1 accessible owner, got %d", len(owners))
	}
	if owners[0]["permission"] != "read" {
		t.Errorf("permission = %v, want read", owners[0]["permission"])
	}
}

func TestSharedAccessReadOnly(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken, guestToken, _ := setupTwoUsers(t, handler)

	// Admin creates data
	acctID, catID := createTestAccountAndCategory(t, handler, adminToken)
	body := fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"expense","amount":25,"date":"2025-01-01"}`, acctID, catID)
	req := authedRequest("POST", "/api/transactions", body, adminToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var created map[string]any
	json.Unmarshal(w.Body.Bytes(), &created)
	txnID := int(created["id"].(float64))

	// Share with read permission
	req = authedRequest("POST", "/api/shared", `{"guest_email":"guest@test.com","permission":"read"}`, adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	// Guest can read admin's transactions
	req = authedRequest("GET", "/api/transactions?owner_id=1", "", guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("guest read: got %d: %s", w.Code, w.Body.String())
	}
	var txns []map[string]any
	json.Unmarshal(w.Body.Bytes(), &txns)
	if len(txns) != 1 {
		t.Errorf("expected 1 transaction, got %d", len(txns))
	}

	// Guest can read admin's accounts
	req = authedRequest("GET", "/api/accounts?owner_id=1", "", guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Errorf("guest read accounts: got %d", w.Code)
	}

	// Guest can read admin's categories
	req = authedRequest("GET", "/api/categories?owner_id=1", "", guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Errorf("guest read categories: got %d", w.Code)
	}

	// Guest CANNOT create transactions for admin (read-only)
	body = fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"expense","amount":10,"date":"2025-01-02"}`, acctID, catID)
	req = authedRequest("POST", "/api/transactions?owner_id=1", body, guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 403 {
		t.Errorf("guest create: got %d, want 403", w.Code)
	}

	// Guest CANNOT delete admin's transactions (read-only)
	req = authedRequest("DELETE", fmt.Sprintf("/api/transactions/%d?owner_id=1", txnID), "", guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 403 {
		t.Errorf("guest delete: got %d, want 403", w.Code)
	}

	// Guest CANNOT create accounts for admin (read-only)
	req = authedRequest("POST", "/api/accounts?owner_id=1", `{"name":"Hacked"}`, guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 403 {
		t.Errorf("guest create account: got %d, want 403", w.Code)
	}
}

func TestSharedAccessWritePermission(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken, guestToken, _ := setupTwoUsers(t, handler)

	// Admin creates data
	acctID, catID := createTestAccountAndCategory(t, handler, adminToken)

	// Share with write permission
	req := authedRequest("POST", "/api/shared", `{"guest_email":"guest@test.com","permission":"write"}`, adminToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	// Guest CAN create transactions for admin
	body := fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"expense","amount":30,"date":"2025-02-01"}`, acctID, catID)
	req = authedRequest("POST", "/api/transactions?owner_id=1", body, guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Errorf("guest write create: got %d: %s", w.Code, w.Body.String())
	}

	// Verify the transaction is visible under admin's data
	req = authedRequest("GET", "/api/transactions", "", adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var txns []map[string]any
	json.Unmarshal(w.Body.Bytes(), &txns)
	if len(txns) != 1 {
		t.Errorf("admin should see 1 transaction, got %d", len(txns))
	}
}

func TestSharedAccessNoShare(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	_, guestToken, _ := setupTwoUsers(t, handler)

	// Guest tries to access admin's data without any share → 403
	req := authedRequest("GET", "/api/transactions?owner_id=1", "", guestToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 403 {
		t.Errorf("no share access: got %d, want 403", w.Code)
	}

	req = authedRequest("GET", "/api/accounts?owner_id=1", "", guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 403 {
		t.Errorf("no share accounts: got %d, want 403", w.Code)
	}
}

func TestSharedAccessBackwardCompatible(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)
	acctID, catID := createTestAccountAndCategory(t, handler, token)

	// Create a transaction
	body := fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"expense","amount":10,"date":"2025-01-01"}`, acctID, catID)
	req := authedRequest("POST", "/api/transactions", body, token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	// Omitting owner_id returns own data (backward compatible)
	req = authedRequest("GET", "/api/transactions", "", token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("backward compat: got %d", w.Code)
	}
	var txns []map[string]any
	json.Unmarshal(w.Body.Bytes(), &txns)
	if len(txns) != 1 {
		t.Errorf("expected 1 transaction, got %d", len(txns))
	}
}

func TestSharedAccessReports(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken, guestToken, _ := setupTwoUsers(t, handler)

	acctID, catID := createTestAccountAndCategory(t, handler, adminToken)
	body := fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"expense","amount":50,"date":"2025-03-15"}`, acctID, catID)
	req := authedRequest("POST", "/api/transactions", body, adminToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	// Share with read
	req = authedRequest("POST", "/api/shared", `{"guest_email":"guest@test.com","permission":"read"}`, adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	// Guest can view reports for admin
	req = authedRequest("GET", "/api/reports/by-category?owner_id=1", "", guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Errorf("guest reports by-category: got %d", w.Code)
	}
	var results []map[string]any
	json.Unmarshal(w.Body.Bytes(), &results)
	if len(results) != 1 {
		t.Errorf("expected 1 report row, got %d", len(results))
	}

	req = authedRequest("GET", "/api/reports/by-month?owner_id=1", "", guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Errorf("guest reports by-month: got %d", w.Code)
	}
}

// ===== Helpers =====

func TestParseSortParams(t *testing.T) {
	tests := []struct {
		sortBy, sortDir  string
		wantCol, wantDir string
		wantJoin         bool
		wantErr          bool
	}{
		{"", "", "date", "DESC", false, false},
		{"date", "asc", "date", "ASC", false, false},
		{"amount", "desc", "amount", "DESC", false, false},
		{"description", "", "description", "DESC", false, false},
		{"category", "asc", "c.name", "ASC", true, false},
		{"invalid", "", "", "", false, true},
		{"date", "invalid", "", "", false, true},
	}

	for _, tt := range tests {
		t.Run(tt.sortBy+"/"+tt.sortDir, func(t *testing.T) {
			col, dir, join, err := parseSortParams(tt.sortBy, tt.sortDir)
			if tt.wantErr {
				if err == nil {
					t.Error("expected error")
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if col != tt.wantCol {
				t.Errorf("col = %v, want %v", col, tt.wantCol)
			}
			if dir != tt.wantDir {
				t.Errorf("dir = %v, want %v", dir, tt.wantDir)
			}
			if join != tt.wantJoin {
				t.Errorf("join = %v, want %v", join, tt.wantJoin)
			}
		})
	}
}

func TestChangePassword(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	body := `{"current_password":"testpassword","new_password":"newpassword123"}`
	req := authedRequest("POST", "/api/auth/change-password", body, token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 204 {
		t.Errorf("change password: got %d: %s", w.Code, w.Body.String())
	}

	// Login with new password should work
	loginBody := `{"login":"admin","password":"newpassword123"}`
	req = httptest.NewRequest("POST", "/api/auth/login", strings.NewReader(loginBody))
	req.Header.Set("Content-Type", "application/json")
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Errorf("login with new password: got %d", w.Code)
	}
}
