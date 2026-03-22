package api

import (
	"context"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/pstivanin/insitu-ledger/backend/internal/auth"
)

type contextKey string

const userIDKey contextKey = "user_id"

const maxRequestBodySize = 1 << 20 // 1 MB

// AuthMiddleware validates the bearer token and injects user_id into context.
func AuthMiddleware(store *auth.Store) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			token, err := auth.ExtractToken(r)
			if err != nil {
				http.Error(w, "unauthorized", http.StatusUnauthorized)
				return
			}
			userID, err := store.ValidateToken(token)
			if err != nil {
				http.Error(w, "unauthorized", http.StatusUnauthorized)
				return
			}
			ctx := context.WithValue(r.Context(), userIDKey, userID)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

// UserIDFromContext retrieves the authenticated user's ID from the request context.
func UserIDFromContext(ctx context.Context) int64 {
	id, _ := ctx.Value(userIDKey).(int64)
	return id
}

// LoggingMiddleware logs method, path, status, and duration for every request.
func LoggingMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		sw := &statusWriter{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(sw, r)
		log.Printf("%s %s %d %s", r.Method, r.URL.Path, sw.status, time.Since(start).Round(time.Millisecond))
	})
}

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (sw *statusWriter) WriteHeader(code int) {
	sw.status = code
	sw.ResponseWriter.WriteHeader(code)
}

// SecurityHeadersMiddleware adds standard security response headers.
func SecurityHeadersMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Referrer-Policy", "strict-origin-when-cross-origin")
		next.ServeHTTP(w, r)
	})
}

// BodyLimitMiddleware restricts request body size to maxRequestBodySize.
func BodyLimitMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Body != nil {
			r.Body = http.MaxBytesReader(w, r.Body, maxRequestBodySize)
		}
		next.ServeHTTP(w, r)
	})
}

// LoginRateLimiter tracks per-IP login attempt rates.
type LoginRateLimiter struct {
	mu       sync.Mutex
	attempts map[string][]time.Time
}

// NewLoginRateLimiter creates a rate limiter for login attempts.
func NewLoginRateLimiter() *LoginRateLimiter {
	rl := &LoginRateLimiter{
		attempts: make(map[string][]time.Time),
	}
	// Periodically clean up stale entries
	go func() {
		for range time.Tick(10 * time.Minute) {
			rl.cleanup()
		}
	}()
	return rl
}

const (
	loginRateWindow = 15 * time.Minute
	loginRateMax    = 10
)

// Allow returns true if the IP is allowed to attempt a login.
func (rl *LoginRateLimiter) Allow(ip string) bool {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	now := time.Now()
	cutoff := now.Add(-loginRateWindow)

	// Filter to recent attempts only
	recent := rl.attempts[ip][:0]
	for _, t := range rl.attempts[ip] {
		if t.After(cutoff) {
			recent = append(recent, t)
		}
	}

	if len(recent) >= loginRateMax {
		rl.attempts[ip] = recent
		return false
	}

	rl.attempts[ip] = append(recent, now)
	return true
}

func (rl *LoginRateLimiter) cleanup() {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	cutoff := time.Now().Add(-loginRateWindow)
	for ip, times := range rl.attempts {
		recent := times[:0]
		for _, t := range times {
			if t.After(cutoff) {
				recent = append(recent, t)
			}
		}
		if len(recent) == 0 {
			delete(rl.attempts, ip)
		} else {
			rl.attempts[ip] = recent
		}
	}
}
