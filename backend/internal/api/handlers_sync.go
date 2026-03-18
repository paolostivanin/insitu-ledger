package api

import (
	"encoding/json"
	"net/http"
	"strconv"
)

func (s *Server) handleSync(w http.ResponseWriter, r *http.Request) {
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

	sinceStr := r.URL.Query().Get("since")
	since, _ := strconv.ParseInt(sinceStr, 10, 64)

	var currentVersion int64
	s.DB.QueryRow("SELECT version FROM sync_meta WHERE id = 1").Scan(&currentVersion)

	if since >= currentVersion {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"current_version":        currentVersion,
			"transactions":           []any{},
			"categories":             []any{},
			"accounts":               []any{},
			"scheduled_transactions": []any{},
		})
		return
	}

	resp := map[string]any{"current_version": currentVersion}

	// Fetch changed transactions (including soft-deleted ones so mobile can remove them)
	txnRows, err := s.DB.Query(
		`SELECT id, account_id, category_id, user_id, type, amount, currency,
		        description, date, created_at, updated_at, deleted_at, sync_version
		 FROM transactions WHERE user_id = ? AND sync_version > ?`, targetUserID, since,
	)
	if err == nil {
		var txns []map[string]any
		for txnRows.Next() {
			var id, accountID, categoryID, uid, sv int64
			var typ, currency, date, createdAt, updatedAt string
			var amount float64
			var description, deletedAt *string
			txnRows.Scan(&id, &accountID, &categoryID, &uid, &typ, &amount, &currency,
				&description, &date, &createdAt, &updatedAt, &deletedAt, &sv)
			txns = append(txns, map[string]any{
				"id": id, "account_id": accountID, "category_id": categoryID,
				"user_id": uid, "type": typ, "amount": amount, "currency": currency,
				"description": description, "date": truncDate(date), "created_at": createdAt,
				"updated_at": updatedAt, "deleted_at": deletedAt, "sync_version": sv,
			})
		}
		txnRows.Close()
		if txns == nil {
			txns = []map[string]any{}
		}
		resp["transactions"] = txns
	}

	// Fetch changed categories
	catRows, err := s.DB.Query(
		`SELECT id, user_id, parent_id, name, type, icon, color, created_at, updated_at, deleted_at, sync_version
		 FROM categories WHERE user_id = ? AND sync_version > ?`, targetUserID, since,
	)
	if err == nil {
		var cats []map[string]any
		for catRows.Next() {
			var id, uid, sv int64
			var parentID *int64
			var name, typ, createdAt, updatedAt string
			var icon, color, deletedAt *string
			catRows.Scan(&id, &uid, &parentID, &name, &typ, &icon, &color, &createdAt, &updatedAt, &deletedAt, &sv)
			cats = append(cats, map[string]any{
				"id": id, "user_id": uid, "parent_id": parentID, "name": name, "type": typ,
				"icon": icon, "color": color, "created_at": createdAt, "updated_at": updatedAt,
				"deleted_at": deletedAt, "sync_version": sv,
			})
		}
		catRows.Close()
		if cats == nil {
			cats = []map[string]any{}
		}
		resp["categories"] = cats
	}

	// Fetch changed accounts
	acctRows, err := s.DB.Query(
		`SELECT id, user_id, name, currency, balance, created_at, updated_at, deleted_at, sync_version
		 FROM accounts WHERE user_id = ? AND sync_version > ?`, targetUserID, since,
	)
	if err == nil {
		var accts []map[string]any
		for acctRows.Next() {
			var id, uid, sv int64
			var name, currency, createdAt, updatedAt string
			var balance float64
			var deletedAt *string
			acctRows.Scan(&id, &uid, &name, &currency, &balance, &createdAt, &updatedAt, &deletedAt, &sv)
			accts = append(accts, map[string]any{
				"id": id, "user_id": uid, "name": name, "currency": currency,
				"balance": balance, "created_at": createdAt, "updated_at": updatedAt,
				"deleted_at": deletedAt, "sync_version": sv,
			})
		}
		acctRows.Close()
		if accts == nil {
			accts = []map[string]any{}
		}
		resp["accounts"] = accts
	}

	// Fetch changed scheduled transactions
	schedRows, err := s.DB.Query(
		`SELECT id, account_id, category_id, user_id, type, amount, currency,
		        description, rrule, next_occurrence, active, max_occurrences, occurrence_count,
		        created_at, updated_at, deleted_at, sync_version
		 FROM scheduled_transactions WHERE user_id = ? AND sync_version > ?`, targetUserID, since,
	)
	if err == nil {
		var scheds []map[string]any
		for schedRows.Next() {
			var id, accountID, categoryID, uid, sv, occurrenceCount int64
			var active int
			var typ, currency, rrule, nextOcc, createdAt, updatedAt string
			var amount float64
			var description, deletedAt *string
			var maxOccurrences *int64
			schedRows.Scan(&id, &accountID, &categoryID, &uid, &typ, &amount, &currency,
				&description, &rrule, &nextOcc, &active, &maxOccurrences, &occurrenceCount,
				&createdAt, &updatedAt, &deletedAt, &sv)
			scheds = append(scheds, map[string]any{
				"id": id, "account_id": accountID, "category_id": categoryID,
				"user_id": uid, "type": typ, "amount": amount, "currency": currency,
				"description": description, "rrule": rrule, "next_occurrence": nextOcc,
				"active": active == 1, "max_occurrences": maxOccurrences, "occurrence_count": occurrenceCount,
				"created_at": createdAt, "updated_at": updatedAt,
				"deleted_at": deletedAt, "sync_version": sv,
			})
		}
		schedRows.Close()
		if scheds == nil {
			scheds = []map[string]any{}
		}
		resp["scheduled_transactions"] = scheds
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}
