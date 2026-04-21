package api

import (
	"encoding/json"
	"log"
	"net/http"
	"strconv"
)

func (s *Server) handleSync(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	ownerStr := r.URL.Query().Get("owner_id")

	sinceStr := r.URL.Query().Get("since")
	since, _ := strconv.ParseInt(sinceStr, 10, 64)

	// Use a read transaction so all queries see a consistent snapshot.
	tx, err := s.DB.Begin()
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	// Build the sync scope.
	//   - Aggregate mode (no owner_id): every account the auth user can read
	//     (own + shared) and every owner whose categories they should see.
	//     Includes soft-deleted accounts so clients can purge them locally.
	//   - Per-owner mode (owner_id present): legacy behavior — own accounts
	//     for self-sync, or only shared-access account IDs for a guest.
	var (
		syncAccIDs   []int64
		categoryUIDs []int64
	)
	if ownerStr == "" {
		ownAccts, err := tx.Query(`SELECT id FROM accounts WHERE user_id = ?`, userID)
		if err != nil {
			http.Error(w, "query error", http.StatusInternalServerError)
			return
		}
		for ownAccts.Next() {
			var id int64
			if err := ownAccts.Scan(&id); err == nil {
				syncAccIDs = append(syncAccIDs, id)
			}
		}
		ownAccts.Close()

		sharedAccts, err := tx.Query(
			`SELECT account_id FROM shared_account_access WHERE guest_user_id = ?`,
			userID,
		)
		if err != nil {
			http.Error(w, "query error", http.StatusInternalServerError)
			return
		}
		for sharedAccts.Next() {
			var id int64
			if err := sharedAccts.Scan(&id); err == nil {
				syncAccIDs = append(syncAccIDs, id)
			}
		}
		sharedAccts.Close()

		ownerIDs, err := listAccessibleOwnerIDs(userID, tx)
		if err != nil {
			http.Error(w, "query error", http.StatusInternalServerError)
			return
		}
		categoryUIDs = ownerIDs
	} else {
		targetUserID, isOwn, err := resolveTargetOwner(r, userID, s.DB)
		if err != nil {
			writeAuthError(w, err)
			return
		}
		categoryUIDs = []int64{targetUserID}
		if isOwn {
			idRows, err := tx.Query(`SELECT id FROM accounts WHERE user_id = ?`, targetUserID)
			if err != nil {
				http.Error(w, "query error", http.StatusInternalServerError)
				return
			}
			for idRows.Next() {
				var id int64
				if err := idRows.Scan(&id); err == nil {
					syncAccIDs = append(syncAccIDs, id)
				}
			}
			idRows.Close()
		} else {
			idRows, err := tx.Query(
				`SELECT account_id FROM shared_account_access
				 WHERE owner_user_id = ? AND guest_user_id = ?`,
				targetUserID, userID,
			)
			if err != nil {
				http.Error(w, "query error", http.StatusInternalServerError)
				return
			}
			for idRows.Next() {
				var id int64
				if err := idRows.Scan(&id); err == nil {
					syncAccIDs = append(syncAccIDs, id)
				}
			}
			idRows.Close()
		}
	}
	accInClause := sqlInPlaceholders(len(syncAccIDs))
	catUIDClause := sqlInPlaceholders(len(categoryUIDs))

	var currentVersion int64
	tx.QueryRow("SELECT version FROM sync_meta WHERE id = 1").Scan(&currentVersion)

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

	// Fetch changed transactions (including soft-deleted ones so mobile can remove them).
	// Includes created_by_user_id and the creator's display name for the "Added by" UI.
	txnArgs := append([]any{since}, idsToArgs(syncAccIDs)...)
	txnRows, err := tx.Query(
		`SELECT t.id, t.account_id, t.category_id, t.user_id, t.type, t.amount, t.currency,
		        t.description, t.note, t.date, t.created_at, t.updated_at, t.deleted_at, t.sync_version,
		        t.created_by_user_id, cu.name
		 FROM transactions t
		 LEFT JOIN users cu ON cu.id = t.created_by_user_id
		 WHERE t.sync_version > ?
		   AND t.account_id IN (`+accInClause+`)`, txnArgs...,
	)
	if err != nil {
		log.Printf("sync: transaction query error: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer txnRows.Close()
	var txns []map[string]any
	for txnRows.Next() {
		var id, accountID, categoryID, uid, sv int64
		var typ, currency, date, createdAt, updatedAt string
		var amount float64
		var description, note, deletedAt, createdByName *string
		var createdByUserID *int64
		if err := txnRows.Scan(&id, &accountID, &categoryID, &uid, &typ, &amount, &currency,
			&description, &note, &date, &createdAt, &updatedAt, &deletedAt, &sv,
			&createdByUserID, &createdByName); err != nil {
			log.Printf("sync: scan transaction error: %v", err)
			continue
		}
		txns = append(txns, map[string]any{
			"id": id, "account_id": accountID, "category_id": categoryID,
			"user_id": uid, "type": typ, "amount": amount, "currency": currency,
			"description": description, "note": note, "date": date, "created_at": createdAt,
			"updated_at": updatedAt, "deleted_at": deletedAt, "sync_version": sv,
			"created_by_user_id": createdByUserID,
			"created_by_name":    createdByName,
		})
	}
	if err := txnRows.Err(); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if txns == nil {
		txns = []map[string]any{}
	}
	resp["transactions"] = txns

	// Fetch changed categories from every accessible owner.
	catArgs := append([]any{since}, idsToArgs(categoryUIDs)...)
	catRows, err := tx.Query(
		`SELECT id, user_id, parent_id, name, type, icon, color, created_at, updated_at, deleted_at, sync_version
		 FROM categories WHERE sync_version > ? AND user_id IN (`+catUIDClause+`)`, catArgs...,
	)
	if err != nil {
		log.Printf("sync: category query error: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer catRows.Close()
	var cats []map[string]any
	for catRows.Next() {
		var id, uid, sv int64
		var parentID *int64
		var name, typ, createdAt, updatedAt string
		var icon, color, deletedAt *string
		if err := catRows.Scan(&id, &uid, &parentID, &name, &typ, &icon, &color, &createdAt, &updatedAt, &deletedAt, &sv); err != nil {
			log.Printf("sync: scan category error: %v", err)
			continue
		}
		cats = append(cats, map[string]any{
			"id": id, "user_id": uid, "parent_id": parentID, "name": name, "type": typ,
			"icon": icon, "color": color, "created_at": createdAt, "updated_at": updatedAt,
			"deleted_at": deletedAt, "sync_version": sv,
		})
	}
	if err := catRows.Err(); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if cats == nil {
		cats = []map[string]any{}
	}
	resp["categories"] = cats

	// Fetch changed accounts (only those in scope for this user). The owner
	// display name + is_shared flag travel with each account so Android can
	// render the "Shared by [name]" badge offline from its local cache.
	acctArgs := append([]any{since}, idsToArgs(syncAccIDs)...)
	acctRows, err := tx.Query(
		`SELECT a.id, a.user_id, a.name, a.currency, a.balance,
		        a.created_at, a.updated_at, a.deleted_at, a.sync_version,
		        u.name AS owner_name,
		        EXISTS(SELECT 1 FROM shared_account_access s WHERE s.account_id = a.id) AS is_shared
		 FROM accounts a
		 JOIN users u ON u.id = a.user_id
		 WHERE a.sync_version > ?
		   AND a.id IN (`+accInClause+`)`, acctArgs...,
	)
	if err != nil {
		log.Printf("sync: account query error: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer acctRows.Close()
	var accts []map[string]any
	for acctRows.Next() {
		var id, uid, sv int64
		var name, currency, createdAt, updatedAt, ownerName string
		var balance float64
		var deletedAt *string
		var isShared int
		if err := acctRows.Scan(&id, &uid, &name, &currency, &balance, &createdAt, &updatedAt, &deletedAt, &sv, &ownerName, &isShared); err != nil {
			log.Printf("sync: scan account error: %v", err)
			continue
		}
		accts = append(accts, map[string]any{
			"id": id, "user_id": uid, "name": name, "currency": currency,
			"balance": balance, "created_at": createdAt, "updated_at": updatedAt,
			"deleted_at": deletedAt, "sync_version": sv,
			"owner_user_id": uid,
			"owner_name":    ownerName,
			"is_shared":     isShared == 1,
		})
	}
	if err := acctRows.Err(); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if accts == nil {
		accts = []map[string]any{}
	}
	resp["accounts"] = accts

	// Fetch changed scheduled transactions
	schedArgs := append([]any{since}, idsToArgs(syncAccIDs)...)
	schedRows, err := tx.Query(
		`SELECT s.id, s.account_id, s.category_id, s.user_id, s.type, s.amount, s.currency,
		        s.description, s.note, s.rrule, s.next_occurrence, s.active,
		        s.max_occurrences, s.occurrence_count,
		        s.created_at, s.updated_at, s.deleted_at, s.sync_version,
		        s.created_by_user_id, cu.name
		 FROM scheduled_transactions s
		 LEFT JOIN users cu ON cu.id = s.created_by_user_id
		 WHERE s.sync_version > ?
		   AND s.account_id IN (`+accInClause+`)`, schedArgs...,
	)
	if err != nil {
		log.Printf("sync: scheduled query error: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer schedRows.Close()
	var scheds []map[string]any
	for schedRows.Next() {
		var id, accountID, categoryID, uid, sv, occurrenceCount int64
		var active int
		var typ, currency, rrule, nextOcc, createdAt, updatedAt string
		var amount float64
		var description, note, deletedAt, createdByName *string
		var maxOccurrences, createdByUserID *int64
		if err := schedRows.Scan(&id, &accountID, &categoryID, &uid, &typ, &amount, &currency,
			&description, &note, &rrule, &nextOcc, &active, &maxOccurrences, &occurrenceCount,
			&createdAt, &updatedAt, &deletedAt, &sv, &createdByUserID, &createdByName); err != nil {
			log.Printf("sync: scan scheduled transaction error: %v", err)
			continue
		}
		scheds = append(scheds, map[string]any{
			"id": id, "account_id": accountID, "category_id": categoryID,
			"user_id": uid, "type": typ, "amount": amount, "currency": currency,
			"description": description, "note": note, "rrule": rrule, "next_occurrence": nextOcc,
			"active": active == 1, "max_occurrences": maxOccurrences, "occurrence_count": occurrenceCount,
			"created_at": createdAt, "updated_at": updatedAt,
			"deleted_at":         deletedAt,
			"sync_version":       sv,
			"created_by_user_id": createdByUserID,
			"created_by_name":    createdByName,
		})
	}
	if err := schedRows.Err(); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if scheds == nil {
		scheds = []map[string]any{}
	}
	resp["scheduled_transactions"] = scheds

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}
