package api

import (
	"encoding/json"
	"log"
	"net/http"
)

func (s *Server) handleReportByCategory(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	targetUserID, _, err := resolveTargetUserID(r, userID, s.DB)
	if err != nil {
		if err.Error() == "forbidden: no shared access" {
			http.Error(w, err.Error(), http.StatusForbidden)
		} else {
			http.Error(w, err.Error(), http.StatusBadRequest)
		}
		return
	}

	from := r.URL.Query().Get("from")
	to := r.URL.Query().Get("to")
	typ := r.URL.Query().Get("type") // "income" or "expense"

	query := `SELECT c.id, c.name, c.color, t.type, SUM(t.amount) as total
	          FROM transactions t
	          JOIN categories c ON t.category_id = c.id
	          WHERE t.user_id = ? AND t.deleted_at IS NULL`
	args := []any{targetUserID}

	if from != "" {
		query += " AND SUBSTR(t.date, 1, 10) >= ?"
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
		var total float64
		if err := rows.Scan(&catID, &catName, &catColor, &catType, &total); err != nil {
			log.Printf("report by-category: scan error: %v", err)
			continue
		}
		results = append(results, map[string]any{
			"category_id": catID, "category_name": catName,
			"category_color": catColor, "type": catType, "total": total,
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

func (s *Server) handleReportByMonth(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	targetUserID, _, err := resolveTargetUserID(r, userID, s.DB)
	if err != nil {
		if err.Error() == "forbidden: no shared access" {
			http.Error(w, err.Error(), http.StatusForbidden)
		} else {
			http.Error(w, err.Error(), http.StatusBadRequest)
		}
		return
	}

	year := r.URL.Query().Get("year")

	query := `SELECT strftime('%Y-%m', date) as month, type, SUM(amount) as total
	          FROM transactions
	          WHERE user_id = ? AND deleted_at IS NULL`
	args := []any{targetUserID}

	if year != "" {
		query += " AND strftime('%Y', date) = ?"
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

	targetUserID, _, err := resolveTargetUserID(r, userID, s.DB)
	if err != nil {
		if err.Error() == "forbidden: no shared access" {
			http.Error(w, err.Error(), http.StatusForbidden)
		} else {
			http.Error(w, err.Error(), http.StatusBadRequest)
		}
		return
	}

	from := r.URL.Query().Get("from")
	to := r.URL.Query().Get("to")
	groupBy := r.URL.Query().Get("group_by") // "day", "week", "month"

	// strftimeFmts is a strict whitelist — never add user-controlled values here.
	strftimeFmts := map[string]string{
		"day":  "%Y-%m-%d",
		"week": "%Y-W%W",
	}
	strftimeFmt, ok := strftimeFmts[groupBy]
	if !ok {
		strftimeFmt = "%Y-%m"
	}

	query := `SELECT strftime('` + strftimeFmt + `', date) as period, type, SUM(amount) as total
	          FROM transactions
	          WHERE user_id = ? AND deleted_at IS NULL`
	args := []any{targetUserID}

	if from != "" {
		query += " AND SUBSTR(date, 1, 10) >= ?"
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
