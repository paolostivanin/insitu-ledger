package api

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"strings"
)

// maxBatchIDs caps how many distinct ids a batch edit may carry. The web
// "select all matching" flow and Android's bulk edit refuse above it too.
const maxBatchIDs = 5000

type batchDeleteRequest struct {
	IDs []int64 `json:"ids"`
}

type batchUpdateCategoryRequest struct {
	IDs        []int64 `json:"ids"`
	CategoryID int64   `json:"category_id"`
}

// batchEditRequest is the body of POST /api/transactions/batch-update and
// POST /api/scheduled/batch-update. A nil (absent or JSON null) Description
// or CategoryID leaves that field unchanged. UpdateSchedules is read by the
// transactions endpoint only.
type batchEditRequest struct {
	IDs             []int64 `json:"ids"`
	Description     *string `json:"description"`
	CategoryID      *int64  `json:"category_id"`
	UpdateSchedules bool    `json:"update_schedules"`
}

// batchEditCounts is the response of POST /api/scheduled/batch-update.
type batchEditCounts struct {
	Updated   int `json:"updated"`
	Unchanged int `json:"unchanged"`
	Skipped   int `json:"skipped"`
}

// batchUpdateTransactionsResponse is the response of
// POST /api/transactions/batch-update. The schedules_* counts stay 0 unless
// update_schedules was set.
type batchUpdateTransactionsResponse struct {
	Updated            int `json:"updated"`
	Unchanged          int `json:"unchanged"`
	Skipped            int `json:"skipped"`
	SchedulesUpdated   int `json:"schedules_updated"`
	SchedulesUnchanged int `json:"schedules_unchanged"`
	SchedulesSkipped   int `json:"schedules_skipped"`
}

// Client-facing messages of the batch edit endpoints. Part of the API
// contract: the web and Android clients show them verbatim.
const (
	errBatchInvalidBody      = "invalid request body"
	errBatchIDsRequired      = "ids required"
	errBatchNothingToUpdate  = "nothing to update: set description and/or category_id"
	errBatchInvalidID        = "invalid id"
	errBatchEmptyDescription = "description must not be empty"
	errBatchInvalidCategory  = "invalid category_id"
	errBatchMultiOwner       = "selected entries belong to different owners; change the category for one owner at a time"
	errBatchCategoryNotFound = "category not found"
	errBatchMixedTypes       = "selected entries mix income and expense; change the category for one type at a time"
	errBatchTypeMismatch     = "category type does not match the selected entries"
	errBatchScheduleOwner    = "a linked recurring schedule belongs to a different owner"
	errBatchScheduleType     = "a linked recurring schedule has a different type than the new category"
)

var errBatchTooManyIDs = fmt.Sprintf("too many ids (max %d)", maxBatchIDs)

func (s *Server) handleBatchDeleteTransactions(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	var req batchDeleteRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	if len(req.IDs) == 0 {
		http.Error(w, "ids required", http.StatusBadRequest)
		return
	}
	for _, id := range req.IDs {
		if id <= 0 {
			http.Error(w, errBatchInvalidID, http.StatusBadRequest)
			return
		}
	}
	// "Select all matching" can send thousands of ids; bound the IN list.
	// Duplicates were already harmless (IN matches a row once), but dedupe
	// so the cap counts distinct ids.
	ids := dedupeIDs(req.IDs)
	if len(ids) > maxBatchIDs {
		http.Error(w, errBatchTooManyIDs, http.StatusBadRequest)
		return
	}

	tx, err := s.DB.Begin()
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	// Fetch all candidate transactions (no user filter — auth is per-account below).
	placeholders := make([]string, len(ids))
	args := make([]any, 0, len(ids))
	for i, id := range ids {
		placeholders[i] = "?"
		args = append(args, id)
	}

	fetchQuery := fmt.Sprintf(
		"SELECT id, amount, type, account_id FROM transactions WHERE id IN (%s) AND deleted_at IS NULL",
		strings.Join(placeholders, ","),
	)
	rows, err := tx.Query(fetchQuery, args...)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	// Accumulate balance deltas per account
	type txnInfo struct {
		id        int64
		amount    float64
		typ       string
		accountID int64
	}
	var found []txnInfo
	for rows.Next() {
		var t txnInfo
		if err := rows.Scan(&t.id, &t.amount, &t.typ, &t.accountID); err != nil {
			rows.Close()
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		found = append(found, t)
	}
	rows.Close()
	// A truncated read must not turn into a partial delete.
	if err := rows.Err(); err != nil {
		log.Printf("batch delete: read: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	if len(found) == 0 {
		tx.Rollback()
		w.WriteHeader(http.StatusNoContent)
		return
	}

	// Verify the caller has access to every affected account.
	checked := map[int64]bool{}
	for _, t := range found {
		if checked[t.accountID] {
			continue
		}
		if _, err := checkAccountAccess(userID, t.accountID, tx); err != nil {
			http.Error(w, errForbidden, http.StatusForbidden)
			return
		}
		checked[t.accountID] = true
	}

	// Soft-delete all matching transactions in one query
	delPlaceholders := make([]string, len(found))
	delArgs := make([]any, len(found))
	for i, t := range found {
		delPlaceholders[i] = "?"
		delArgs[i] = t.id
	}
	delQuery := fmt.Sprintf("UPDATE transactions SET deleted_at = datetime('now') WHERE deleted_at IS NULL AND id IN (%s)", strings.Join(delPlaceholders, ","))
	res, err := tx.Exec(delQuery, delArgs...)
	if err != nil {
		http.Error(w, "delete failed", http.StatusInternalServerError)
		return
	}
	// Every row whose balance effect is reversed below must be the row that
	// was just deleted, exactly once.
	if n, err := res.RowsAffected(); err != nil || n != int64(len(found)) {
		log.Printf("batch delete: %d rows deleted, want %d (err %v)", n, len(found), err)
		http.Error(w, "delete failed", http.StatusInternalServerError)
		return
	}

	// Aggregate balance adjustments per account
	balanceDeltas := make(map[int64]float64)
	for _, t := range found {
		sign := 1.0
		if t.typ == "expense" {
			sign = -1.0
		}
		balanceDeltas[t.accountID] -= t.amount * sign
	}
	for accountID, delta := range balanceDeltas {
		if _, err := tx.Exec("UPDATE accounts SET balance = balance + ? WHERE id = ?", delta, accountID); err != nil {
			http.Error(w, "failed to update balance", http.StatusInternalServerError)
			return
		}
	}

	if err := tx.Commit(); err != nil {
		log.Printf("commit error: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusNoContent)
}

// batchError is a rejected batch request: the HTTP status and the message.
type batchError struct {
	status int
	msg    string
}

func badBatch(msg string) *batchError { return &batchError{http.StatusBadRequest, msg} }

var (
	errBatchForbidden = &batchError{http.StatusForbidden, errForbidden}
	errBatchInternal  = &batchError{http.StatusInternalServerError, "internal error"}
)

func (e *batchError) write(w http.ResponseWriter) { http.Error(w, e.msg, e.status) }

// batchEdit is a request that passed the input checks (rules 2-7 of the
// contract): ids are positive and deduplicated, the description is trimmed.
type batchEdit struct {
	ids         []int64
	description *string // nil = unchanged
	categoryID  *int64  // nil = unchanged
}

// dedupeIDs drops repeated ids, keeping the first occurrence's position.
func dedupeIDs(ids []int64) []int64 {
	seen := make(map[int64]struct{}, len(ids))
	out := make([]int64, 0, len(ids))
	for _, id := range ids {
		if _, dup := seen[id]; dup {
			continue
		}
		seen[id] = struct{}{}
		out = append(out, id)
	}
	return out
}

// normalizeEdit runs the request-only checks, in contract order, before any
// database access.
func normalizeEdit(req batchEditRequest) (batchEdit, *batchError) {
	if len(req.IDs) == 0 {
		return batchEdit{}, badBatch(errBatchIDsRequired)
	}
	if req.Description == nil && req.CategoryID == nil {
		return batchEdit{}, badBatch(errBatchNothingToUpdate)
	}
	for _, id := range req.IDs {
		if id <= 0 {
			return batchEdit{}, badBatch(errBatchInvalidID)
		}
	}
	ids := dedupeIDs(req.IDs)
	if len(ids) > maxBatchIDs {
		return batchEdit{}, badBatch(errBatchTooManyIDs)
	}
	edit := batchEdit{ids: ids}
	if req.Description != nil {
		d := strings.TrimSpace(*req.Description)
		if d == "" {
			return batchEdit{}, badBatch(errBatchEmptyDescription)
		}
		// Bytes, like every other description write (validateLength).
		if err := validateLength("description", d, 500); err != nil {
			return batchEdit{}, badBatch(err.Error())
		}
		edit.description = &d
	}
	if req.CategoryID != nil {
		if *req.CategoryID <= 0 {
			return batchEdit{}, badBatch(errBatchInvalidCategory)
		}
		c := *req.CategoryID
		edit.categoryID = &c
	}
	return edit, nil
}

// changes reports whether applying the edit would change the row. Rows it
// would not change are never written, so their sync_version stays put.
func (e batchEdit) changes(row editRow) bool {
	if e.description != nil && (!row.description.Valid || row.description.String != *e.description) {
		return true
	}
	if e.categoryID != nil && row.categoryID != *e.categoryID {
		return true
	}
	return false
}

// split partitions rows into the ids the edit changes and the unchanged count.
func (e batchEdit) split(rows []editRow) (changed []int64, unchanged int) {
	for _, row := range rows {
		if e.changes(row) {
			changed = append(changed, row.id)
		} else {
			unchanged++
		}
	}
	return changed, unchanged
}

// editTable is one of the two tables a batch edit may write. Only the
// package-level values below exist; never build one from request input, the
// fields are interpolated into SQL.
type editTable struct {
	name           string
	scheduledIDCol string // "scheduled_id", or "NULL" where the column doesn't exist
}

var (
	editTransactions = editTable{name: "transactions", scheduledIDCol: "scheduled_id"}
	editScheduled    = editTable{name: "scheduled_transactions", scheduledIDCol: "NULL"}
)

// editRow is the slice of a transaction or schedule a batch edit reads.
// ownerID is the row's user_id, which mirrors the account owner.
type editRow struct {
	id, accountID, ownerID, categoryID int64
	typ                                string
	description                        sql.NullString
	scheduledID                        sql.NullInt64 // always NULL for schedules
}

// loadEditRows returns the live (deleted_at IS NULL) rows among ids, ordered
// by id. A Scan or iteration error is returned, never skipped: a partially
// read selection must not be written.
func loadEditRows(tx *sql.Tx, table editTable, ids []int64) ([]editRow, error) {
	if len(ids) == 0 {
		return nil, nil
	}
	rs, err := tx.Query(
		`SELECT id, account_id, user_id, category_id, type, description, `+table.scheduledIDCol+`
		 FROM `+table.name+`
		 WHERE deleted_at IS NULL AND id IN (`+sqlInPlaceholders(len(ids))+`)
		 ORDER BY id`,
		idsToArgs(ids)...,
	)
	if err != nil {
		return nil, err
	}
	defer rs.Close()
	var out []editRow
	for rs.Next() {
		var row editRow
		if err := rs.Scan(&row.id, &row.accountID, &row.ownerID, &row.categoryID, &row.typ,
			&row.description, &row.scheduledID); err != nil {
			return nil, err
		}
		out = append(out, row)
	}
	if err := rs.Err(); err != nil {
		return nil, err
	}
	return out, nil
}

// checkEditAccess runs checkAccountAccess on every distinct account the rows
// live in. One inaccessible account rejects the whole request.
func checkEditAccess(tx *sql.Tx, userID int64, rows []editRow) *batchError {
	checked := map[int64]bool{}
	for _, row := range rows {
		if checked[row.accountID] {
			continue
		}
		if _, err := checkAccountAccess(userID, row.accountID, tx); err != nil {
			if errors.Is(err, errAccessCheck) {
				return errBatchInternal
			}
			return errBatchForbidden
		}
		checked[row.accountID] = true
	}
	return nil
}

// liveCategoryType returns the type of categoryID if it is a live category
// owned by ownerID.
func liveCategoryType(tx *sql.Tx, categoryID, ownerID int64) (string, *batchError) {
	var typ string
	err := tx.QueryRow(
		`SELECT type FROM categories WHERE id = ? AND user_id = ? AND deleted_at IS NULL`,
		categoryID, ownerID,
	).Scan(&typ)
	if errors.Is(err, sql.ErrNoRows) {
		return "", badBatch(errBatchCategoryNotFound)
	}
	if err != nil {
		log.Printf("batch edit: category lookup: %v", err)
		return "", errBatchInternal
	}
	return typ, nil
}

// validateEditCategory runs rule 10 for a category change: the rows share
// one owner, the category is a live category of that owner, the rows share
// one type and it is the category's type. Returns the owner and the category
// type, which the linked-schedule checks reuse.
func validateEditCategory(tx *sql.Tx, rows []editRow, categoryID int64) (int64, string, *batchError) {
	owner := rows[0].ownerID
	for _, row := range rows {
		if row.ownerID != owner {
			return 0, "", badBatch(errBatchMultiOwner)
		}
	}
	catType, berr := liveCategoryType(tx, categoryID, owner)
	if berr != nil {
		return 0, "", berr
	}
	rowType := rows[0].typ
	for _, row := range rows {
		if row.typ != rowType {
			return 0, "", badBatch(errBatchMixedTypes)
		}
	}
	if rowType != catType {
		return 0, "", badBatch(errBatchTypeMismatch)
	}
	return owner, catType, nil
}

// batchEditPlan is the outcome of rules 8-10 on one table.
type batchEditPlan struct {
	rows    []editRow // live rows, ordered by id
	skipped int       // requested ids that are missing or soft-deleted
	owner   int64     // only set when the edit changes the category
	catType string    // only set when the edit changes the category
}

// planBatchEdit loads the live rows and runs the access and category checks.
// It never writes. An empty plan (no live rows) skips the checks: there is
// nothing they could protect.
func planBatchEdit(tx *sql.Tx, userID int64, table editTable, edit batchEdit) (batchEditPlan, *batchError) {
	rows, err := loadEditRows(tx, table, edit.ids)
	if err != nil {
		log.Printf("batch edit %s: load: %v", table.name, err)
		return batchEditPlan{}, errBatchInternal
	}
	plan := batchEditPlan{rows: rows, skipped: len(edit.ids) - len(rows)}
	if len(rows) == 0 {
		return plan, nil
	}
	if berr := checkEditAccess(tx, userID, rows); berr != nil {
		return batchEditPlan{}, berr
	}
	if edit.categoryID != nil {
		owner, catType, berr := validateEditCategory(tx, rows, *edit.categoryID)
		if berr != nil {
			return batchEditPlan{}, berr
		}
		plan.owner, plan.catType = owner, catType
	}
	return plan, nil
}

// linkedSchedules resolves rule 11: the distinct schedules the live rows were
// materialized from, kept only when live and in an account the caller can
// access. Live means not soft-deleted: the scheduler soft-deletes a finished
// series, and a paused schedule (active = 0) is still live. Everything else
// is counted as skipped, never rejected.
func linkedSchedules(tx *sql.Tx, userID int64, rows []editRow) ([]editRow, int, error) {
	var ids []int64
	seen := map[int64]bool{}
	for _, row := range rows {
		if row.scheduledID.Valid && !seen[row.scheduledID.Int64] {
			seen[row.scheduledID.Int64] = true
			ids = append(ids, row.scheduledID.Int64)
		}
	}
	if len(ids) == 0 {
		return nil, 0, nil
	}
	scheds, err := loadEditRows(tx, editScheduled, ids)
	if err != nil {
		return nil, 0, err
	}
	skipped := len(ids) - len(scheds)
	accessible := map[int64]bool{}
	var live []editRow
	for _, sch := range scheds {
		ok, known := accessible[sch.accountID]
		if !known {
			_, aerr := checkAccountAccess(userID, sch.accountID, tx)
			if errors.Is(aerr, errAccessCheck) {
				return nil, 0, aerr
			}
			ok = aerr == nil
			accessible[sch.accountID] = ok
		}
		if ok {
			live = append(live, sch)
		} else {
			skipped++
		}
	}
	return live, skipped, nil
}

// validateScheduleCategory: a category change carried over to the linked
// schedules must fit each of them (same owner, same type).
func validateScheduleCategory(scheds []editRow, owner int64, catType string) *batchError {
	for _, sch := range scheds {
		if sch.ownerID != owner {
			return badBatch(errBatchScheduleOwner)
		}
	}
	for _, sch := range scheds {
		if sch.typ != catType {
			return badBatch(errBatchScheduleType)
		}
	}
	return nil
}

// applyEdit writes the edit to ids, which must already be narrowed to the
// rows it changes. Only description and category_id are touched. The UPDATE
// re-asserts deleted_at IS NULL and must affect exactly len(ids) rows; any
// other count means the selection moved under us, and the caller rolls back.
// (RowsAffected excludes the sync_version trigger's own UPDATE.)
func applyEdit(tx *sql.Tx, table editTable, edit batchEdit, ids []int64) error {
	if len(ids) == 0 {
		return nil
	}
	var desc, cat any // nil binds NULL, which COALESCE turns into "keep"
	if edit.description != nil {
		desc = *edit.description
	}
	if edit.categoryID != nil {
		cat = *edit.categoryID
	}
	args := append([]any{desc, cat}, idsToArgs(ids)...)
	res, err := tx.Exec(
		`UPDATE `+table.name+`
		 SET description = COALESCE(?, description), category_id = COALESCE(?, category_id)
		 WHERE deleted_at IS NULL AND id IN (`+sqlInPlaceholders(len(ids))+`)`,
		args...,
	)
	if err != nil {
		return err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if n != int64(len(ids)) {
		return fmt.Errorf("%s: %d rows affected, want %d", table.name, n, len(ids))
	}
	return nil
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(v); err != nil {
		log.Printf("write json: %v", err)
	}
}

// handleBatchUpdateTransactions renames and/or recategorizes many entries in
// one transaction (POST /api/transactions/batch-update). Every check runs
// before the first write; see the contract in openapi.yaml. With
// update_schedules it carries the same edit over to the live schedules the
// entries came from, so future occurrences follow.
func (s *Server) handleBatchUpdateTransactions(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	var req batchEditRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, errBatchInvalidBody, http.StatusBadRequest)
		return
	}
	edit, berr := normalizeEdit(req)
	if berr != nil {
		berr.write(w)
		return
	}

	tx, err := s.DB.Begin()
	if err != nil {
		errBatchInternal.write(w)
		return
	}
	defer tx.Rollback()

	plan, berr := planBatchEdit(tx, userID, editTransactions, edit)
	if berr != nil {
		berr.write(w)
		return
	}
	resp := batchUpdateTransactionsResponse{Skipped: plan.skipped}
	if len(plan.rows) == 0 {
		writeJSON(w, http.StatusOK, resp)
		return
	}

	var scheds []editRow
	if req.UpdateSchedules {
		scheds, resp.SchedulesSkipped, err = linkedSchedules(tx, userID, plan.rows)
		if err != nil {
			log.Printf("batch edit transactions: linked schedules: %v", err)
			errBatchInternal.write(w)
			return
		}
		if edit.categoryID != nil {
			if berr := validateScheduleCategory(scheds, plan.owner, plan.catType); berr != nil {
				berr.write(w)
				return
			}
		}
	}

	changed, unchanged := edit.split(plan.rows)
	schedChanged, schedUnchanged := edit.split(scheds)
	if err := applyEdit(tx, editTransactions, edit, changed); err != nil {
		log.Printf("batch edit transactions: %v", err)
		errBatchInternal.write(w)
		return
	}
	if err := applyEdit(tx, editScheduled, edit, schedChanged); err != nil {
		log.Printf("batch edit transactions: linked schedules: %v", err)
		errBatchInternal.write(w)
		return
	}
	if err := tx.Commit(); err != nil {
		log.Printf("batch edit transactions: commit: %v", err)
		errBatchInternal.write(w)
		return
	}

	resp.Updated, resp.Unchanged = len(changed), unchanged
	resp.SchedulesUpdated, resp.SchedulesUnchanged = len(schedChanged), schedUnchanged
	writeJSON(w, http.StatusOK, resp)
}

// handleBatchUpdateScheduled renames and/or recategorizes many schedules
// (POST /api/scheduled/batch-update). Same rules as the transactions
// endpoint; only description and category_id are written, never
// next_occurrence, rrule, amount or the occurrence counters.
func (s *Server) handleBatchUpdateScheduled(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	var req batchEditRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, errBatchInvalidBody, http.StatusBadRequest)
		return
	}
	edit, berr := normalizeEdit(req)
	if berr != nil {
		berr.write(w)
		return
	}

	tx, err := s.DB.Begin()
	if err != nil {
		errBatchInternal.write(w)
		return
	}
	defer tx.Rollback()

	plan, berr := planBatchEdit(tx, userID, editScheduled, edit)
	if berr != nil {
		berr.write(w)
		return
	}
	resp := batchEditCounts{Skipped: plan.skipped}
	if len(plan.rows) == 0 {
		writeJSON(w, http.StatusOK, resp)
		return
	}

	changed, unchanged := edit.split(plan.rows)
	if err := applyEdit(tx, editScheduled, edit, changed); err != nil {
		log.Printf("batch edit scheduled: %v", err)
		errBatchInternal.write(w)
		return
	}
	if err := tx.Commit(); err != nil {
		log.Printf("batch edit scheduled: commit: %v", err)
		errBatchInternal.write(w)
		return
	}

	resp.Updated, resp.Unchanged = len(changed), unchanged
	writeJSON(w, http.StatusOK, resp)
}

// handleBatchUpdateCategory is the pre-1.27 bulk category change.
//
// Deprecated: use POST /api/transactions/batch-update. Kept for older web
// builds with its historic contract (204; 403 when the entries span several
// owners), plus the checks it lacked: the category type must match every
// entry, ids are deduplicated and capped, and read errors abort.
func (s *Server) handleBatchUpdateCategory(w http.ResponseWriter, r *http.Request) {
	userID := UserIDFromContext(r.Context())

	var req batchUpdateCategoryRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, errBatchInvalidBody, http.StatusBadRequest)
		return
	}
	if len(req.IDs) == 0 || req.CategoryID == 0 {
		http.Error(w, "ids and category_id required", http.StatusBadRequest)
		return
	}
	ids := dedupeIDs(req.IDs)
	if len(ids) > maxBatchIDs {
		http.Error(w, errBatchTooManyIDs, http.StatusBadRequest)
		return
	}

	tx, err := s.DB.Begin()
	if err != nil {
		errBatchInternal.write(w)
		return
	}
	defer tx.Rollback()

	rows, err := loadEditRows(tx, editTransactions, ids)
	if err != nil {
		log.Printf("batch update category: load: %v", err)
		errBatchInternal.write(w)
		return
	}
	if len(rows) == 0 {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if berr := checkEditAccess(tx, userID, rows); berr != nil {
		berr.write(w)
		return
	}
	owner := rows[0].ownerID
	for _, row := range rows {
		if row.ownerID != owner {
			http.Error(w, "forbidden: transactions span multiple owners", http.StatusForbidden)
			return
		}
	}
	catType, berr := liveCategoryType(tx, req.CategoryID, owner)
	if berr != nil {
		berr.write(w)
		return
	}
	for _, row := range rows {
		if row.typ != catType {
			http.Error(w, errBatchTypeMismatch, http.StatusBadRequest)
			return
		}
	}

	edit := batchEdit{categoryID: &req.CategoryID}
	changed, _ := edit.split(rows)
	if err := applyEdit(tx, editTransactions, edit, changed); err != nil {
		log.Printf("batch update category: %v", err)
		errBatchInternal.write(w)
		return
	}
	if err := tx.Commit(); err != nil {
		log.Printf("batch update category: commit: %v", err)
		errBatchInternal.write(w)
		return
	}

	w.WriteHeader(http.StatusNoContent)
}
