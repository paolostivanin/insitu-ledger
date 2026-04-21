package api

import (
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"testing"
)

// Per-account share: guest can see only the shared account, not siblings.
func TestPerAccountIsolation(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken, guestToken, _ := setupTwoUsers(t, handler)

	// Admin creates two accounts: Wallet and Savings, plus a category.
	req := authedRequest("POST", "/api/accounts", `{"name":"Wallet"}`, adminToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var wallet map[string]any
	json.Unmarshal(w.Body.Bytes(), &wallet)
	walletID := int(wallet["id"].(float64))

	req = authedRequest("POST", "/api/accounts", `{"name":"Savings"}`, adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var savings map[string]any
	json.Unmarshal(w.Body.Bytes(), &savings)
	savingsID := int(savings["id"].(float64))

	req = authedRequest("POST", "/api/categories", `{"name":"Food","type":"expense"}`, adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var cat map[string]any
	json.Unmarshal(w.Body.Bytes(), &cat)
	catID := int(cat["id"].(float64))

	// Transactions in both accounts.
	for _, aID := range []int{walletID, savingsID} {
		body := fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"expense","amount":10,"date":"2025-01-01"}`, aID, catID)
		req := authedRequest("POST", "/api/transactions", body, adminToken)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, req)
		if w.Code != 201 {
			t.Fatalf("seed transaction: got %d: %s", w.Code, w.Body.String())
		}
	}

	// Share ONLY Wallet with guest (read).
	body := fmt.Sprintf(`{"guest_email":"guest@test.com","account_id":%d,"permission":"read"}`, walletID)
	req = authedRequest("POST", "/api/shared", body, adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("share Wallet: got %d: %s", w.Code, w.Body.String())
	}

	// Guest sees only Wallet on /api/accounts?owner_id=1.
	req = authedRequest("GET", "/api/accounts?owner_id=1", "", guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var visible []map[string]any
	json.Unmarshal(w.Body.Bytes(), &visible)
	if len(visible) != 1 || int(visible[0]["id"].(float64)) != walletID {
		t.Fatalf("guest should see only Wallet, got %v", visible)
	}

	// Guest sees only the Wallet transaction.
	req = authedRequest("GET", "/api/transactions?owner_id=1", "", guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var txns []map[string]any
	json.Unmarshal(w.Body.Bytes(), &txns)
	if len(txns) != 1 {
		t.Fatalf("expected 1 visible transaction, got %d", len(txns))
	}
	if int(txns[0]["account_id"].(float64)) != walletID {
		t.Errorf("visible transaction account_id = %v, want %d", txns[0]["account_id"], walletID)
	}

	// Filtering by account_id = Savings (not shared) returns empty, not 403.
	req = authedRequest("GET", fmt.Sprintf("/api/transactions?owner_id=1&account_id=%d", savingsID), "", guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("filter by inaccessible account: got %d", w.Code)
	}
	json.Unmarshal(w.Body.Bytes(), &txns)
	if len(txns) != 0 {
		t.Errorf("expected 0 transactions for inaccessible account, got %d", len(txns))
	}
}

// Write share lets the guest POST/PUT/DELETE only on the shared account.
func TestPerAccountWriteScoping(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken, guestToken, _ := setupTwoUsers(t, handler)

	req := authedRequest("POST", "/api/accounts", `{"name":"Wallet"}`, adminToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var wallet map[string]any
	json.Unmarshal(w.Body.Bytes(), &wallet)
	walletID := int(wallet["id"].(float64))

	req = authedRequest("POST", "/api/accounts", `{"name":"Savings"}`, adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var savings map[string]any
	json.Unmarshal(w.Body.Bytes(), &savings)
	savingsID := int(savings["id"].(float64))

	req = authedRequest("POST", "/api/categories", `{"name":"Food","type":"expense"}`, adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var cat map[string]any
	json.Unmarshal(w.Body.Bytes(), &cat)
	catID := int(cat["id"].(float64))

	// Admin creates a transaction on Savings (the account NOT shared).
	body := fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"expense","amount":99,"date":"2025-01-01"}`, savingsID, catID)
	req = authedRequest("POST", "/api/transactions", body, adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var savingsTxn map[string]any
	json.Unmarshal(w.Body.Bytes(), &savingsTxn)
	savingsTxnID := int(savingsTxn["id"].(float64))

	// Share ONLY Wallet with write permission.
	body = fmt.Sprintf(`{"guest_email":"guest@test.com","account_id":%d,"permission":"write"}`, walletID)
	req = authedRequest("POST", "/api/shared", body, adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("share: got %d: %s", w.Code, w.Body.String())
	}

	// Guest can create on Wallet.
	body = fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"expense","amount":5,"date":"2025-01-02"}`, walletID, catID)
	req = authedRequest("POST", "/api/transactions?owner_id=1", body, guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Errorf("guest create on Wallet: got %d: %s", w.Code, w.Body.String())
	}

	// Guest CANNOT create on Savings.
	body = fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"expense","amount":5,"date":"2025-01-02"}`, savingsID, catID)
	req = authedRequest("POST", "/api/transactions?owner_id=1", body, guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 403 {
		t.Errorf("guest create on Savings: got %d, want 403", w.Code)
	}

	// Guest CANNOT delete a Savings transaction.
	req = authedRequest("DELETE", fmt.Sprintf("/api/transactions/%d", savingsTxnID), "", guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 403 {
		t.Errorf("guest delete Savings txn: got %d, want 403", w.Code)
	}
}

// Sharing with self or sharing the same (guest, account) twice should fail.
func TestSharedAccessValidation(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken, _, _ := setupTwoUsers(t, handler)

	req := authedRequest("POST", "/api/accounts", `{"name":"A"}`, adminToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var a map[string]any
	json.Unmarshal(w.Body.Bytes(), &a)
	aID := int(a["id"].(float64))

	// Sharing with yourself: 400.
	body := fmt.Sprintf(`{"guest_email":"admin@test.com","account_id":%d,"permission":"read"}`, aID)
	req = authedRequest("POST", "/api/shared", body, adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 400 {
		t.Errorf("share with self: got %d, want 400", w.Code)
	}

	// Sharing an unknown account: 404.
	body = fmt.Sprintf(`{"guest_email":"guest@test.com","account_id":99999,"permission":"read"}`)
	req = authedRequest("POST", "/api/shared", body, adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 404 {
		t.Errorf("share unknown account: got %d, want 404", w.Code)
	}

	// Sharing an unknown email: 404.
	body = fmt.Sprintf(`{"guest_email":"nobody@test.com","account_id":%d,"permission":"read"}`, aID)
	req = authedRequest("POST", "/api/shared", body, adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 404 {
		t.Errorf("share unknown email: got %d, want 404", w.Code)
	}

	// Bad permission value: 400.
	body = fmt.Sprintf(`{"guest_email":"guest@test.com","account_id":%d,"permission":"admin"}`, aID)
	req = authedRequest("POST", "/api/shared", body, adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 400 {
		t.Errorf("bad permission: got %d, want 400", w.Code)
	}

	// First valid share: 201.
	body = fmt.Sprintf(`{"guest_email":"guest@test.com","account_id":%d,"permission":"read"}`, aID)
	req = authedRequest("POST", "/api/shared", body, adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("first share: got %d: %s", w.Code, w.Body.String())
	}

	// Duplicate (guest, account): 409.
	req = authedRequest("POST", "/api/shared", body, adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 409 {
		t.Errorf("duplicate share: got %d, want 409", w.Code)
	}
}

// Categories are never directly shareable; guests get them read-only and must
// not be able to mutate them even with a write share on an account.
func TestCategoriesReadOnlyForGuest(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken, guestToken, _ := setupTwoUsers(t, handler)

	acctID, catID := createTestAccountAndCategory(t, handler, adminToken)

	body := fmt.Sprintf(`{"guest_email":"guest@test.com","account_id":%d,"permission":"write"}`, acctID)
	req := authedRequest("POST", "/api/shared", body, adminToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("share: got %d: %s", w.Code, w.Body.String())
	}

	// Guest GETs admin's categories with read_only=true.
	req = authedRequest("GET", "/api/categories?owner_id=1", "", guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("guest list categories: got %d", w.Code)
	}
	var cats []map[string]any
	json.Unmarshal(w.Body.Bytes(), &cats)
	if len(cats) == 0 {
		t.Fatalf("expected at least 1 category visible")
	}
	if cats[0]["read_only"] != true {
		t.Errorf("expected read_only=true, got %v", cats[0]["read_only"])
	}

	// Guest cannot create a category in admin's space (even with write share).
	req = authedRequest("POST", "/api/categories?owner_id=1", `{"name":"Hacked","type":"expense"}`, guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 403 {
		t.Errorf("guest create category: got %d, want 403", w.Code)
	}

	// Guest cannot update admin's categories.
	req = authedRequest("PUT", fmt.Sprintf("/api/categories/%d?owner_id=1", catID), `{"name":"x","type":"expense"}`, guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 403 {
		t.Errorf("guest update category: got %d, want 403", w.Code)
	}

	// Guest cannot delete admin's categories.
	req = authedRequest("DELETE", fmt.Sprintf("/api/categories/%d?owner_id=1", catID), "", guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 403 {
		t.Errorf("guest delete category: got %d, want 403", w.Code)
	}

	// Own categories list still has read_only=false.
	req = authedRequest("GET", "/api/categories", "", adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	json.Unmarshal(w.Body.Bytes(), &cats)
	for _, c := range cats {
		if c["read_only"] != false {
			t.Errorf("own categories should have read_only=false, got %v for %v", c["read_only"], c["name"])
		}
	}
}

// Profile preferences: get returns null when unset; PUT validates accessibility;
// stale references silently return null.
func TestProfilePreferences(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken, guestToken, _ := setupTwoUsers(t, handler)

	// Initially unset.
	req := authedRequest("GET", "/api/profile/preferences", "", guestToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("get prefs: got %d", w.Code)
	}
	var prefs map[string]any
	json.Unmarshal(w.Body.Bytes(), &prefs)
	if prefs["default_account_id"] != nil {
		t.Errorf("expected null default_account_id, got %v", prefs["default_account_id"])
	}

	// Admin creates an account; guest tries to set it as default → 400 (not accessible).
	req = authedRequest("POST", "/api/accounts", `{"name":"Wallet"}`, adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var wallet map[string]any
	json.Unmarshal(w.Body.Bytes(), &wallet)
	walletID := int(wallet["id"].(float64))

	body := fmt.Sprintf(`{"default_account_id":%d}`, walletID)
	req = authedRequest("PUT", "/api/profile/preferences", body, guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 400 {
		t.Errorf("set inaccessible default: got %d, want 400", w.Code)
	}

	// Admin shares Wallet with guest.
	shareBody := fmt.Sprintf(`{"guest_email":"guest@test.com","account_id":%d,"permission":"read"}`, walletID)
	req = authedRequest("POST", "/api/shared", shareBody, adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("share: got %d: %s", w.Code, w.Body.String())
	}

	// Now guest can set Wallet as default.
	req = authedRequest("PUT", "/api/profile/preferences", body, guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 204 {
		t.Errorf("set accessible default: got %d: %s", w.Code, w.Body.String())
	}

	req = authedRequest("GET", "/api/profile/preferences", "", guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	json.Unmarshal(w.Body.Bytes(), &prefs)
	if int(prefs["default_account_id"].(float64)) != walletID {
		t.Errorf("default = %v, want %d", prefs["default_account_id"], walletID)
	}

	// Admin revokes the share. List shares to find the id, then DELETE.
	req = authedRequest("GET", "/api/shared", "", adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var shares []map[string]any
	json.Unmarshal(w.Body.Bytes(), &shares)
	if len(shares) != 1 {
		t.Fatalf("expected 1 share, got %d", len(shares))
	}
	shareID := int(shares[0]["id"].(float64))
	req = authedRequest("DELETE", fmt.Sprintf("/api/shared/%d", shareID), "", adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 204 {
		t.Fatalf("revoke share: got %d", w.Code)
	}

	// Guest's GET returns null silently — not an error.
	req = authedRequest("GET", "/api/profile/preferences", "", guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	json.Unmarshal(w.Body.Bytes(), &prefs)
	if prefs["default_account_id"] != nil {
		t.Errorf("after revoke, expected null, got %v", prefs["default_account_id"])
	}

	// Clearing via null is allowed.
	req = authedRequest("PUT", "/api/profile/preferences", `{"default_account_id":null}`, guestToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 204 {
		t.Errorf("clear default: got %d", w.Code)
	}
}

// Owner can set default to their own account.
func TestProfilePreferencesOwnAccount(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken := loginAdmin(t, handler)

	req := authedRequest("POST", "/api/accounts", `{"name":"Main"}`, adminToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var a map[string]any
	json.Unmarshal(w.Body.Bytes(), &a)
	aID := int(a["id"].(float64))

	body := fmt.Sprintf(`{"default_account_id":%d}`, aID)
	req = authedRequest("PUT", "/api/profile/preferences", body, adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 204 {
		t.Fatalf("set own default: got %d", w.Code)
	}

	req = authedRequest("GET", "/api/profile/preferences", "", adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	var prefs map[string]any
	json.Unmarshal(w.Body.Bytes(), &prefs)
	if int(prefs["default_account_id"].(float64)) != aID {
		t.Errorf("default = %v, want %d", prefs["default_account_id"], aID)
	}
}
