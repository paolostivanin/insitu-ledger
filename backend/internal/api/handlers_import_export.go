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

	accIDs, err := scopedAccountIDs(r, userID, s.DB)
	if err != nil {
		writeAuthError(w, err)
		return
	}

	from := r.URL.Query().Get("from")
	to := r.URL.Query().Get("to")

	// "added_by" projects the creator's display name unconditionally — solo
	// accounts get the same column populated, which is simpler than per-row
	// branching and keeps the CSV schema stable across share/un-share.
	query := `SELECT t.date, t.type, t.amount, t.currency, t.description, COALESCE(t.note, ''),
		COALESCE(c.name, ''), COALESCE(a.name, ''), COALESCE(cu.name, '')
		FROM transactions t
		LEFT JOIN categories c ON c.id = t.category_id
		LEFT JOIN accounts a ON a.id = t.account_id
		LEFT JOIN users cu ON cu.id = t.created_by_user_id
		WHERE t.deleted_at IS NULL
		  AND t.account_id IN (` + sqlInPlaceholders(len(accIDs)) + `)`
	args := idsToArgs(accIDs)

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
	cw.Write([]string{"date", "type", "amount", "currency", "description", "note", "category_name", "account_name", "added_by"})

	for rows.Next() {
		var date, typ, currency, description, note, catName, acctName, addedBy string
		var amount float64
		if err := rows.Scan(&date, &typ, &amount, &currency, &description, &note, &catName, &acctName, &addedBy); err != nil {
			log.Printf("export scan error: %v", err)
			continue
		}
		cw.Write([]string{
			truncDate(date), typ, strconv.FormatFloat(amount, 'f', 2, 64),
			currency, description, note, catName, acctName, addedBy,
		})
	}
	if err := rows.Err(); err != nil {
		log.Printf("export rows iteration error: %v", err)
	}
	cw.Flush()
	rows.Close()

	// Data-access audit: bulk export across all accessible accounts is sensitive
	// regardless of ownership. Logged after rows.Close() so the audit INSERT
	// doesn't contend with the open SELECT for a SQLite connection.
	writeAuditLog(s.DB, userID, "export_transactions", int64Ptr(userID), "self", s.clientIP(r))
}

const maxImportSize = 10 << 20 // 10 MB
const maxImportRows = 50000

func (s *Server) handleImportTransactions(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	targetUserID, _, err := resolveTargetOwner(r, userID, s.DB)
	if err != nil {
		writeAuthError(w, err)
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
	// Three accepted shapes:
	//   - 7 cols (oldest): date, type, amount, currency, description, category_name, account_name
	//   - 8 cols (v1.4+): + note column at position 6
	//   - 9 cols (v1.15+): + added_by column at the end (ignored on import — importer is always stamped as the creator)
	headerNewest := []string{"date", "type", "amount", "currency", "description", "note", "category_name", "account_name", "added_by"}
	headerNew := []string{"date", "type", "amount", "currency", "description", "note", "category_name", "account_name"}
	headerOld := []string{"date", "type", "amount", "currency", "description", "category_name", "account_name"}
	hasNoteCol := false
	switch len(header) {
	case len(headerNewest):
		hasNoteCol = true
		for i, h := range headerNewest {
			if strings.TrimSpace(strings.ToLower(header[i])) != h {
				http.Error(w, fmt.Sprintf("column %d: expected '%s', got '%s'", i+1, h, header[i]), http.StatusBadRequest)
				return
			}
		}
	case len(headerNew):
		hasNoteCol = true
		for i, h := range headerNew {
			if strings.TrimSpace(strings.ToLower(header[i])) != h {
				http.Error(w, fmt.Sprintf("column %d: expected '%s', got '%s'", i+1, h, header[i]), http.StatusBadRequest)
				return
			}
		}
	case len(headerOld):
		for i, h := range headerOld {
			if strings.TrimSpace(strings.ToLower(header[i])) != h {
				http.Error(w, fmt.Sprintf("column %d: expected '%s', got '%s'", i+1, h, header[i]), http.StatusBadRequest)
				return
			}
		}
	default:
		http.Error(w, fmt.Sprintf("expected 7, 8, or 9 columns, got %d", len(header)), http.StatusBadRequest)
		return
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

	// Only accounts the auth user can access in the target owner's space are
	// valid import destinations. (Post v1.15.0, access == write.)
	writableAccIDs, err := listAccessibleAccountIDs(userID, targetUserID, s.DB)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if len(writableAccIDs) == 0 {
		http.Error(w, "forbidden: no accessible accounts", http.StatusForbidden)
		return
	}
	acctRows, err := s.DB.Query(
		"SELECT id, name FROM accounts WHERE user_id = ? AND deleted_at IS NULL AND id IN ("+
			sqlInPlaceholders(len(writableAccIDs))+")",
		append([]any{targetUserID}, idsToArgs(writableAccIDs)...)...,
	)
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

		expectedCols := len(header)
		if len(record) != expectedCols {
			http.Error(w, fmt.Sprintf("row %d: expected %d columns, got %d", rowNum, expectedCols, len(record)), http.StatusBadRequest)
			return
		}

		date := strings.TrimSpace(record[0])
		typ := strings.TrimSpace(record[1])
		amountStr := strings.TrimSpace(record[2])
		currency := strings.TrimSpace(record[3])
		description := strings.TrimSpace(record[4])
		var note string
		var catName, acctName string
		if hasNoteCol {
			note = strings.TrimSpace(record[5])
			catName = strings.TrimSpace(record[6])
			acctName = strings.TrimSpace(record[7])
		} else {
			catName = strings.TrimSpace(record[5])
			acctName = strings.TrimSpace(record[6])
		}

		if len(description) > 500 {
			http.Error(w, fmt.Sprintf("row %d: description exceeds 500 characters", rowNum), http.StatusBadRequest)
			return
		}
		if len(note) > 2000 {
			http.Error(w, fmt.Sprintf("row %d: note exceeds 2000 characters", rowNum), http.StatusBadRequest)
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
		var notePtr *string
		if note != "" {
			notePtr = &note
		}

		if _, err := tx.Exec(
			`INSERT INTO transactions (account_id, category_id, user_id, created_by_user_id, type, amount, currency, description, note, date)
			 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
			acctID, catID, targetUserID, userID, typ, amount, currency, desc, notePtr, date,
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

	importDetails := fmt.Sprintf("imported=%d", imported)
	if targetUserID != userID {
		importDetails = fmt.Sprintf("on_behalf_of=%d %s", targetUserID, importDetails)
	}
	writeAuditLog(s.DB, userID, "import_transactions", int64Ptr(targetUserID), importDetails, s.clientIP(r))

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"imported": imported})
}
