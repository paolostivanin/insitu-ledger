package api

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// TestRecoverMiddlewareReturns500OnPanic: a handler that panics must produce a
// 500 to the client, not propagate the panic and crash the connection. This is
// the load-bearing guarantee of RecoverMiddleware.
func TestRecoverMiddlewareReturns500OnPanic(t *testing.T) {
	h := RecoverMiddleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		panic("boom")
	}))

	req := httptest.NewRequest(http.MethodGet, "/anything", nil)
	rec := httptest.NewRecorder()

	// If RecoverMiddleware fails, this call panics and the test fails fast.
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusInternalServerError {
		t.Errorf("status = %d, want %d", rec.Code, http.StatusInternalServerError)
	}
}

// TestRecoverMiddlewarePassesThroughNormalRequests: handlers that don't panic
// must see no behavior change — RecoverMiddleware is inert on the happy path.
func TestRecoverMiddlewarePassesThroughNormalRequests(t *testing.T) {
	h := RecoverMiddleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusTeapot)
		w.Write([]byte("hello"))
	}))

	req := httptest.NewRequest(http.MethodGet, "/anything", nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusTeapot {
		t.Errorf("status = %d, want %d", rec.Code, http.StatusTeapot)
	}
	if rec.Body.String() != "hello" {
		t.Errorf("body = %q, want %q", rec.Body.String(), "hello")
	}
}
