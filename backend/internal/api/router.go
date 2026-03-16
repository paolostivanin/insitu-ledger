package api

import (
	"database/sql"
	"net/http"

	"github.com/pstivanin/insitu-ledger/backend/internal/auth"
)

// Server holds dependencies for all HTTP handlers.
type Server struct {
	DB        *sql.DB
	AuthStore *auth.Store
}

// NewRouter sets up all routes and returns the root handler.
func NewRouter(s *Server) http.Handler {
	mux := http.NewServeMux()
	authMw := AuthMiddleware(s.AuthStore)

	// Public routes (no registration — admin creates users)
	mux.HandleFunc("POST /api/auth/login", s.handleLogin)

	// Protected routes — wrapped with auth middleware
	protected := http.NewServeMux()
	protected.HandleFunc("POST /api/auth/logout", s.handleLogout)
	protected.HandleFunc("POST /api/auth/change-password", s.handleChangePassword)
	protected.HandleFunc("PUT /api/auth/profile", s.handleUpdateProfile)
	protected.HandleFunc("GET /api/auth/me", s.handleGetMe)

	// 2FA
	protected.HandleFunc("POST /api/auth/totp/setup", s.handleTOTPSetup)
	protected.HandleFunc("POST /api/auth/totp/verify", s.handleTOTPVerify)
	protected.HandleFunc("POST /api/auth/totp/reset", s.handleTOTPReset)

	// Transactions
	protected.HandleFunc("GET /api/transactions", s.handleListTransactions)
	protected.HandleFunc("POST /api/transactions", s.handleCreateTransaction)
	protected.HandleFunc("PUT /api/transactions/{id}", s.handleUpdateTransaction)
	protected.HandleFunc("DELETE /api/transactions/{id}", s.handleDeleteTransaction)

	// Categories
	protected.HandleFunc("GET /api/categories", s.handleListCategories)
	protected.HandleFunc("POST /api/categories", s.handleCreateCategory)
	protected.HandleFunc("PUT /api/categories/{id}", s.handleUpdateCategory)
	protected.HandleFunc("DELETE /api/categories/{id}", s.handleDeleteCategory)

	// Accounts
	protected.HandleFunc("GET /api/accounts", s.handleListAccounts)
	protected.HandleFunc("POST /api/accounts", s.handleCreateAccount)
	protected.HandleFunc("PUT /api/accounts/{id}", s.handleUpdateAccount)
	protected.HandleFunc("DELETE /api/accounts/{id}", s.handleDeleteAccount)

	// Scheduled transactions
	protected.HandleFunc("GET /api/scheduled", s.handleListScheduled)
	protected.HandleFunc("POST /api/scheduled", s.handleCreateScheduled)
	protected.HandleFunc("PUT /api/scheduled/{id}", s.handleUpdateScheduled)
	protected.HandleFunc("DELETE /api/scheduled/{id}", s.handleDeleteScheduled)

	// Sync endpoint for mobile
	protected.HandleFunc("GET /api/sync", s.handleSync)

	// Graph / reporting data
	protected.HandleFunc("GET /api/reports/by-category", s.handleReportByCategory)
	protected.HandleFunc("GET /api/reports/by-month", s.handleReportByMonth)
	protected.HandleFunc("GET /api/reports/trend", s.handleReportTrend)

	// Shared access
	protected.HandleFunc("GET /api/shared", s.handleListSharedAccess)
	protected.HandleFunc("POST /api/shared", s.handleCreateSharedAccess)
	protected.HandleFunc("DELETE /api/shared/{id}", s.handleDeleteSharedAccess)

	// Admin routes — protected by auth + admin check
	admin := http.NewServeMux()
	admin.HandleFunc("GET /api/admin/users", s.handleAdminListUsers)
	admin.HandleFunc("POST /api/admin/users", s.handleAdminCreateUser)
	admin.HandleFunc("PUT /api/admin/users/{id}", s.handleAdminUpdateUser)
	admin.HandleFunc("DELETE /api/admin/users/{id}", s.handleAdminDeleteUser)
	admin.HandleFunc("POST /api/admin/users/{id}/reset-password", s.handleAdminResetPassword)
	admin.HandleFunc("POST /api/admin/users/{id}/toggle-admin", s.handleAdminToggleAdmin)
	admin.HandleFunc("POST /api/admin/users/{id}/disable-totp", s.handleAdminDisableTOTP)
	protected.Handle("/api/admin/", s.AdminMiddleware(admin))

	// Mount protected routes behind auth middleware
	mux.Handle("/api/", authMw(protected))

	// Serve frontend static files
	mux.Handle("/", http.FileServer(http.Dir("static")))

	return mux
}
