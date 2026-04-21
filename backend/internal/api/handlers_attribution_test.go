package api

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"testing"
)

// TestCreateTransactionRecordsAuthenticatedCreator verifies that when a
// co-owner posts a transaction on a shared account, the row's
// created_by_user_id is the guest (auth user) while user_id stays as the
// account owner (legacy column).
func TestCreateTransactionRecordsAuthenticatedCreator(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken, guestToken, guestID := setupTwoUsers(t, handler)

	acctID, catID := createTestAccountAndCategory(t, handler, adminToken)

	body := fmt.Sprintf(`{"guest_email":"guest@test.com","account_id":%d}`, acctID)
	req := authedRequest("POST", "/api/shared", body, adminToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("share: got %d: %s", w.Code, w.Body.String())
	}

	// Guest creates a transaction on the shared account.
	body = fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"expense","amount":42,"date":"2025-04-01"}`, acctID, catID)
	req = authedRequest("POST", "/api/transactions?owner_id=1", body, guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("guest create: got %d: %s", w.Code, w.Body.String())
	}
	var created map[string]any
	json.Unmarshal(w.Body.Bytes(), &created)
	txnID := int64(created["id"].(float64))

	// Inspect the row directly: created_by_user_id must equal the GUEST id;
	// user_id (legacy) must equal the OWNER id (admin = id 1).
	var createdBy sql.NullInt64
	var userID int64
	if err := s.DB.QueryRow(
		`SELECT user_id, created_by_user_id FROM transactions WHERE id = ?`, txnID,
	).Scan(&userID, &createdBy); err != nil {
		t.Fatalf("scan transaction: %v", err)
	}
	if userID != 1 {
		t.Errorf("user_id = %d, want 1 (account owner)", userID)
	}
	if !createdBy.Valid || createdBy.Int64 != int64(guestID) {
		t.Errorf("created_by_user_id = %v, want %d (guest)", createdBy, guestID)
	}
}

// TestListTransactionsAggregatesOwnAndShared: with no owner_id, the list must
// include rows from BOTH the auth user's own accounts and any shared accounts.
// With owner_id set, results scope to that owner's space.
func TestListTransactionsAggregatesOwnAndShared(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken, guestToken, _ := setupTwoUsers(t, handler)

	// Admin creates an account + transaction.
	adminAcctID, adminCatID := createTestAccountAndCategory(t, handler, adminToken)
	body := fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"expense","amount":10,"date":"2025-04-01"}`, adminAcctID, adminCatID)
	req := authedRequest("POST", "/api/transactions", body, adminToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("admin txn: got %d: %s", w.Code, w.Body.String())
	}

	// Admin shares with guest.
	body = fmt.Sprintf(`{"guest_email":"guest@test.com","account_id":%d}`, adminAcctID)
	req = authedRequest("POST", "/api/shared", body, adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("share: got %d: %s", w.Code, w.Body.String())
	}

	// Guest creates own account + transaction.
	guestAcctID, guestCatID := createTestAccountAndCategory(t, handler, guestToken)
	body = fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"income","amount":99,"date":"2025-04-02"}`, guestAcctID, guestCatID)
	req = authedRequest("POST", "/api/transactions", body, guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("guest txn: got %d: %s", w.Code, w.Body.String())
	}

	// Guest lists with NO owner_id → both transactions visible (own + shared).
	req = authedRequest("GET", "/api/transactions", "", guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("aggregate list: got %d: %s", w.Code, w.Body.String())
	}
	var txns []map[string]any
	json.Unmarshal(w.Body.Bytes(), &txns)
	if len(txns) != 2 {
		t.Errorf("aggregate list: got %d transactions, want 2", len(txns))
	}

	// Guest lists with owner_id=1 → only admin's transaction visible.
	req = authedRequest("GET", "/api/transactions?owner_id=1", "", guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("filter list: got %d: %s", w.Code, w.Body.String())
	}
	json.Unmarshal(w.Body.Bytes(), &txns)
	if len(txns) != 1 {
		t.Errorf("filter list: got %d transactions, want 1", len(txns))
	}
	if got := int64(txns[0]["account_id"].(float64)); got != int64(adminAcctID) {
		t.Errorf("filter list account_id = %d, want %d", got, adminAcctID)
	}
}

// TestListReturnsCreatedByName verifies the GET /api/transactions response
// includes the denormalized creator name for the "Added by [name]" UI.
func TestListReturnsCreatedByName(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken, guestToken, guestID := setupTwoUsers(t, handler)

	acctID, catID := createTestAccountAndCategory(t, handler, adminToken)

	body := fmt.Sprintf(`{"guest_email":"guest@test.com","account_id":%d}`, acctID)
	req := authedRequest("POST", "/api/shared", body, adminToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("share: got %d", w.Code)
	}

	// Guest writes a transaction.
	body = fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"expense","amount":7,"date":"2025-04-03"}`, acctID, catID)
	req = authedRequest("POST", "/api/transactions?owner_id=1", body, guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("guest create: got %d: %s", w.Code, w.Body.String())
	}

	// Admin lists transactions and should see "Added by Guest".
	req = authedRequest("GET", "/api/transactions", "", adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("admin list: got %d", w.Code)
	}
	var txns []map[string]any
	json.Unmarshal(w.Body.Bytes(), &txns)
	if len(txns) != 1 {
		t.Fatalf("expected 1 transaction, got %d", len(txns))
	}
	if got := txns[0]["created_by_name"]; got != "Guest" {
		t.Errorf("created_by_name = %v, want \"Guest\"", got)
	}
	if got := int64(txns[0]["created_by_user_id"].(float64)); got != int64(guestID) {
		t.Errorf("created_by_user_id = %d, want %d", got, guestID)
	}
}

// TestUpdateDoesNotChangeCreatedBy: edits by a different co-user must NOT
// rewrite the original creator. created_by_user_id is sticky.
func TestUpdateDoesNotChangeCreatedBy(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken, guestToken, _ := setupTwoUsers(t, handler)

	acctID, catID := createTestAccountAndCategory(t, handler, adminToken)
	body := fmt.Sprintf(`{"guest_email":"guest@test.com","account_id":%d}`, acctID)
	req := authedRequest("POST", "/api/shared", body, adminToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("share: got %d", w.Code)
	}

	// Admin creates a transaction.
	body = fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"expense","amount":15,"date":"2025-04-04"}`, acctID, catID)
	req = authedRequest("POST", "/api/transactions", body, adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var created map[string]any
	json.Unmarshal(w.Body.Bytes(), &created)
	txnID := int64(created["id"].(float64))

	var origCreator sql.NullInt64
	if err := s.DB.QueryRow(
		`SELECT created_by_user_id FROM transactions WHERE id = ?`, txnID,
	).Scan(&origCreator); err != nil {
		t.Fatalf("scan: %v", err)
	}
	if !origCreator.Valid || origCreator.Int64 != 1 {
		t.Fatalf("seed creator = %v, want 1 (admin)", origCreator)
	}

	// Guest edits.
	body = fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"expense","amount":99,"date":"2025-04-04","description":"edited by guest"}`, acctID, catID)
	req = authedRequest("PUT", fmt.Sprintf("/api/transactions/%d?owner_id=1", txnID), body, guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 204 {
		t.Fatalf("guest update: got %d: %s", w.Code, w.Body.String())
	}

	var afterCreator sql.NullInt64
	if err := s.DB.QueryRow(
		`SELECT created_by_user_id FROM transactions WHERE id = ?`, txnID,
	).Scan(&afterCreator); err != nil {
		t.Fatalf("scan: %v", err)
	}
	if !afterCreator.Valid || afterCreator.Int64 != 1 {
		t.Errorf("after-update creator = %v, want 1 (admin) — sticky attribution broken", afterCreator)
	}
}

// TestAccountListExposesOwnershipFlags: the GET /api/accounts response must
// include is_shared, owner_user_id, owner_name so the frontend can render the
// "Shared by [name]" badge and gate owner-only controls.
func TestAccountListExposesOwnershipFlags(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken, guestToken, _ := setupTwoUsers(t, handler)

	acctID, _ := createTestAccountAndCategory(t, handler, adminToken)

	// Before sharing: account is owned but not shared.
	req := authedRequest("GET", "/api/accounts", "", adminToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var accts []map[string]any
	json.Unmarshal(w.Body.Bytes(), &accts)
	if len(accts) != 1 {
		t.Fatalf("admin accounts: got %d, want 1", len(accts))
	}
	if accts[0]["is_shared"] != false {
		t.Errorf("pre-share is_shared = %v, want false", accts[0]["is_shared"])
	}
	if accts[0]["owner_name"] != "Admin" {
		t.Errorf("owner_name = %v, want Admin", accts[0]["owner_name"])
	}

	// Share, then re-list: is_shared flips to true.
	body := fmt.Sprintf(`{"guest_email":"guest@test.com","account_id":%d}`, acctID)
	req = authedRequest("POST", "/api/shared", body, adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("share: got %d", w.Code)
	}

	req = authedRequest("GET", "/api/accounts", "", adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	json.Unmarshal(w.Body.Bytes(), &accts)
	if accts[0]["is_shared"] != true {
		t.Errorf("post-share is_shared = %v, want true", accts[0]["is_shared"])
	}

	// Guest sees the same account aggregated into their own list, with
	// owner_name = Admin.
	req = authedRequest("GET", "/api/accounts", "", guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	json.Unmarshal(w.Body.Bytes(), &accts)
	if len(accts) != 1 {
		t.Fatalf("guest aggregated accounts: got %d, want 1", len(accts))
	}
	if accts[0]["owner_name"] != "Admin" {
		t.Errorf("guest sees owner_name = %v, want Admin", accts[0]["owner_name"])
	}
	if accts[0]["is_shared"] != true {
		t.Errorf("guest sees is_shared = %v, want true", accts[0]["is_shared"])
	}
}

// TestCoOwnerCannotRenameOrDelete: even with full transaction-level co-owner
// rights, a guest cannot rename or delete the shared account. These are
// owner-only operations.
func TestCoOwnerCannotRenameOrDelete(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken, guestToken, _ := setupTwoUsers(t, handler)

	acctID, _ := createTestAccountAndCategory(t, handler, adminToken)
	body := fmt.Sprintf(`{"guest_email":"guest@test.com","account_id":%d}`, acctID)
	req := authedRequest("POST", "/api/shared", body, adminToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("share: got %d", w.Code)
	}

	req = authedRequest("PUT", fmt.Sprintf("/api/accounts/%d", acctID), `{"name":"Renamed","currency":"EUR"}`, guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 403 {
		t.Errorf("guest rename: got %d, want 403", w.Code)
	}

	req = authedRequest("DELETE", fmt.Sprintf("/api/accounts/%d", acctID), "", guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 403 {
		t.Errorf("guest delete: got %d, want 403", w.Code)
	}
}

// TestOwnerOnlyCanShare: a co-owner cannot create new shares on an account
// they don't own — re-sharing is an owner-only operation.
func TestOwnerOnlyCanShare(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken, guestToken, _ := setupTwoUsers(t, handler)

	acctID, _ := createTestAccountAndCategory(t, handler, adminToken)
	body := fmt.Sprintf(`{"guest_email":"guest@test.com","account_id":%d}`, acctID)
	req := authedRequest("POST", "/api/shared", body, adminToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("seed share: got %d", w.Code)
	}

	// Create a third user for guest to attempt re-sharing to.
	body = `{"username":"third","email":"third@test.com","name":"Third","password":"password123"}`
	req = authedRequest("POST", "/api/admin/users", body, adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("create third user: got %d", w.Code)
	}

	body = fmt.Sprintf(`{"guest_email":"third@test.com","account_id":%d}`, acctID)
	req = authedRequest("POST", "/api/shared", body, guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 403 {
		t.Errorf("guest re-share: got %d, want 403", w.Code)
	}
}

// TestMigrationCoercesReadToWrite: rows that pre-date v1.15.0 may have
// permission='read'. The migration coerces them to 'write' so the API model
// is uniform.
func TestMigrationCoercesReadToWrite(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken, _, guestID := setupTwoUsers(t, handler)
	acctID, _ := createTestAccountAndCategory(t, handler, adminToken)

	// Drop & recreate the table without the new CHECK so we can seed a 'read'
	// row, simulating a pre-v1.15.0 share.
	if _, err := s.DB.Exec(`DROP TABLE shared_account_access`); err != nil {
		t.Fatalf("drop table: %v", err)
	}
	if _, err := s.DB.Exec(`CREATE TABLE shared_account_access (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		owner_user_id INTEGER NOT NULL,
		guest_user_id INTEGER NOT NULL,
		account_id INTEGER NOT NULL,
		permission TEXT NOT NULL DEFAULT 'read',
		created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
		UNIQUE(guest_user_id, account_id)
	)`); err != nil {
		t.Fatalf("recreate legacy table: %v", err)
	}
	if _, err := s.DB.Exec(
		`INSERT INTO shared_account_access (owner_user_id, guest_user_id, account_id, permission)
		 VALUES (1, ?, ?, 'read')`, guestID, acctID,
	); err != nil {
		t.Fatalf("seed legacy row: %v", err)
	}

	// Run the same coercion the production migration runs.
	if _, err := s.DB.Exec(
		`UPDATE shared_account_access SET permission = 'write' WHERE permission = 'read'`,
	); err != nil {
		t.Fatalf("coerce: %v", err)
	}

	var perm string
	if err := s.DB.QueryRow(
		`SELECT permission FROM shared_account_access WHERE account_id = ? AND guest_user_id = ?`,
		acctID, guestID,
	).Scan(&perm); err != nil {
		t.Fatalf("scan: %v", err)
	}
	if perm != "write" {
		t.Errorf("permission = %q, want \"write\"", perm)
	}
}

// TestMigrationBackfillsCreatedBy: simulates an upgrade by inserting a
// transaction with a NULL created_by_user_id, then re-runs the backfill SQL
// the migration uses, and asserts every row is filled in.
func TestMigrationBackfillsCreatedBy(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken := loginAdmin(t, handler)
	acctID, catID := createTestAccountAndCategory(t, handler, adminToken)

	// Insert a row directly with NULL created_by_user_id (simulates a row
	// from before v1.15.0).
	if _, err := s.DB.Exec(
		`INSERT INTO transactions (account_id, category_id, user_id, type, amount, currency, date)
		 VALUES (?, ?, ?, 'expense', 5, 'EUR', '2025-04-05')`,
		acctID, catID, 1,
	); err != nil {
		t.Fatalf("seed insert: %v", err)
	}

	var nullCount int
	if err := s.DB.QueryRow(
		`SELECT COUNT(*) FROM transactions WHERE created_by_user_id IS NULL`,
	).Scan(&nullCount); err != nil {
		t.Fatalf("count nulls: %v", err)
	}
	if nullCount == 0 {
		t.Fatalf("seed row should have NULL created_by_user_id")
	}

	// Run the same backfill the production migration runs.
	if _, err := s.DB.Exec(
		`UPDATE transactions SET created_by_user_id = user_id WHERE created_by_user_id IS NULL`,
	); err != nil {
		t.Fatalf("backfill: %v", err)
	}

	if err := s.DB.QueryRow(
		`SELECT COUNT(*) FROM transactions WHERE created_by_user_id IS NULL`,
	).Scan(&nullCount); err != nil {
		t.Fatalf("count nulls (post): %v", err)
	}
	if nullCount != 0 {
		t.Errorf("post-backfill nulls = %d, want 0", nullCount)
	}
}
