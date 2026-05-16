package api

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// TestTOTPSetupRejectsWhenAlreadyEnabled: a session-token holder cannot
// silently rotate the TOTP secret of a user who has 2FA already enabled.
// Rotation must go through /totp/reset which requires password confirmation.
func TestTOTPSetupRejectsWhenAlreadyEnabled(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	enableTOTPForUser(t, s, 1)

	req := authedRequest("POST", "/api/auth/totp/setup", "", token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusConflict {
		t.Errorf("status = %d, want %d (body=%s)", w.Code, http.StatusConflict, w.Body.String())
	}
}

// TestTOTPSetupAllowsWhenNotEnabled: first-time setup must still work. The
// guard only blocks rotation, not initial activation.
func TestTOTPSetupAllowsWhenNotEnabled(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	req := authedRequest("POST", "/api/auth/totp/setup", "", token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("status = %d, want %d (body=%s)", w.Code, http.StatusOK, w.Body.String())
	}
}

// TestChangePasswordRateLimited: a session-token holder cannot brute-force the
// current password indefinitely. After the per-user budget is exhausted, the
// endpoint must respond 429 regardless of credential correctness.
func TestChangePasswordRateLimited(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	// 5 failed attempts (the limiter's budget) — all should return 401.
	body := `{"current_password":"wrong","new_password":"newpassword123"}`
	for i := 0; i < 5; i++ {
		req := authedRequest("POST", "/api/auth/change-password", body, token)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, req)
		if w.Code != http.StatusUnauthorized {
			t.Fatalf("attempt %d: status = %d, want %d (body=%s)", i+1, w.Code, http.StatusUnauthorized, w.Body.String())
		}
	}

	// 6th attempt must be throttled — even with the *correct* password, the
	// limiter trips before the credential check runs.
	req := authedRequest("POST", "/api/auth/change-password", `{"current_password":"testpassword","new_password":"newpassword123"}`, token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != http.StatusTooManyRequests {
		t.Errorf("post-budget: status = %d, want %d (body=%s)", w.Code, http.StatusTooManyRequests, w.Body.String())
	}
}
