package api

import (
	"context"
	"net/http"

	"github.com/pstivanin/insitu-ledger/backend/internal/auth"
)

type contextKey string

const userIDKey contextKey = "user_id"

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
