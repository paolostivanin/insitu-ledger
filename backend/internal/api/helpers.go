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

// resolveTargetUserID checks the owner_id query param.
// If absent or equal to authUserID, returns (authUserID, "write", nil).
// If different, checks shared_access and returns the permission or 403 error.
func resolveTargetUserID(r *http.Request, authUserID int64, db interface{ QueryRow(string, ...any) *sql.Row }) (int64, string, error) {
	ownerStr := r.URL.Query().Get("owner_id")
	if ownerStr == "" {
		return authUserID, "write", nil
	}

	ownerID, err := strconv.ParseInt(ownerStr, 10, 64)
	if err != nil {
		return 0, "", fmt.Errorf("invalid owner_id")
	}

	if ownerID == authUserID {
		return authUserID, "write", nil
	}

	var permission string
	err = db.QueryRow(
		"SELECT permission FROM shared_access WHERE owner_user_id = ? AND guest_user_id = ?",
		ownerID, authUserID,
	).Scan(&permission)
	if err != nil {
		return 0, "", fmt.Errorf("forbidden: no shared access")
	}

	return ownerID, permission, nil
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
