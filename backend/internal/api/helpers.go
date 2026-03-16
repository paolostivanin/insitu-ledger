package api

// truncDate ensures a date string is in YYYY-MM-DD format,
// trimming any time/timezone suffix that the SQLite driver may add.
func truncDate(s string) string {
	if len(s) >= 10 {
		return s[:10]
	}
	return s
}
