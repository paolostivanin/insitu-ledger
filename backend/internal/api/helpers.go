package api

import (
	"database/sql"
	"fmt"
	"net/http"
	"strconv"
	"time"
)

// truncDate ensures a date string is in YYYY-MM-DD format,
// trimming any time/timezone suffix that the SQLite driver may add.
func truncDate(s string) string {
	if len(s) >= 10 {
		return s[:10]
	}
	return s
}

// validateDate checks that a string is a valid YYYY-MM-DD date.
func validateDate(s string) error {
	if _, err := time.Parse("2006-01-02", s); err != nil {
		return fmt.Errorf("invalid date format, expected YYYY-MM-DD: %s", s)
	}
	return nil
}

// validateDatetime checks that a string is a valid YYYY-MM-DD or YYYY-MM-DDTHH:MM datetime.
func validateDatetime(s string) error {
	if _, err := time.Parse("2006-01-02", s); err == nil {
		return nil
	}
	if _, err := time.Parse("2006-01-02T15:04", s); err == nil {
		return nil
	}
	return fmt.Errorf("invalid format, expected YYYY-MM-DD or YYYY-MM-DDTHH:MM: %s", s)
}

// parsePagination parses limit/offset query params with bounds checking.
// Returns clamped limit (1-500, default 50) and non-negative offset (default 0).
// validateLength checks that a string does not exceed maxLen.
func validateLength(field, value string, maxLen int) error {
	if len(value) > maxLen {
		return fmt.Errorf("%s must not exceed %d characters", field, maxLen)
	}
	return nil
}

// parseSortParams validates and returns safe SQL sort fragments.
// Returns (column, direction, needsCategoryJoin, error).
func parseSortParams(sortBy, sortDir string) (string, string, bool, error) {
	columnMap := map[string]string{
		"date":        "date",
		"amount":      "amount",
		"description": "description",
		"category":    "c.name",
	}
	column, ok := columnMap[sortBy]
	if sortBy == "" {
		column = "date"
	} else if !ok {
		return "", "", false, fmt.Errorf("invalid sort_by: must be one of date, amount, description, category")
	}

	direction := "DESC"
	if sortDir == "asc" {
		direction = "ASC"
	} else if sortDir != "" && sortDir != "desc" {
		return "", "", false, fmt.Errorf("invalid sort_dir: must be 'asc' or 'desc'")
	}

	needsJoin := sortBy == "category"
	return column, direction, needsJoin, nil
}

// dbQuerier is the read interface satisfied by both *sql.DB and *sql.Tx.
type dbQuerier interface {
	QueryRow(string, ...any) *sql.Row
	Query(string, ...any) (*sql.Rows, error)
}

// errForbidden indicates the auth user has no access to the requested resource.
// Handlers should map it to HTTP 403.
const errForbidden = "forbidden: no shared access"

// resolveTargetOwner returns the user whose data the request is targeting.
//
// If the owner_id query param is absent or equal to authUserID, returns
// (authUserID, true, nil). Otherwise verifies that the auth user has at least
// one shared_account_access row from the requested owner and returns
// (ownerID, false, nil); 403 otherwise.
//
// Use this for non-account-scoped reads (e.g. categories list) and for "is
// this user the owner" checks before mutating owner-only resources like
// categories and accounts (create).
func resolveTargetOwner(r *http.Request, authUserID int64, db dbQuerier) (int64, bool, error) {
	ownerStr := r.URL.Query().Get("owner_id")
	if ownerStr == "" {
		return authUserID, true, nil
	}

	ownerID, err := strconv.ParseInt(ownerStr, 10, 64)
	if err != nil {
		return 0, false, fmt.Errorf("invalid owner_id")
	}
	if ownerID == authUserID {
		return authUserID, true, nil
	}

	var hasShare int
	err = db.QueryRow(
		`SELECT 1 FROM shared_account_access
		 WHERE owner_user_id = ? AND guest_user_id = ? LIMIT 1`,
		ownerID, authUserID,
	).Scan(&hasShare)
	if err != nil {
		return 0, false, fmt.Errorf(errForbidden)
	}
	return ownerID, false, nil
}

// listAccessibleAccountIDs returns the account IDs (non-deleted) in
// targetOwnerID's space that authUserID can read. If authUserID == targetOwnerID
// it returns all of the owner's accounts. Otherwise it returns only the
// accounts shared with the auth user.
//
// Returns an empty slice (not nil, not an error) when there's no access.
func listAccessibleAccountIDs(authUserID, targetOwnerID int64, db dbQuerier) ([]int64, error) {
	var (
		rows *sql.Rows
		err  error
	)
	if authUserID == targetOwnerID {
		rows, err = db.Query(
			`SELECT id FROM accounts WHERE user_id = ? AND deleted_at IS NULL`,
			targetOwnerID,
		)
	} else {
		rows, err = db.Query(
			`SELECT a.id FROM accounts a
			 JOIN shared_account_access s ON s.account_id = a.id
			 WHERE a.user_id = ? AND a.deleted_at IS NULL
			   AND s.owner_user_id = ? AND s.guest_user_id = ?`,
			targetOwnerID, targetOwnerID, authUserID,
		)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	ids := []int64{}
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}

// checkAccountAccess looks up the account and returns its owner user_id plus
// the auth user's permission ("read" or "write"). If the auth user owns the
// account they always get "write". Otherwise returns the share permission, or
// 403 if no share exists. Returns 404-style "account not found" if the
// account doesn't exist or is soft-deleted (mapped to 403 by handlers to
// avoid leaking IDs).
func checkAccountAccess(authUserID, accountID int64, db dbQuerier) (int64, string, error) {
	var ownerID int64
	err := db.QueryRow(
		`SELECT user_id FROM accounts WHERE id = ? AND deleted_at IS NULL`,
		accountID,
	).Scan(&ownerID)
	if err != nil {
		return 0, "", fmt.Errorf(errForbidden)
	}
	if ownerID == authUserID {
		return ownerID, "write", nil
	}
	var permission string
	err = db.QueryRow(
		`SELECT permission FROM shared_account_access
		 WHERE owner_user_id = ? AND guest_user_id = ? AND account_id = ?`,
		ownerID, authUserID, accountID,
	).Scan(&permission)
	if err != nil {
		return 0, "", fmt.Errorf(errForbidden)
	}
	return ownerID, permission, nil
}

// writeAuthError writes the standard HTTP response for an auth error from any
// of the resolve/check helpers above.
func writeAuthError(w http.ResponseWriter, err error) {
	if err.Error() == errForbidden {
		http.Error(w, err.Error(), http.StatusForbidden)
		return
	}
	http.Error(w, err.Error(), http.StatusBadRequest)
}

// sqlInPlaceholders returns "?,?,?" for n>0 or "NULL" for n==0 (so a
// resulting "id IN (NULL)" matches no rows without breaking SQL syntax).
func sqlInPlaceholders(n int) string {
	if n == 0 {
		return "NULL"
	}
	out := make([]byte, 0, n*2-1)
	for i := 0; i < n; i++ {
		if i > 0 {
			out = append(out, ',')
		}
		out = append(out, '?')
	}
	return string(out)
}

// idsToArgs widens []int64 to []any for use as variadic SQL arguments.
func idsToArgs(ids []int64) []any {
	out := make([]any, len(ids))
	for i, id := range ids {
		out[i] = id
	}
	return out
}

// listWritableAccountIDs returns account IDs in targetOwnerID's space that
// the auth user can write to. If isOwn (auth user == owner) returns all the
// owner's non-deleted accounts. Otherwise returns only accounts shared with
// permission='write'.
func listWritableAccountIDs(authUserID, targetOwnerID int64, db dbQuerier) ([]int64, error) {
	var (
		rows *sql.Rows
		err  error
	)
	if authUserID == targetOwnerID {
		rows, err = db.Query(
			`SELECT id FROM accounts WHERE user_id = ? AND deleted_at IS NULL`,
			targetOwnerID,
		)
	} else {
		rows, err = db.Query(
			`SELECT a.id FROM accounts a
			 JOIN shared_account_access s ON s.account_id = a.id
			 WHERE a.user_id = ? AND a.deleted_at IS NULL
			   AND s.owner_user_id = ? AND s.guest_user_id = ?
			   AND s.permission = 'write'`,
			targetOwnerID, targetOwnerID, authUserID,
		)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	ids := []int64{}
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}

// scopedAccountIDs returns the set of account IDs the auth user can read in
// targetOwner's space, optionally narrowed by the request's account_id query
// param. If account_id is set but doesn't intersect the accessible set, the
// returned slice is empty (queries using it will yield zero rows).
func scopedAccountIDs(r *http.Request, authUserID, targetOwnerID int64, db dbQuerier) ([]int64, error) {
	accIDs, err := listAccessibleAccountIDs(authUserID, targetOwnerID, db)
	if err != nil {
		return nil, err
	}
	want := r.URL.Query().Get("account_id")
	if want == "" {
		return accIDs, nil
	}
	wantID, err := strconv.ParseInt(want, 10, 64)
	if err != nil {
		return nil, fmt.Errorf("invalid account_id")
	}
	for _, id := range accIDs {
		if id == wantID {
			return []int64{wantID}, nil
		}
	}
	return []int64{}, nil
}

func parsePagination(limitStr, offsetStr string) (int, int, error) {
	limit := 50
	offset := 0

	if limitStr != "" {
		l, err := strconv.Atoi(limitStr)
		if err != nil {
			return 0, 0, fmt.Errorf("invalid limit")
		}
		if l < 1 {
			l = 1
		}
		if l > 500 {
			l = 500
		}
		limit = l
	}

	if offsetStr != "" {
		o, err := strconv.Atoi(offsetStr)
		if err != nil {
			return 0, 0, fmt.Errorf("invalid offset")
		}
		if o < 0 {
			o = 0
		}
		offset = o
	}

	return limit, offset, nil
}
