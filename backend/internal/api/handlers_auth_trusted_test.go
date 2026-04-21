package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/pquerna/otp/totp"
	"github.com/pstivanin/insitu-ledger/backend/internal/auth"
)

// enableTOTPForUser flips totp_enabled and stores a known secret on the
// given user, returning the secret so the test can mint valid codes.
func enableTOTPForUser(t *testing.T, s *Server, userID int64) string {
	t.Helper()
	key, err := totp.Generate(totp.GenerateOpts{Issuer: "test", AccountName: "u"})
	if err != nil {
		t.Fatal(err)
	}
	_, err = s.DB.Exec(
		"UPDATE users SET totp_enabled = 1, totp_secret = ? WHERE id = ?",
		key.Secret(), userID,
	)
	if err != nil {
		t.Fatal(err)
	}
	return key.Secret()
}

func loginPostJSON(handler http.Handler, body string, cookie *http.Cookie) *httptest.ResponseRecorder {
	req := httptest.NewRequest("POST", "/api/auth/login", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	if cookie != nil {
		req.AddCookie(cookie)
	}
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	return w
}

func extractCookie(t *testing.T, w *httptest.ResponseRecorder, name string) *http.Cookie {
	t.Helper()
	for _, c := range w.Result().Cookies() {
		if c.Name == name && c.Value != "" {
			return c
		}
	}
	return nil
}

// loginResp matches the JSON shape of authResponse so each call gets a
// fresh struct (json.Unmarshal into a reused map merges fields, which can
// hide regressions like a token+totp_required response).
type loginResp struct {
	Token        string `json:"token"`
	TOTPRequired bool   `json:"totp_required"`
}

func parseLogin(t *testing.T, w *httptest.ResponseRecorder) loginResp {
	t.Helper()
	var r loginResp
	if err := json.Unmarshal(w.Body.Bytes(), &r); err != nil {
		t.Fatalf("decode login response: %v (body=%s)", err, w.Body.String())
	}
	return r
}

func TestTrustedDeviceLoginFlow(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)

	secret := enableTOTPForUser(t, s, 1)

	// 1. Without TOTP code, the server should ask for one (no cookie yet).
	w := loginPostJSON(handler, `{"login":"admin","password":"testpassword"}`, nil)
	if w.Code != 200 {
		t.Fatalf("step 1: got %d, body=%s", w.Code, w.Body.String())
	}
	if r := parseLogin(t, w); !r.TOTPRequired {
		t.Fatalf("step 1: expected totp_required=true, got %+v", r)
	}

	// 2. Submit code + trust_device → expect token AND a Set-Cookie.
	code, err := totp.GenerateCode(secret, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	body := `{"login":"admin","password":"testpassword","totp_code":"` + code + `","trust_device":true}`
	w = loginPostJSON(handler, body, nil)
	if w.Code != 200 {
		t.Fatalf("step 2: got %d, body=%s", w.Code, w.Body.String())
	}
	if r := parseLogin(t, w); r.Token == "" {
		t.Fatalf("step 2: expected token, got %+v", r)
	}
	cookie := extractCookie(t, w, "insitu_trusted_device")
	if cookie == nil {
		t.Fatal("step 2: expected Set-Cookie insitu_trusted_device")
	}
	if !cookie.HttpOnly {
		t.Error("trusted-device cookie must be HttpOnly")
	}
	if cookie.SameSite != http.SameSiteStrictMode {
		t.Errorf("trusted-device cookie SameSite = %v, want Strict", cookie.SameSite)
	}
	if cookie.Path != "/api/auth" {
		t.Errorf("trusted-device cookie Path = %q, want /api/auth", cookie.Path)
	}

	// 3. Re-login with the cookie and NO TOTP code → should succeed without
	//    prompting for 2FA.
	w = loginPostJSON(handler, `{"login":"admin","password":"testpassword"}`, cookie)
	if w.Code != 200 {
		t.Fatalf("step 3: got %d, body=%s", w.Code, w.Body.String())
	}
	r3 := parseLogin(t, w)
	if r3.TOTPRequired {
		t.Fatalf("step 3: expected NO totp prompt with valid cookie, got %+v", r3)
	}
	if r3.Token == "" {
		t.Fatalf("step 3: expected token, got %+v", r3)
	}

	// 4. Same login, no cookie → 2FA must be required again.
	w = loginPostJSON(handler, `{"login":"admin","password":"testpassword"}`, nil)
	if r := parseLogin(t, w); !r.TOTPRequired {
		t.Fatalf("step 4: expected totp_required without cookie, got %+v", r)
	}

	// 5. Wrong password must NOT honor the cookie.
	w = loginPostJSON(handler, `{"login":"admin","password":"wrong"}`, cookie)
	if w.Code != 401 {
		t.Errorf("step 5: cookie+wrong-password should be 401, got %d", w.Code)
	}
}

func TestTrustedDeviceCookieDoesNotCrossUsers(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)

	// admin (id 1) is seeded by setupTestServer. Add a second user.
	hash, _ := auth.HashPassword("password2")
	if _, err := s.DB.Exec(
		"INSERT INTO users (username, email, name, password_hash, is_admin) VALUES (?, ?, ?, ?, 0)",
		"user2", "user2@test.com", "U2", hash,
	); err != nil {
		t.Fatal(err)
	}
	var u2 int64
	s.DB.QueryRow("SELECT id FROM users WHERE username = 'user2'").Scan(&u2)

	secret1 := enableTOTPForUser(t, s, 1)
	enableTOTPForUser(t, s, u2)

	// Trust admin's browser.
	code, _ := totp.GenerateCode(secret1, time.Now())
	body := `{"login":"admin","password":"testpassword","totp_code":"` + code + `","trust_device":true}`
	w := loginPostJSON(handler, body, nil)
	if w.Code != 200 {
		t.Fatalf("trust setup failed: %d %s", w.Code, w.Body.String())
	}
	cookie := extractCookie(t, w, "insitu_trusted_device")
	if cookie == nil {
		t.Fatal("expected trusted-device cookie")
	}

	// Now try to log in as user2 with admin's cookie. The cookie must NOT
	// let user2 skip TOTP — otherwise stealing one user's cookie would
	// neuter 2FA for everyone with credentials.
	w = loginPostJSON(handler, `{"login":"user2","password":"password2"}`, cookie)
	if w.Code != 200 {
		t.Fatalf("user2 login: got %d, body=%s", w.Code, w.Body.String())
	}
	if r := parseLogin(t, w); !r.TOTPRequired {
		t.Fatalf("user2 with admin's cookie should still need TOTP, got %+v", r)
	}
}

func TestPasswordChangeRevokesTrustedDevices(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)

	secret := enableTOTPForUser(t, s, 1)

	// Trust the browser.
	code, _ := totp.GenerateCode(secret, time.Now())
	body := `{"login":"admin","password":"testpassword","totp_code":"` + code + `","trust_device":true}`
	w := loginPostJSON(handler, body, nil)
	if w.Code != 200 {
		t.Fatalf("trust setup failed: %d %s", w.Code, w.Body.String())
	}
	cookie := extractCookie(t, w, "insitu_trusted_device")
	if cookie == nil {
		t.Fatal("expected cookie")
	}
	token := parseLogin(t, w).Token
	if token == "" {
		t.Fatal("expected token from initial login")
	}

	// Sanity: cookie works.
	w = loginPostJSON(handler, `{"login":"admin","password":"testpassword"}`, cookie)
	if r := parseLogin(t, w); r.TOTPRequired {
		t.Fatal("cookie should bypass TOTP before password change")
	}

	// Change password.
	cpReq := authedRequest("POST", "/api/auth/change-password", `{"current_password":"testpassword","new_password":"newpassword"}`, token)
	wcp := httptest.NewRecorder()
	handler.ServeHTTP(wcp, cpReq)
	if wcp.Code != 204 {
		t.Fatalf("change-password: got %d, body=%s", wcp.Code, wcp.Body.String())
	}

	// Now the same cookie + new password should require TOTP again — the
	// trusted-device row was wiped by RevokeAllTrustedDevicesForUser.
	w = loginPostJSON(handler, `{"login":"admin","password":"newpassword"}`, cookie)
	if r := parseLogin(t, w); !r.TOTPRequired {
		t.Fatalf("after password change, cookie must NOT bypass TOTP, got %+v", r)
	}
}

