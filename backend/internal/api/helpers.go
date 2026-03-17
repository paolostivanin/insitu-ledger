package api

import (
	"fmt"
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
