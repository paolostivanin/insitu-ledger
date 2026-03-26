package api

import (
	"encoding/csv"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strconv"
	"strings"
)

func (s *Server) handleExportTransactions(w http.ResponseWriter, r *http.Request) {
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

	query := `SELECT t.date, t.type, t.amount, t.currency, t.description,
		COALESCE(c.name, ''), COALESCE(a.name, '')
		FROM transactions t
		LEFT JOIN categories c ON c.id = t.category_id
		LEFT JOIN accounts a ON a.id = t.account_id
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
	query += " ORDER BY t.date DESC"

	rows, err := s.DB.Query(query, args...)
	if err != nil {
		http.Error(w, "query error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	w.Header().Set("Content-Type", "text/csv")
	w.Header().Set("Content-Disposition", "attachment; filename=transactions.csv")

	cw := csv.NewWriter(w)
	cw.Write([]string{"date", "type", "amount", "currency", "description", "category_name", "account_name"})

	for rows.Next() {
		var date, typ, currency, description, catName, acctName string
		var amount float64
		if err := rows.Scan(&date, &typ, &amount, &currency, &description, &catName, &acctName); err != nil {
			log.Printf("export scan error: %v", err)
			continue
		}
		cw.Write([]string{
			truncDate(date), typ, strconv.FormatFloat(amount, 'f', 2, 64),
			currency, description, catName, acctName,
		})
	}
	if err := rows.Err(); err != nil {
		log.Printf("export rows iteration error: %v", err)
	}
	cw.Flush()
}

const maxImportSize = 10 << 20 // 10 MB
const maxImportRows = 50000

func (s *Server) handleImportTransactions(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	targetUserID, permission, err := resolveTargetUserID(r, userID, s.DB)
	if err != nil {
		if err.Error() == "forbidden: no shared access" {
			http.Error(w, err.Error(), http.StatusForbidden)
		} else {
			http.Error(w, err.Error(), http.StatusBadRequest)
		}
		return
	}
	if permission != "write" {
		http.Error(w, "forbidden: read-only access", http.StatusForbidden)
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, maxImportSize)
	if err := r.ParseMultipartForm(maxImportSize); err != nil {
		http.Error(w, "file too large or invalid multipart", http.StatusBadRequest)
		return
	}

	file, _, err := r.FormFile("file")
	if err != nil {
		http.Error(w, "file field required", http.StatusBadRequest)
		return
	}
	defer file.Close()

	reader := csv.NewReader(file)

	// Read header
	header, err := reader.Read()
	if err != nil {
		http.Error(w, "empty or invalid CSV", http.StatusBadRequest)
		return
	}
	expectedHeader := []string{"date", "type", "amount", "currency", "description", "category_name", "account_name"}
	if len(header) != len(expectedHeader) {
		http.Error(w, fmt.Sprintf("expected %d columns, got %d", len(expectedHeader), len(header)), http.StatusBadRequest)
		return
	}
	for i, h := range expectedHeader {
		if strings.TrimSpace(strings.ToLower(header[i])) != h {
			http.Error(w, fmt.Sprintf("column %d: expected '%s', got '%s'", i+1, h, header[i]), http.StatusBadRequest)
			return
		}
	}

	// Build lookup maps for categories and accounts
	catMap := map[string]int64{}
	acctMap := map[string]int64{}

	catRows, err := s.DB.Query("SELECT id, name FROM categories WHERE user_id = ? AND deleted_at IS NULL", targetUserID)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer catRows.Close()
	for catRows.Next() {
		var id int64
		var name string
		if err := catRows.Scan(&id, &name); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		catMap[strings.ToLower(name)] = id
	}
	if err := catRows.Err(); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	acctRows, err := s.DB.Query("SELECT id, name FROM accounts WHERE user_id = ? AND deleted_at IS NULL", targetUserID)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer acctRows.Close()
	for acctRows.Next() {
		var id int64
		var name string
		if err := acctRows.Scan(&id, &name); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		acctMap[strings.ToLower(name)] = id
	}
	if err := acctRows.Err(); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	tx, err := s.DB.Begin()
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	imported := 0
	rowNum := 1
	for {
		record, err := reader.Read()
		if err == io.EOF {
			break
		}
		if err != nil {
			http.Error(w, fmt.Sprintf("row %d: CSV parse error: %v", rowNum+1, err), http.StatusBadRequest)
			return
		}
		rowNum++

		if rowNum > maxImportRows+1 {
			http.Error(w, fmt.Sprintf("import limited to %d rows", maxImportRows), http.StatusBadRequest)
			return
		}

		if len(record) != 7 {
			http.Error(w, fmt.Sprintf("row %d: expected 7 columns, got %d", rowNum, len(record)), http.StatusBadRequest)
			return
		}

		date := strings.TrimSpace(record[0])
		typ := strings.TrimSpace(record[1])
		amountStr := strings.TrimSpace(record[2])
		currency := strings.TrimSpace(record[3])
		description := strings.TrimSpace(record[4])
		catName := strings.TrimSpace(record[5])
		acctName := strings.TrimSpace(record[6])

		if len(description) > 500 {
			http.Error(w, fmt.Sprintf("row %d: description exceeds 500 characters", rowNum), http.StatusBadRequest)
			return
		}

		if typ != "income" && typ != "expense" {
			http.Error(w, fmt.Sprintf("row %d: type must be 'income' or 'expense'", rowNum), http.StatusBadRequest)
			return
		}

		amount, err := strconv.ParseFloat(amountStr, 64)
		if err != nil || amount <= 0 {
			http.Error(w, fmt.Sprintf("row %d: invalid amount", rowNum), http.StatusBadRequest)
			return
		}

		if currency == "" {
			currency = "EUR"
		}

		catID, ok := catMap[strings.ToLower(catName)]
		if !ok {
			http.Error(w, fmt.Sprintf("row %d: unknown category '%s'", rowNum, catName), http.StatusBadRequest)
			return
		}

		acctID, ok := acctMap[strings.ToLower(acctName)]
		if !ok {
			http.Error(w, fmt.Sprintf("row %d: unknown account '%s'", rowNum, acctName), http.StatusBadRequest)
			return
		}

		var desc *string
		if description != "" {
			desc = &description
		}

		if _, err := tx.Exec(
			`INSERT INTO transactions (account_id, category_id, user_id, type, amount, currency, description, date)
			 VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
			acctID, catID, targetUserID, typ, amount, currency, desc, date,
		); err != nil {
			http.Error(w, fmt.Sprintf("row %d: insert error: %v", rowNum, err), http.StatusInternalServerError)
			return
		}

		sign := 1.0
		if typ == "expense" {
			sign = -1.0
		}
		if _, err := tx.Exec("UPDATE accounts SET balance = balance + ? WHERE id = ?", amount*sign, acctID); err != nil {
			http.Error(w, fmt.Sprintf("row %d: balance update error", rowNum), http.StatusInternalServerError)
			return
		}

		imported++
	}

	if err := tx.Commit(); err != nil {
		log.Printf("import commit error: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"imported": imported})
}
