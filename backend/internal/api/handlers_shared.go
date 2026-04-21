package api

import (
	"encoding/json"
	"log"
	"net/http"
	"strconv"
	"strings"
)

type sharedAccessRequest struct {
	GuestEmail string `json:"guest_email"`
	AccountID  int64  `json:"account_id"`
	Permission string `json:"permission"` // "read" or "write"
}

// handleListSharedAccess returns the per-account shares the authenticated user
// has granted, one row per (guest, account) pair.
func (s *Server) handleListSharedAccess(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	rows, err := s.DB.Query(
		`SELECT sa.id, sa.owner_user_id, sa.guest_user_id, sa.account_id, sa.permission,
		        u.name, u.email, a.name
		 FROM shared_account_access sa
		 JOIN users u ON sa.guest_user_id = u.id
		 JOIN accounts a ON sa.account_id = a.id
		 WHERE sa.owner_user_id = ? AND a.deleted_at IS NULL
		 ORDER BY u.email, a.name`, userID,
	)
	if err != nil {
		http.Error(w, "query error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	items := []map[string]any{}
	for rows.Next() {
		var id, ownerID, guestID, accountID int64
		var permission, guestName, guestEmail, accountName string
		if err := rows.Scan(&id, &ownerID, &guestID, &accountID, &permission,
			&guestName, &guestEmail, &accountName); err != nil {
			log.Printf("shared access: scan error: %v", err)
			continue
		}
		items = append(items, map[string]any{
			"id": id, "owner_user_id": ownerID, "guest_user_id": guestID,
			"account_id": accountID, "account_name": accountName,
			"permission": permission, "guest_name": guestName, "guest_email": guestEmail,
		})
	}
	if err := rows.Err(); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(items)
}

// handleCreateSharedAccess grants a guest read or write access to a single
// account owned by the authenticated user.
func (s *Server) handleCreateSharedAccess(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())
	var req sharedAccessRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	if req.Permission != "read" && req.Permission != "write" {
		http.Error(w, "permission must be 'read' or 'write'", http.StatusBadRequest)
		return
	}
	if req.AccountID == 0 {
		http.Error(w, "account_id is required", http.StatusBadRequest)
		return
	}
	req.GuestEmail = strings.TrimSpace(req.GuestEmail)
	if req.GuestEmail == "" {
		http.Error(w, "guest_email is required", http.StatusBadRequest)
		return
	}

	// Account must belong to the authenticated user.
	var accountOwner int64
	if err := s.DB.QueryRow(
		"SELECT user_id FROM accounts WHERE id = ? AND deleted_at IS NULL", req.AccountID,
	).Scan(&accountOwner); err != nil {
		http.Error(w, "account not found", http.StatusNotFound)
		return
	}
	if accountOwner != userID {
		http.Error(w, "forbidden: not the account owner", http.StatusForbidden)
		return
	}

	var guestID int64
	if err := s.DB.QueryRow(
		"SELECT id FROM users WHERE email = ?", req.GuestEmail,
	).Scan(&guestID); err != nil {
		http.Error(w, "user not found", http.StatusNotFound)
		return
	}
	if guestID == userID {
		http.Error(w, "cannot share with yourself", http.StatusBadRequest)
		return
	}

	result, err := s.DB.Exec(
		`INSERT INTO shared_account_access (owner_user_id, guest_user_id, account_id, permission)
		 VALUES (?, ?, ?, ?)`,
		userID, guestID, req.AccountID, req.Permission,
	)
	if err != nil {
		http.Error(w, "already shared with this user for this account", http.StatusConflict)
		return
	}

	id, _ := result.LastInsertId()
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(map[string]any{"id": id})
}

// handleListAccessibleOwners returns the owners that have shared at least one
// account with the authenticated user, with the list of accounts and per-account
// permissions nested under each owner.
func (s *Server) handleListAccessibleOwners(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	rows, err := s.DB.Query(
		`SELECT sa.owner_user_id, u.name, u.email, sa.account_id, a.name, sa.permission
		 FROM shared_account_access sa
		 JOIN users u ON sa.owner_user_id = u.id
		 JOIN accounts a ON sa.account_id = a.id
		 WHERE sa.guest_user_id = ? AND a.deleted_at IS NULL
		 ORDER BY u.email, a.name`, userID,
	)
	if err != nil {
		http.Error(w, "query error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	type acctEntry struct {
		AccountID   int64  `json:"account_id"`
		AccountName string `json:"account_name"`
		Permission  string `json:"permission"`
	}
	type ownerEntry struct {
		OwnerUserID int64       `json:"owner_user_id"`
		Name        string      `json:"name"`
		Email       string      `json:"email"`
		Accounts    []acctEntry `json:"accounts"`
	}

	owners := []*ownerEntry{}
	byID := map[int64]*ownerEntry{}
	for rows.Next() {
		var ownerID, accountID int64
		var name, email, accountName, permission string
		if err := rows.Scan(&ownerID, &name, &email, &accountID, &accountName, &permission); err != nil {
			log.Printf("accessible owners: scan error: %v", err)
			continue
		}
		entry, ok := byID[ownerID]
		if !ok {
			entry = &ownerEntry{OwnerUserID: ownerID, Name: name, Email: email, Accounts: []acctEntry{}}
			byID[ownerID] = entry
			owners = append(owners, entry)
		}
		entry.Accounts = append(entry.Accounts, acctEntry{
			AccountID: accountID, AccountName: accountName, Permission: permission,
		})
	}
	if err := rows.Err(); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(owners)
}

func (s *Server) handleDeleteSharedAccess(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}

	result, err := s.DB.Exec(
		"DELETE FROM shared_account_access WHERE id = ? AND owner_user_id = ?", id, userID,
	)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	rows, err := result.RowsAffected()
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if rows == 0 {
		http.Error(w, "shared access not found", http.StatusNotFound)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
