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
		w.Header().Set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'")
		// HSTS: instruct browsers to enforce HTTPS for 1 year. Has no effect
		// over plain HTTP (the spec ignores it), so safe to always send. The
		// reverse proxy may also set this; browsers honor the strictest.
		w.Header().Set("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
		next.ServeHTTP(w, r)
	})
}

// BodyLimitMiddleware restricts request body size to maxRequestBodySize.
//
// Endpoints that legitimately accept large uploads (DB restore, CSV import)
// are exempt here and apply their own MaxBytesReader. If we left the global
// 1 MB cap in place, those handlers would EOF before they could read past it
// — wrapping a MaxBytesReader with a larger limit doesn't help, the inner
// reader still hits the smaller cap first.
func BodyLimitMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/admin/restore", "/api/transactions/import":
			next.ServeHTTP(w, r)
			return
		}
		if r.Body != nil {
			r.Body = http.MaxBytesReader(w, r.Body, maxRequestBodySize)
		}
		next.ServeHTTP(w, r)
	})
}

// slidingWindowLimiter is a simple per-key sliding-window counter used for
// both login throttling (per IP) and TOTP throttling (per user). It is in
// memory only and resets on restart, which is acceptable for rate limiting.
type slidingWindowLimiter struct {
	mu       sync.Mutex
	window   time.Duration
	max      int
	attempts map[string][]time.Time
	done     chan struct{}
}

func newSlidingWindowLimiter(window time.Duration, max int) *slidingWindowLimiter {
	rl := &slidingWindowLimiter{
		window:   window,
		max:      max,
		attempts: make(map[string][]time.Time),
		done:     make(chan struct{}),
	}
	go func() {
		ticker := time.NewTicker(10 * time.Minute)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				rl.cleanup()
			case <-rl.done:
				return
			}
		}
	}()
	return rl
}

func (rl *slidingWindowLimiter) Stop() {
	close(rl.done)
}

func (rl *slidingWindowLimiter) Allow(key string) bool {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	now := time.Now()
	cutoff := now.Add(-rl.window)

	recent := rl.attempts[key][:0]
	for _, t := range rl.attempts[key] {
		if t.After(cutoff) {
			recent = append(recent, t)
		}
	}

	if len(recent) >= rl.max {
		rl.attempts[key] = recent
		return false
	}

	rl.attempts[key] = append(recent, now)
	return true
}

// Reset drops all recorded attempts for a key — used to clear TOTP failures
// after a successful login.
func (rl *slidingWindowLimiter) Reset(key string) {
	rl.mu.Lock()
	defer rl.mu.Unlock()
	delete(rl.attempts, key)
}

func (rl *slidingWindowLimiter) cleanup() {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	cutoff := time.Now().Add(-rl.window)
	for k, times := range rl.attempts {
		recent := times[:0]
		for _, t := range times {
			if t.After(cutoff) {
				recent = append(recent, t)
			}
		}
		if len(recent) == 0 {
			delete(rl.attempts, k)
		} else {
			rl.attempts[k] = recent
		}
	}
}

// LoginRateLimiter throttles login attempts per IP (10 / 15 min).
type LoginRateLimiter struct{ *slidingWindowLimiter }

// NewLoginRateLimiter creates a rate limiter for the login endpoint.
func NewLoginRateLimiter() *LoginRateLimiter {
	return &LoginRateLimiter{newSlidingWindowLimiter(15*time.Minute, 10)}
}

// TOTPRateLimiter throttles TOTP attempts per user account (5 / 15 min).
// Reset on successful login.
type TOTPRateLimiter struct{ *slidingWindowLimiter }

func NewTOTPRateLimiter() *TOTPRateLimiter {
	return &TOTPRateLimiter{newSlidingWindowLimiter(15*time.Minute, 5)}
}

// APIRateLimiter throttles all authenticated API calls per IP. The threshold
// is well above any human use of the UI — its purpose is to bound damage
// from runaway scripts or scrapers, not to slow down normal use.
type APIRateLimiter struct{ *slidingWindowLimiter }

func NewAPIRateLimiter() *APIRateLimiter {
	return &APIRateLimiter{newSlidingWindowLimiter(1*time.Minute, 300)}
}

// APIRateLimitMiddleware applies a per-IP cap to every /api/ request.
// Health and docs endpoints are excluded so monitoring doesn't get throttled.
func APIRateLimitMiddleware(rl *APIRateLimiter, ipFn func(*http.Request) string) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if !rl.Allow(ipFn(r)) {
				w.Header().Set("Retry-After", "60")
				http.Error(w, "rate limit exceeded", http.StatusTooManyRequests)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}
