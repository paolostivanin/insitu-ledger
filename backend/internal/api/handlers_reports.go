package api

import (
	"encoding/json"
	"log"
	"net/http"
	"strings"
)

func (s *Server) handleReportByCategory(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	accIDs, err := scopedAccountIDs(r, userID, s.DB)
	if err != nil {
		writeAuthError(w, err)
		return
	}

	from := r.URL.Query().Get("from")
	to := r.URL.Query().Get("to")
	typ := r.URL.Query().Get("type") // "income" or "expense"

	// p.id (not c.parent_id) so parent_id comes back NULL exactly when there is
	// no *live* parent. A child whose parent was soft-deleted then reports as
	// its own top-level bucket instead of rolling up into a nameless one.
	query := `SELECT c.id, c.name, c.color, p.id, p.name, p.color, t.type, SUM(t.amount) as total
	          FROM transactions t
	          JOIN categories c ON t.category_id = c.id
	          LEFT JOIN categories p ON c.parent_id = p.id AND p.deleted_at IS NULL
	          WHERE t.deleted_at IS NULL
	            AND t.account_id IN (` + sqlInPlaceholders(len(accIDs)) + `)`
	args := idsToArgs(accIDs)

	if from != "" {
		query += " AND t.date >= ?"
		args = append(args, from)
	}
	if to != "" {
		query += " AND SUBSTR(t.date, 1, 10) <= ?"
		args = append(args, to)
	}
	if typ != "" {
		query += " AND t.type = ?"
		args = append(args, typ)
	}

	query += " GROUP BY c.id, t.type ORDER BY total DESC LIMIT 1000"

	rows, err := s.DB.Query(query, args...)
	if err != nil {
		http.Error(w, "query error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	var results []map[string]any
	for rows.Next() {
		var catID int64
		var catName, catType string
		var catColor *string
		var parentID *int64
		var parentName, parentColor *string
		var total float64
		if err := rows.Scan(&catID, &catName, &catColor, &parentID, &parentName, &parentColor, &catType, &total); err != nil {
			log.Printf("report by-category: scan error: %v", err)
			continue
		}
		results = append(results, map[string]any{
			"category_id": catID, "category_name": catName,
			"category_color": catColor, "type": catType, "total": total,
			"parent_id": parentID, "parent_name": parentName, "parent_color": parentColor,
		})
	}
	if err := rows.Err(); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	if results == nil {
		results = []map[string]any{}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(results)
}

// handleReportSummary totals the transactions matching a free-text search (and
// the usual date/category filters) into money in, money out and the net.
//
// The use case is a trip or project tagged in the description: searching
// "valencia" should surface both what you spent and what friends paid back,
// and the balance between them.
//
// Grouped by currency rather than summed into one figure. The other report
// endpoints do sum across currencies, but a summary of a trip abroad is
// exactly where mixed currencies show up, and adding dollars to euros there
// would produce a confident wrong number.
func (s *Server) handleReportSummary(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	accIDs, err := scopedAccountIDs(r, userID, s.DB)
	if err != nil {
		writeAuthError(w, err)
		return
	}

	from := r.URL.Query().Get("from")
	to := r.URL.Query().Get("to")
	catID := r.URL.Query().Get("category_id")
	q := strings.TrimSpace(r.URL.Query().Get("q"))

	if from != "" {
		if err := validateDate(from); err != nil {
			http.Error(w, "invalid 'from' date: "+err.Error(), http.StatusBadRequest)
			return
		}
	}
	if to != "" {
		if err := validateDate(to); err != nil {
			http.Error(w, "invalid 'to' date: "+err.Error(), http.StatusBadRequest)
			return
		}
	}
	if err := validateLength("q", q, maxSearchLen); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	query := `SELECT t.currency,
	                 COALESCE(SUM(CASE WHEN t.type = 'income' THEN t.amount END), 0) AS income,
	                 COALESCE(SUM(CASE WHEN t.type = 'expense' THEN t.amount END), 0) AS expense,
	                 COUNT(*) AS count
	          FROM transactions t
	          WHERE t.deleted_at IS NULL
	            AND t.account_id IN (` + sqlInPlaceholders(len(accIDs)) + `)`
	args := idsToArgs(accIDs)

	if from != "" {
		query += " AND t.date >= ?"
		args = append(args, from)
	}
	if to != "" {
		query += " AND SUBSTR(t.date, 1, 10) <= ?"
		args = append(args, to)
	}
	if catID != "" {
		query += " AND t.category_id IN (SELECT id FROM categories WHERE id = ? OR parent_id = ?)"
		args = append(args, catID, catID)
	}
	if q != "" {
		// Description only, matching GET /api/transactions — so the rows behind
		// a total are the rows the same search shows in the list.
		query += ` AND t.description LIKE ? ESCAPE '\'`
		args = append(args, "%"+escapeLike(q)+"%")
	}

	// SUM(t.amount) is income+expense (amounts are stored positive; `type`
	// carries the sign), so this puts the dominant currency first.
	query += " GROUP BY t.currency ORDER BY SUM(t.amount) DESC, t.currency ASC LIMIT 100"

	rows, err := s.DB.Query(query, args...)
	if err != nil {
		http.Error(w, "query error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	results := []map[string]any{}
	for rows.Next() {
		var currency string
		var income, expense float64
		var count int64
		if err := rows.Scan(&currency, &income, &expense, &count); err != nil {
			log.Printf("report summary: scan error: %v", err)
			continue
		}
		results = append(results, map[string]any{
			"currency": currency, "income": income, "expense": expense,
			"net": income - expense, "count": count,
		})
	}
	if err := rows.Err(); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(results)
}

func (s *Server) handleReportByMonth(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	accIDs, err := scopedAccountIDs(r, userID, s.DB)
	if err != nil {
		writeAuthError(w, err)
		return
	}

	year := r.URL.Query().Get("year")

	// SUBSTR (not strftime) — SQLite's strftime treats a trailing "+02:00" as
	// a UTC-offset modifier and would re-bucket offset-bearing rows by UTC,
	// shifting month/year for rows near midnight. SUBSTR on the typed prefix
	// matches the display philosophy (typed wall-clock).
	query := `SELECT SUBSTR(date, 1, 7) as month, type, SUM(amount) as total
	          FROM transactions
	          WHERE deleted_at IS NULL
	            AND account_id IN (` + sqlInPlaceholders(len(accIDs)) + `)`
	args := idsToArgs(accIDs)

	if year != "" {
		query += " AND SUBSTR(date, 1, 4) = ?"
		args = append(args, year)
	}

	query += " GROUP BY month, type ORDER BY month LIMIT 1000"

	rows, err := s.DB.Query(query, args...)
	if err != nil {
		http.Error(w, "query error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	var results []map[string]any
	for rows.Next() {
		var month, typ string
		var total float64
		if err := rows.Scan(&month, &typ, &total); err != nil {
			log.Printf("report by-month: scan error: %v", err)
			continue
		}
		results = append(results, map[string]any{
			"month": month, "type": typ, "total": total,
		})
	}
	if err := rows.Err(); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	if results == nil {
		results = []map[string]any{}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(results)
}

func (s *Server) handleReportTrend(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	accIDs, err := scopedAccountIDs(r, userID, s.DB)
	if err != nil {
		writeAuthError(w, err)
		return
	}

	from := r.URL.Query().Get("from")
	to := r.URL.Query().Get("to")
	groupBy := r.URL.Query().Get("group_by") // "day", "week", "month"

	// Bucket on the SUBSTR'd date prefix instead of raw strftime(date) — see
	// handleReportByMonth above for why. Weekly stays on strftime, but applied
	// to the offset-stripped date so it can't be mis-normalized.
	var periodExpr string
	switch groupBy {
	case "day":
		periodExpr = "SUBSTR(date, 1, 10)"
	case "week":
		periodExpr = "strftime('%Y-W%W', SUBSTR(date, 1, 10))"
	default:
		periodExpr = "SUBSTR(date, 1, 7)"
	}

	query := `SELECT ` + periodExpr + ` as period, type, SUM(amount) as total
	          FROM transactions
	          WHERE deleted_at IS NULL
	            AND account_id IN (` + sqlInPlaceholders(len(accIDs)) + `)`
	args := idsToArgs(accIDs)

	if from != "" {
		query += " AND date >= ?"
		args = append(args, from)
	}
	if to != "" {
		query += " AND SUBSTR(date, 1, 10) <= ?"
		args = append(args, to)
	}

	query += " GROUP BY period, type ORDER BY period LIMIT 1000"

	rows, err := s.DB.Query(query, args...)
	if err != nil {
		http.Error(w, "query error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	var results []map[string]any
	for rows.Next() {
		var period, typ string
		var total float64
		if err := rows.Scan(&period, &typ, &total); err != nil {
			log.Printf("report trend: scan error: %v", err)
			continue
		}
		results = append(results, map[string]any{
			"period": period, "type": typ, "total": total,
		})
	}
	if err := rows.Err(); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	if results == nil {
		results = []map[string]any{}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(results)
}
