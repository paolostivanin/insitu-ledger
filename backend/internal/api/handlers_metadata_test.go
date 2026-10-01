package api

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	ledgerdb "github.com/pstivanin/insitu-ledger/backend/internal/db"
)

func TestSharedAccountMetadataReachesOwnerIncrementally(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	if err := ledgerdb.InstallMetadataSyncTriggers(s.DB); err != nil {
		t.Fatal(err)
	}
	h := NewRouter(s)
	owner, _, _ := setupTwoUsers(t, h)
	account := mustCreateAccount(t, h, owner, `{"name":"Wallet"}`)
	before := doSync(t, h, owner, 0)
	since := int64(before["current_version"].(float64))
	share := mustShare(t, h, owner, "guest@test.com", int64(account))
	after := doSync(t, h, owner, since)
	rows := after["accounts"].([]any)
	if len(rows) != 1 || rows[0].(map[string]any)["is_shared"] != true {
		t.Fatalf("owner did not receive shared status: %v", rows)
	}
	since = int64(after["current_version"].(float64))
	w := httptest.NewRecorder()
	h.ServeHTTP(w, authedRequest("DELETE", fmt.Sprintf("/api/shared/%d", share), "", owner))
	if w.Code != http.StatusNoContent {
		t.Fatalf("revoke: %d %s", w.Code, w.Body.String())
	}
	rows = doSync(t, h, owner, since)["accounts"].([]any)
	if len(rows) != 1 || rows[0].(map[string]any)["is_shared"] != false {
		t.Fatalf("owner did not receive unshared status: %v", rows)
	}
}

func TestNameChangeResendsJoinedMetadata(t *testing.T) {
	for _, adminEdit := range []bool{false, true} {
		t.Run(fmt.Sprintf("admin=%v", adminEdit), func(t *testing.T) {
			s, cleanup := setupTestServer(t)
			defer cleanup()
			if err := ledgerdb.InstallMetadataSyncTriggers(s.DB); err != nil {
				t.Fatal(err)
			}
			h := NewRouter(s)
			owner, guest, guestID := setupTwoUsers(t, h)
			account, category := createTestAccountAndCategory(t, h, owner)
			mustShare(t, h, owner, "guest@test.com", int64(account))
			mustCreateAccount(t, h, guest, `{"name":"Personal"}`)
			w := httptest.NewRecorder()
			h.ServeHTTP(w, authedRequest("POST", "/api/transactions", fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"expense","amount":7,"date":"2025-04-03"}`, account, category), guest))
			if w.Code != 201 {
				t.Fatalf("transaction: %d %s", w.Code, w.Body.String())
			}
			w = httptest.NewRecorder()
			h.ServeHTTP(w, authedRequest("POST", "/api/scheduled", fmt.Sprintf(`{"account_id":%d,"category_id":%d,"type":"expense","amount":9,"rrule":"FREQ=MONTHLY","next_occurrence":"2030-01-01"}`, account, category), guest))
			if w.Code != 201 {
				t.Fatalf("schedule: %d %s", w.Code, w.Body.String())
			}
			before := doSync(t, h, guest, 0)
			since := int64(before["current_version"].(float64))
			path, token := "/api/auth/profile", guest
			if adminEdit {
				path, token = fmt.Sprintf("/api/admin/users/%d", guestID), owner
			}
			w = httptest.NewRecorder()
			h.ServeHTTP(w, authedRequest("PUT", path, `{"name":"Renamed Guest"}`, token))
			if w.Code != 204 {
				t.Fatalf("rename: %d %s", w.Code, w.Body.String())
			}
			after := doSync(t, h, guest, since)
			for _, field := range []string{"transactions", "scheduled_transactions"} {
				rows := after[field].([]any)
				if len(rows) != 1 || rows[0].(map[string]any)["created_by_name"] != "Renamed Guest" {
					t.Fatalf("%s missing new creator: %v", field, rows)
				}
				if int(rows[0].(map[string]any)["created_by_user_id"].(float64)) != guestID {
					t.Fatal("creator changed")
				}
			}
			accounts := after["accounts"].([]any)
			if len(accounts) != 1 || accounts[0].(map[string]any)["owner_name"] != "Renamed Guest" {
				t.Fatalf("owner name: %v", accounts)
			}
			var balance float64
			if err := s.DB.QueryRow("SELECT balance FROM accounts WHERE id=?", account).Scan(&balance); err != nil {
				t.Fatal(err)
			}
			if balance != -7 {
				t.Fatalf("balance changed: %v", balance)
			}
			// Reapplying the same display name must not resend the whole history.
			w = httptest.NewRecorder()
			h.ServeHTTP(w, authedRequest("PUT", path, `{"name":"Renamed Guest"}`, token))
			if w.Code != http.StatusNoContent {
				t.Fatalf("unchanged rename: %d %s", w.Code, w.Body.String())
			}
			unchanged := doSync(t, h, guest, int64(after["current_version"].(float64)))
			if len(unchanged["transactions"].([]any)) != 0 {
				t.Fatal("unchanged name resent history")
			}
		})
	}
}

func TestProfileUpdatesAreAtomic(t *testing.T) {
	for _, adminEdit := range []bool{false, true} {
		for _, invalid := range []bool{false, true} {
			t.Run(fmt.Sprintf("admin=%v/invalid=%v", adminEdit, invalid), func(t *testing.T) {
				s, cleanup := setupTestServer(t)
				defer cleanup()
				if err := ledgerdb.InstallMetadataSyncTriggers(s.DB); err != nil {
					t.Fatal(err)
				}
				h := NewRouter(s)
				admin, guest, id := setupTwoUsers(t, h)
				path, token := "/api/auth/profile", guest
				if adminEdit {
					path, token = fmt.Sprintf("/api/admin/users/%d", id), admin
				}
				var username, email string
				if err := s.DB.QueryRow("SELECT username,email FROM users WHERE id=1").Scan(&username, &email); err != nil {
					t.Fatal(err)
				}
				body := fmt.Sprintf(`{"username":"changed","email":%q}`, email)
				expected := http.StatusConflict
				if invalid {
					body = fmt.Sprintf(`{"username":"changed","name":%q}`, strings.Repeat("x", 101))
					expected = http.StatusBadRequest
				}
				w := httptest.NewRecorder()
				h.ServeHTTP(w, authedRequest("PUT", path, body, token))
				if w.Code != expected {
					t.Fatalf("got %d want %d: %s", w.Code, expected, w.Body.String())
				}
				if err := s.DB.QueryRow("SELECT username FROM users WHERE id=?", id).Scan(&username); err != nil {
					t.Fatal(err)
				}
				if username != "guest" {
					t.Fatalf("partial update stored username %q", username)
				}
			})
		}
	}
}
