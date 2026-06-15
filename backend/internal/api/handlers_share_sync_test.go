package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// Tier B B1: grant after the guest has already synced to version N must
// appear in the next incremental sync (account + transactions + scheduled),
// even though their own sync_version predates `since`.
func TestSharedAccessSyncGrantSurfacesFullSnapshot(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken, guestToken, _ := setupTwoUsers(t, handler)

	walletID := mustCreateAccount(t, handler, adminToken, `{"name":"Wallet"}`)
	catID := mustCreateCategory(t, handler, adminToken, `{"name":"Food","type":"expense"}`)
	body := fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"expense","amount":10,"date":"2025-01-01"}`, walletID, catID)
	req := authedRequest("POST", "/api/transactions", body, adminToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("seed transaction: got %d: %s", w.Code, w.Body.String())
	}

	// Guest's first sync — sees nothing yet.
	syncBefore := doSync(t, handler, guestToken, 0)
	if accs, _ := syncBefore["accounts"].([]any); len(accs) != 0 {
		t.Fatalf("guest pre-share should see 0 accounts, got %d", len(accs))
	}
	guestVersion := int64(syncBefore["current_version"].(float64))

	// Admin shares Wallet with guest.
	mustShare(t, handler, adminToken, "guest@test.com", walletID)

	// Guest's incremental sync from their previous version must now include
	// the wallet AND its previously-existing transaction.
	syncAfter := doSync(t, handler, guestToken, guestVersion)
	accs := syncAfter["accounts"].([]any)
	if len(accs) != 1 || int64(accs[0].(map[string]any)["id"].(float64)) != walletID {
		t.Fatalf("expected freshly accessible Wallet in sync, got %v", accs)
	}
	txns := syncAfter["transactions"].([]any)
	if len(txns) != 1 || int64(txns[0].(map[string]any)["account_id"].(float64)) != walletID {
		t.Fatalf("expected transaction ride-along for freshly accessible account, got %v", txns)
	}
}

// Tier B B1: revoke produces a revoked_account_ids entry that the client
// uses to purge its local cache.
func TestSharedAccessSyncRevokeProducesTombstone(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken, guestToken, _ := setupTwoUsers(t, handler)

	walletID := mustCreateAccount(t, handler, adminToken, `{"name":"Wallet"}`)
	shareID := mustShare(t, handler, adminToken, "guest@test.com", walletID)

	// Guest syncs to current version — picks up the wallet.
	initial := doSync(t, handler, guestToken, 0)
	guestVersion := int64(initial["current_version"].(float64))

	// Admin revokes.
	req := authedRequest("DELETE", fmt.Sprintf("/api/shared/%d", shareID), "", adminToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 204 {
		t.Fatalf("revoke share: got %d: %s", w.Code, w.Body.String())
	}

	// Next guest sync must surface the revocation as a tombstone.
	syncAfter := doSync(t, handler, guestToken, guestVersion)
	rev, _ := syncAfter["revoked_account_ids"].([]any)
	if len(rev) != 1 || int64(rev[0].(float64)) != walletID {
		t.Fatalf("expected revoked_account_ids=[%d], got %v", walletID, rev)
	}
}

// Tier B B1: re-grant after revoke reuses the same row (UNIQUE forbids
// two parallel ones) but the second incremental sync must again return the
// snapshot — not the stale tombstone.
func TestSharedAccessReGrantAfterRevokeProducesFreshSnapshot(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken, guestToken, _ := setupTwoUsers(t, handler)

	walletID := mustCreateAccount(t, handler, adminToken, `{"name":"Wallet"}`)
	shareID := mustShare(t, handler, adminToken, "guest@test.com", walletID)

	v0 := doSync(t, handler, guestToken, 0)
	versionAfterGrant := int64(v0["current_version"].(float64))

	// Revoke.
	req := authedRequest("DELETE", fmt.Sprintf("/api/shared/%d", shareID), "", adminToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 204 {
		t.Fatalf("revoke: got %d", w.Code)
	}

	v1 := doSync(t, handler, guestToken, versionAfterGrant)
	if rev := v1["revoked_account_ids"].([]any); len(rev) != 1 {
		t.Fatalf("expected revoke tombstone, got %v", rev)
	}
	versionAfterRevoke := int64(v1["current_version"].(float64))

	// Re-grant.
	mustShare(t, handler, adminToken, "guest@test.com", walletID)

	v2 := doSync(t, handler, guestToken, versionAfterRevoke)
	accs := v2["accounts"].([]any)
	if len(accs) != 1 || int64(accs[0].(map[string]any)["id"].(float64)) != walletID {
		t.Fatalf("re-granted wallet should be returned, got %v", accs)
	}
	if rev := v2["revoked_account_ids"].([]any); len(rev) != 0 {
		t.Fatalf("re-grant must not produce a revoke tombstone, got %v", rev)
	}
}

// Tier B B1: a live share that has NOT changed since the guest's last sync
// must not be sent (no double-delivery loops).
func TestSharedAccessSyncIdempotent(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken, guestToken, _ := setupTwoUsers(t, handler)

	walletID := mustCreateAccount(t, handler, adminToken, `{"name":"Wallet"}`)
	mustShare(t, handler, adminToken, "guest@test.com", walletID)

	first := doSync(t, handler, guestToken, 0)
	v := int64(first["current_version"].(float64))

	second := doSync(t, handler, guestToken, v)
	if accs := second["accounts"].([]any); len(accs) != 0 {
		t.Fatalf("second sync without changes should return no accounts, got %v", accs)
	}
	if rev := second["revoked_account_ids"].([]any); len(rev) != 0 {
		t.Fatalf("second sync without changes should return no revocations, got %v", rev)
	}
}

// v1.20 (post-Tier-B F3): when an owner soft-deletes a shared account, the
// share row must also be tombstoned so the guest's next incremental sync
// surfaces the revocation via revoked_account_ids. The account itself is no
// longer in the guest's sync scope (the share is gone), so the cleanup path
// is the revoked_account_ids ride-along, not the account-tombstone stream.
func TestAccountDeleteCascadesShareRevocationForGuest(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken, guestToken, _ := setupTwoUsers(t, handler)

	walletID := mustCreateAccount(t, handler, adminToken, `{"name":"Wallet"}`)
	mustShare(t, handler, adminToken, "guest@test.com", walletID)

	// Guest catches up — sees the wallet.
	initial := doSync(t, handler, guestToken, 0)
	if accs := initial["accounts"].([]any); len(accs) != 1 {
		t.Fatalf("guest should see 1 account, got %d", len(accs))
	}
	v := int64(initial["current_version"].(float64))

	// Owner deletes the account.
	req := authedRequest("DELETE", fmt.Sprintf("/api/accounts/%d", walletID), "", adminToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 204 {
		t.Fatalf("delete account: got %d: %s", w.Code, w.Body.String())
	}

	// Guest's next sync surfaces the revocation as revoked_account_ids.
	syncAfter := doSync(t, handler, guestToken, v)
	rev := syncAfter["revoked_account_ids"].([]any)
	if len(rev) != 1 || int64(rev[0].(float64)) != walletID {
		t.Fatalf("expected revoked_account_ids=[%d], got %v", walletID, rev)
	}
}

// v1.20 (post-Tier-B F3): for the OWNER's own incremental sync, deleting an
// account must produce tombstones for the account itself AND its dependent
// transactions / scheduled. Without the cascade, transactions and schedules
// remained un-tombstoned locally on the owner's other devices.
func TestAccountDeleteCascadesTransactionTombstonesForOwner(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken := loginAdmin(t, handler)

	walletID := mustCreateAccount(t, handler, adminToken, `{"name":"Wallet"}`)
	catID := mustCreateCategory(t, handler, adminToken, `{"name":"Food","type":"expense"}`)
	txBody := fmt.Sprintf(
		`{"account_id":%d,"category_id":%d,"type":"expense","amount":10,"date":"2025-01-01"}`,
		walletID, catID,
	)
	req := authedRequest("POST", "/api/transactions", txBody, adminToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("seed transaction: got %d: %s", w.Code, w.Body.String())
	}
	schedBody := fmt.Sprintf(
		`{"account_id":%d,"category_id":%d,"type":"expense","amount":50,"rrule":"FREQ=MONTHLY","next_occurrence":"2026-07-01"}`,
		walletID, catID,
	)
	req = authedRequest("POST", "/api/scheduled", schedBody, adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("seed scheduled: got %d: %s", w.Code, w.Body.String())
	}

	// Owner catches up.
	initial := doSync(t, handler, adminToken, 0)
	v := int64(initial["current_version"].(float64))

	// Owner deletes the account.
	req = authedRequest("DELETE", fmt.Sprintf("/api/accounts/%d", walletID), "", adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 204 {
		t.Fatalf("delete account: got %d: %s", w.Code, w.Body.String())
	}

	// Owner's incremental sync from previous version must include the
	// account, transaction, and scheduled rows all with deleted_at set.
	syncAfter := doSync(t, handler, adminToken, v)

	accs := syncAfter["accounts"].([]any)
	if len(accs) != 1 || accs[0].(map[string]any)["deleted_at"] == nil {
		t.Fatalf("expected account tombstone, got %v", accs)
	}
	txns := syncAfter["transactions"].([]any)
	if len(txns) != 1 || txns[0].(map[string]any)["deleted_at"] == nil {
		t.Fatalf("expected transaction tombstone, got %v", txns)
	}
	scheds := syncAfter["scheduled_transactions"].([]any)
	if len(scheds) != 1 || scheds[0].(map[string]any)["deleted_at"] == nil {
		t.Fatalf("expected scheduled tombstone, got %v", scheds)
	}
}

// Tier B B4: category delete must be rejected when an undeleted scheduled
// transaction references it.
func TestCategoryDeleteBlockedByScheduledTransaction(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken := loginAdmin(t, handler)

	walletID := mustCreateAccount(t, handler, adminToken, `{"name":"Wallet"}`)
	catID := mustCreateCategory(t, handler, adminToken, `{"name":"Rent","type":"expense"}`)

	body := fmt.Sprintf(
		`{"account_id":%d,"category_id":%d,"type":"expense","amount":1000,"rrule":"FREQ=MONTHLY","next_occurrence":"2026-07-01"}`,
		walletID, catID,
	)
	req := authedRequest("POST", "/api/scheduled", body, adminToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("create scheduled: got %d: %s", w.Code, w.Body.String())
	}

	req = authedRequest("DELETE", fmt.Sprintf("/api/categories/%d", catID), "", adminToken)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 409 {
		t.Fatalf("expected 409, got %d: %s", w.Code, w.Body.String())
	}
	if !strings.Contains(w.Body.String(), "scheduled") {
		t.Errorf("error body should mention scheduled, got: %s", w.Body.String())
	}
}

func TestCategoryDeleteBlockedByInactiveUndeletedSchedule(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken := loginAdmin(t, handler)

	walletID := mustCreateAccount(t, handler, adminToken, `{"name":"Wallet"}`)
	catID := mustCreateCategory(t, handler, adminToken, `{"name":"Rent","type":"expense"}`)
	if _, err := s.DB.Exec(
		`INSERT INTO scheduled_transactions
		 (account_id, category_id, user_id, type, amount, currency, rrule, next_occurrence, active)
		 VALUES (?, ?, 1, 'expense', 1000, 'EUR', 'FREQ=MONTHLY', '2026-07-01', 0)`,
		walletID, catID,
	); err != nil {
		t.Fatalf("seed inactive schedule: %v", err)
	}

	req := authedRequest("DELETE", fmt.Sprintf("/api/categories/%d", catID), "", adminToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != http.StatusConflict {
		t.Fatalf("inactive undeleted schedule must block deletion: got %d: %s", w.Code, w.Body.String())
	}
}

func TestCategoryDeleteIgnoresSoftDeletedSchedule(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken := loginAdmin(t, handler)

	walletID := mustCreateAccount(t, handler, adminToken, `{"name":"Wallet"}`)
	catID := mustCreateCategory(t, handler, adminToken, `{"name":"Rent","type":"expense"}`)
	if _, err := s.DB.Exec(
		`INSERT INTO scheduled_transactions
		 (account_id, category_id, user_id, type, amount, currency, rrule, next_occurrence, deleted_at)
		 VALUES (?, ?, 1, 'expense', 1000, 'EUR', 'FREQ=MONTHLY', '2026-07-01', datetime('now'))`,
		walletID, catID,
	); err != nil {
		t.Fatalf("seed deleted schedule: %v", err)
	}

	req := authedRequest("DELETE", fmt.Sprintf("/api/categories/%d", catID), "", adminToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != http.StatusNoContent {
		t.Fatalf("soft-deleted schedule must not block deletion: got %d: %s", w.Code, w.Body.String())
	}
}

// Tier B B4: another user's category id must return 404, not leak existence
// via the dependency-check timing.
func TestCategoryDeleteOtherUserReturns404(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken, guestToken, _ := setupTwoUsers(t, handler)

	adminCatID := mustCreateCategory(t, handler, adminToken, `{"name":"Food","type":"expense"}`)

	req := authedRequest("DELETE", fmt.Sprintf("/api/categories/%d", adminCatID), "", guestToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 404 {
		t.Fatalf("expected 404 for other-user category, got %d: %s", w.Code, w.Body.String())
	}
}

// ---- helpers ----

func mustShare(t *testing.T, handler http.Handler, token, guestEmail string, accountID int64) int64 {
	t.Helper()
	body := fmt.Sprintf(`{"guest_email":%q,"account_id":%d}`, guestEmail, accountID)
	req := authedRequest("POST", "/api/shared", body, token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("share account %d: got %d: %s", accountID, w.Code, w.Body.String())
	}
	var created map[string]any
	json.Unmarshal(w.Body.Bytes(), &created)
	return int64(created["id"].(float64))
}

func doSync(t *testing.T, handler http.Handler, token string, since int64) map[string]any {
	t.Helper()
	req := authedRequest("GET", fmt.Sprintf("/api/sync?since=%d", since), "", token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("sync since=%d: got %d: %s", since, w.Code, w.Body.String())
	}
	var resp map[string]any
	json.Unmarshal(w.Body.Bytes(), &resp)
	return resp
}
