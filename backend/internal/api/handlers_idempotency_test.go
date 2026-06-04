package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
)

func mustCreateAccount(t *testing.T, handler http.Handler, token, body string) int64 {
	t.Helper()
	req := authedRequest("POST", "/api/accounts", body, token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("create account: got %d: %s", w.Code, w.Body.String())
	}
	var out map[string]any
	json.Unmarshal(w.Body.Bytes(), &out)
	return int64(out["id"].(float64))
}

func mustCreateCategory(t *testing.T, handler http.Handler, token, body string) int64 {
	t.Helper()
	req := authedRequest("POST", "/api/categories", body, token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("create category: got %d: %s", w.Code, w.Body.String())
	}
	var out map[string]any
	json.Unmarshal(w.Body.Bytes(), &out)
	return int64(out["id"].(float64))
}

// Same (created_by_user_id, client_id) on a second POST returns the original
// row with HTTP 200 instead of inserting a duplicate.
func TestCreateTransaction_IdempotentClientID(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	acctID := mustCreateAccount(t, handler, token, `{"name":"Wallet"}`)
	catID := mustCreateCategory(t, handler, token, `{"name":"Food","type":"expense"}`)

	body := fmt.Sprintf(
		`{"account_id":%d,"category_id":%d,"type":"expense","amount":12.50,"date":"2026-01-15","client_id":"uuid-abc"}`,
		acctID, catID,
	)

	req := authedRequest("POST", "/api/transactions", body, token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("first POST: got %d: %s", w.Code, w.Body.String())
	}
	var first map[string]any
	json.Unmarshal(w.Body.Bytes(), &first)
	firstID := int64(first["id"].(float64))

	req = authedRequest("POST", "/api/transactions", body, token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("second POST: got %d, want 200: %s", w.Code, w.Body.String())
	}
	var second map[string]any
	json.Unmarshal(w.Body.Bytes(), &second)
	if int64(second["id"].(float64)) != firstID {
		t.Errorf("second POST returned id %v, want %d", second["id"], firstID)
	}

	var count int
	s.DB.QueryRow("SELECT COUNT(*) FROM transactions WHERE client_id = 'uuid-abc'").Scan(&count)
	if count != 1 {
		t.Errorf("expected 1 transaction row, got %d", count)
	}
}

// POSTs without a client_id keep current (duplicate-creating) behavior so old
// clients are unaffected.
func TestCreateTransaction_NoClientIDStillDuplicates(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	acctID := mustCreateAccount(t, handler, token, `{"name":"Wallet"}`)
	catID := mustCreateCategory(t, handler, token, `{"name":"Food","type":"expense"}`)
	body := fmt.Sprintf(
		`{"account_id":%d,"category_id":%d,"type":"expense","amount":7,"date":"2026-01-15"}`,
		acctID, catID,
	)

	for i := 0; i < 2; i++ {
		req := authedRequest("POST", "/api/transactions", body, token)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, req)
		if w.Code != 201 {
			t.Fatalf("POST #%d: got %d", i, w.Code)
		}
	}

	var count int
	s.DB.QueryRow("SELECT COUNT(*) FROM transactions WHERE client_id IS NULL").Scan(&count)
	if count != 2 {
		t.Errorf("expected 2 rows (no dedup without client_id), got %d", count)
	}
}

// Two different authenticated users using the SAME client_id must not collide
// on each other — idempotency is per-creator, not global.
func TestCreateTransaction_IdempotencyScopedByCreator(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	adminToken, guestToken, _ := setupTwoUsers(t, handler)

	adminAcct := mustCreateAccount(t, handler, adminToken, `{"name":"AdminWallet"}`)
	guestAcct := mustCreateAccount(t, handler, guestToken, `{"name":"GuestWallet"}`)
	adminCat := mustCreateCategory(t, handler, adminToken, `{"name":"Food","type":"expense"}`)
	guestCat := mustCreateCategory(t, handler, guestToken, `{"name":"Food","type":"expense"}`)

	adminBody := fmt.Sprintf(
		`{"account_id":%d,"category_id":%d,"type":"expense","amount":5,"date":"2026-01-15","client_id":"shared-uuid"}`,
		adminAcct, adminCat,
	)
	guestBody := fmt.Sprintf(
		`{"account_id":%d,"category_id":%d,"type":"expense","amount":9,"date":"2026-01-15","client_id":"shared-uuid"}`,
		guestAcct, guestCat,
	)

	for _, tt := range []struct {
		name, body, token string
	}{
		{"admin", adminBody, adminToken},
		{"guest", guestBody, guestToken},
	} {
		req := authedRequest("POST", "/api/transactions", tt.body, tt.token)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, req)
		if w.Code != 201 {
			t.Fatalf("%s POST: got %d: %s", tt.name, w.Code, w.Body.String())
		}
	}

	var count int
	s.DB.QueryRow("SELECT COUNT(*) FROM transactions WHERE client_id = 'shared-uuid'").Scan(&count)
	if count != 2 {
		t.Errorf("expected 2 distinct rows (one per creator), got %d", count)
	}
}

func TestCreateAccount_IdempotentClientID(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	body := `{"name":"Savings","currency":"EUR","client_id":"acct-uuid"}`
	id1 := mustCreateAccount(t, handler, token, body)

	req := authedRequest("POST", "/api/accounts", body, token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("second POST: got %d, want 200: %s", w.Code, w.Body.String())
	}
	var second map[string]any
	json.Unmarshal(w.Body.Bytes(), &second)
	if int64(second["id"].(float64)) != id1 {
		t.Errorf("returned id %v, want %d", second["id"], id1)
	}

	var count int
	s.DB.QueryRow("SELECT COUNT(*) FROM accounts WHERE client_id = 'acct-uuid'").Scan(&count)
	if count != 1 {
		t.Errorf("expected 1 account, got %d", count)
	}
}

func TestCreateCategory_IdempotentClientID(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	body := `{"name":"Food","type":"expense","client_id":"cat-uuid"}`
	id1 := mustCreateCategory(t, handler, token, body)

	req := authedRequest("POST", "/api/categories", body, token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("second POST: got %d, want 200: %s", w.Code, w.Body.String())
	}
	var second map[string]any
	json.Unmarshal(w.Body.Bytes(), &second)
	if int64(second["id"].(float64)) != id1 {
		t.Errorf("returned id %v, want %d", second["id"], id1)
	}
}

func TestCreateScheduled_IdempotentClientID(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	acctID := mustCreateAccount(t, handler, token, `{"name":"Wallet"}`)
	catID := mustCreateCategory(t, handler, token, `{"name":"Rent","type":"expense"}`)

	body := fmt.Sprintf(
		`{"account_id":%d,"category_id":%d,"type":"expense","amount":1000,"rrule":"FREQ=MONTHLY","next_occurrence":"2026-02-01","client_id":"sched-uuid"}`,
		acctID, catID,
	)

	req := authedRequest("POST", "/api/scheduled", body, token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("first POST: got %d: %s", w.Code, w.Body.String())
	}
	var first map[string]any
	json.Unmarshal(w.Body.Bytes(), &first)
	firstID := int64(first["id"].(float64))

	req = authedRequest("POST", "/api/scheduled", body, token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("second POST: got %d, want 200: %s", w.Code, w.Body.String())
	}
	var second map[string]any
	json.Unmarshal(w.Body.Bytes(), &second)
	if int64(second["id"].(float64)) != firstID {
		t.Errorf("returned id %v, want %d", second["id"], firstID)
	}
}

// A future-dated transaction POST creates a scheduled_transactions row but
// honors the same idempotency key. A second POST returns that scheduled row.
func TestCreateTransaction_FutureDatedIdempotent(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	acctID := mustCreateAccount(t, handler, token, `{"name":"Wallet"}`)
	catID := mustCreateCategory(t, handler, token, `{"name":"Food","type":"expense"}`)

	body := fmt.Sprintf(
		`{"account_id":%d,"category_id":%d,"type":"expense","amount":50,"date":"2099-12-31","client_id":"future-uuid"}`,
		acctID, catID,
	)

	req := authedRequest("POST", "/api/transactions", body, token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 201 {
		t.Fatalf("first POST: got %d: %s", w.Code, w.Body.String())
	}
	var first map[string]any
	json.Unmarshal(w.Body.Bytes(), &first)
	firstID := int64(first["id"].(float64))
	if first["scheduled"] != true {
		t.Errorf("first POST should have scheduled=true, got %v", first["scheduled"])
	}

	req = authedRequest("POST", "/api/transactions", body, token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("second POST: got %d, want 200: %s", w.Code, w.Body.String())
	}
	var second map[string]any
	json.Unmarshal(w.Body.Bytes(), &second)
	if int64(second["id"].(float64)) != firstID {
		t.Errorf("second POST returned id %v, want %d", second["id"], firstID)
	}
	if second["scheduled"] != true {
		t.Errorf("second POST should have scheduled=true, got %v", second["scheduled"])
	}
}

