package api

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"reflect"
	"sort"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/pstivanin/insitu-ledger/backend/internal/scheduler"
)

// Tests for POST /api/transactions/batch-update, POST /api/scheduled/batch-update
// and the legacy POST /api/transactions/batch-update-category.
//
// Data corruption is the concern, so every rejected request asserts a
// byte-identical database (dbSnapshot) and every accepted one asserts that
// exactly the expected rows changed, only in description / category_id (plus
// the trigger-maintained sync_version / updated_at).

// The frozen client-facing messages, spelled out independently of the
// handler's constants so a silent rewording fails here.
const (
	msgInvalidBody      = "invalid request body"
	msgIDsRequired      = "ids required"
	msgNothingToUpdate  = "nothing to update: set description and/or category_id"
	msgInvalidID        = "invalid id"
	msgTooManyIDs       = "too many ids (max 5000)"
	msgEmptyDescription = "description must not be empty"
	msgDescriptionLong  = "description must not exceed 500 characters"
	msgInvalidCategory  = "invalid category_id"
	msgForbidden        = "forbidden: no shared access"
	msgMultiOwner       = "selected entries belong to different owners; change the category for one owner at a time"
	msgCategoryNotFound = "category not found"
	msgMixedTypes       = "selected entries mix income and expense; change the category for one type at a time"
	msgTypeMismatch     = "category type does not match the selected entries"
	msgScheduleOwner    = "a linked recurring schedule belongs to a different owner"
	msgScheduleType     = "a linked recurring schedule has a different type than the new category"
	msgLegacyMultiOwner = "forbidden: transactions span multiple owners"
)

const (
	pathTxnBatch    = "/api/transactions/batch-update"
	pathSchedBatch  = "/api/scheduled/batch-update"
	pathLegacyBatch = "/api/transactions/batch-update-category"
)

// ===== DB snapshots =====

// dbSnapshot is table -> row id -> column -> quote(value). SQLite's quote()
// keeps the storage class, so 1, 1.0, '1' and NULL all compare different.
type dbSnapshot map[string]map[int64]map[string]string

// snapshotTables is every table a batch edit could conceivably touch.
var snapshotTables = []string{
	"transactions", "scheduled_transactions", "accounts", "categories",
	"sync_meta", "shared_account_access", "users",
}

func takeSnapshot(t *testing.T, db *sql.DB) dbSnapshot {
	t.Helper()
	snap := dbSnapshot{}
	for _, table := range snapshotTables {
		crs, err := db.Query(`SELECT name FROM pragma_table_info(?)`, table)
		if err != nil {
			t.Fatalf("columns of %s: %v", table, err)
		}
		var cols []string
		for crs.Next() {
			var c string
			if err := crs.Scan(&c); err != nil {
				t.Fatalf("scan column of %s: %v", table, err)
			}
			cols = append(cols, c)
		}
		crs.Close()
		if err := crs.Err(); err != nil || len(cols) == 0 {
			t.Fatalf("columns of %s: %v (%d found)", table, err, len(cols))
		}

		exprs := make([]string, len(cols))
		for i, c := range cols {
			exprs[i] = "quote(" + c + ")"
		}
		rs, err := db.Query(`SELECT id, ` + strings.Join(exprs, ", ") + ` FROM ` + table)
		if err != nil {
			t.Fatalf("snapshot %s: %v", table, err)
		}
		rows := map[int64]map[string]string{}
		for rs.Next() {
			var id int64
			vals := make([]string, len(cols))
			dest := []any{&id}
			for i := range vals {
				dest = append(dest, &vals[i])
			}
			if err := rs.Scan(dest...); err != nil {
				t.Fatalf("snapshot %s scan: %v", table, err)
			}
			row := make(map[string]string, len(cols))
			for i, c := range cols {
				row[c] = vals[i]
			}
			rows[id] = row
		}
		rs.Close()
		if err := rs.Err(); err != nil {
			t.Fatalf("snapshot %s: %v", table, err)
		}
		snap[table] = rows
	}
	return snap
}

type cellChange struct {
	table, col    string
	id            int64
	before, after string
}

func (c cellChange) String() string {
	return fmt.Sprintf("%s#%d.%s: %s -> %s", c.table, c.id, c.col, c.before, c.after)
}

// diffSnapshots returns every changed cell plus the rows that appeared or
// vanished, sorted for stable output.
func diffSnapshots(before, after dbSnapshot) ([]cellChange, []string) {
	var cells []cellChange
	var rows []string
	for table, bRows := range before {
		aRows := after[table]
		for id, b := range bRows {
			a, ok := aRows[id]
			if !ok {
				rows = append(rows, fmt.Sprintf("%s#%d vanished", table, id))
				continue
			}
			for col, bv := range b {
				if av := a[col]; av != bv {
					cells = append(cells, cellChange{table: table, col: col, id: id, before: bv, after: av})
				}
			}
		}
		for id := range aRows {
			if _, ok := bRows[id]; !ok {
				rows = append(rows, fmt.Sprintf("%s#%d appeared", table, id))
			}
		}
	}
	sort.Slice(cells, func(i, j int) bool {
		if cells[i].table != cells[j].table {
			return cells[i].table < cells[j].table
		}
		if cells[i].id != cells[j].id {
			return cells[i].id < cells[j].id
		}
		return cells[i].col < cells[j].col
	})
	sort.Strings(rows)
	return cells, rows
}

func formatDiff(cells []cellChange, rows []string) string {
	var b strings.Builder
	for _, r := range rows {
		b.WriteString("  " + r + "\n")
	}
	for _, c := range cells {
		b.WriteString("  " + c.String() + "\n")
	}
	return b.String()
}

func assertNoChanges(t *testing.T, label string, before, after dbSnapshot) {
	t.Helper()
	cells, rows := diffSnapshots(before, after)
	if len(cells)+len(rows) > 0 {
		t.Fatalf("%s: expected zero changes, got:\n%s", label, formatDiff(cells, rows))
	}
}

// editableColumns are the only columns a batch edit may change. The update
// trigger maintains sync_version and updated_at.
var editableColumns = map[string]bool{
	"description": true, "category_id": true, "sync_version": true, "updated_at": true,
}

func mustAtoi(t *testing.T, s string) int64 {
	t.Helper()
	n, err := strconv.ParseInt(s, 10, 64)
	if err != nil {
		t.Fatalf("not an integer: %q", s)
	}
	return n
}

// assertOnlyEdited checks a successful batch edit: exactly txnIDs and
// schedIDs changed, only in editable columns, each in description or
// category_id and with a fresh sync_version, nothing appeared or vanished,
// and sync_meta advanced exactly once per written row.
func assertOnlyEdited(t *testing.T, before, after dbSnapshot, txnIDs, schedIDs []int64) {
	t.Helper()
	want := map[string]map[int64]bool{"transactions": {}, "scheduled_transactions": {}}
	for _, id := range txnIDs {
		want["transactions"][id] = true
	}
	for _, id := range schedIDs {
		want["scheduled_transactions"][id] = true
	}
	cells, rows := diffSnapshots(before, after)
	if len(rows) > 0 {
		t.Fatalf("rows appeared or vanished:\n%s", formatDiff(nil, rows))
	}
	bumped := map[string]map[int64]bool{"transactions": {}, "scheduled_transactions": {}}
	valued := map[string]map[int64]bool{"transactions": {}, "scheduled_transactions": {}}
	for _, c := range cells {
		if c.table == "sync_meta" {
			continue
		}
		if !want[c.table][c.id] {
			t.Errorf("unexpected change %s", c)
			continue
		}
		if !editableColumns[c.col] {
			t.Errorf("non-editable column changed: %s", c)
		}
		switch c.col {
		case "sync_version":
			bumped[c.table][c.id] = true
		case "description", "category_id":
			valued[c.table][c.id] = true
		}
	}
	oldVersion := mustAtoi(t, before["sync_meta"][1]["version"])
	newVersion := mustAtoi(t, after["sync_meta"][1]["version"])
	for table, ids := range want {
		for id := range ids {
			if !valued[table][id] {
				t.Errorf("%s#%d: expected a new description or category, found none", table, id)
			}
			if !bumped[table][id] {
				t.Errorf("%s#%d: sync_version not bumped", table, id)
			} else if v := mustAtoi(t, after[table][id]["sync_version"]); v <= oldVersion {
				t.Errorf("%s#%d: sync_version %d not above the pre-edit version %d", table, id, v, oldVersion)
			}
		}
	}
	if got, wantDelta := newVersion-oldVersion, int64(len(want["transactions"])+len(want["scheduled_transactions"])); got != wantDelta {
		t.Errorf("sync_meta advanced by %d, want %d (one per written row)", got, wantDelta)
	}
	if t.Failed() {
		t.FailNow()
	}
}

func sqlQuote(s string) string { return "'" + strings.ReplaceAll(s, "'", "''") + "'" }
func sqlInt(n int64) string    { return strconv.FormatInt(n, 10) }
func strp(s string) *string    { return &s }

// ===== Fixture =====

// batchFix is a server with two users and a fixed set of accounts and
// categories, seeded straight into the DB so tests control every column:
//
//	admin owns shared (shared with guest) and private
//	guest owns guestAcct
type batchFix struct {
	t                *testing.T
	s                *Server
	h                http.Handler
	adminID, guestID int64
	admin, guest     string // bearer tokens

	shared, private, guestAcct int64

	food, transport, salary, bonus, deletedCat int64 // admin's categories
	guestFood, guestSalary                     int64 // guest's categories
}

func newBatchFixture(t *testing.T) *batchFix {
	t.Helper()
	s, cleanup := setupTestServer(t)
	t.Cleanup(cleanup)
	f := &batchFix{t: t, s: s, h: NewRouter(s)}
	if err := s.DB.QueryRow(`SELECT id FROM users WHERE username = 'admin'`).Scan(&f.adminID); err != nil {
		t.Fatalf("admin id: %v", err)
	}
	f.guestID = f.exec(`INSERT INTO users (username, email, name, password_hash) VALUES ('guest', 'guest@test.com', 'Guest', 'x')`)
	f.admin = f.token(f.adminID)
	f.guest = f.token(f.guestID)

	f.shared = f.account(f.adminID, "Shared")
	f.private = f.account(f.adminID, "Private")
	f.guestAcct = f.account(f.guestID, "Guest")
	f.exec(`INSERT INTO shared_account_access (owner_user_id, guest_user_id, account_id) VALUES (?, ?, ?)`,
		f.adminID, f.guestID, f.shared)

	f.food = f.category(f.adminID, "Food", "expense", false)
	f.transport = f.category(f.adminID, "Transport", "expense", false)
	f.salary = f.category(f.adminID, "Salary", "income", false)
	f.bonus = f.category(f.adminID, "Bonus", "income", false)
	f.deletedCat = f.category(f.adminID, "Old", "expense", true)
	f.guestFood = f.category(f.guestID, "Groceries", "expense", false)
	f.guestSalary = f.category(f.guestID, "Pay", "income", false)
	return f
}

func (f *batchFix) exec(query string, args ...any) int64 {
	f.t.Helper()
	res, err := f.s.DB.Exec(query, args...)
	if err != nil {
		f.t.Fatalf("exec %q: %v", query, err)
	}
	id, _ := res.LastInsertId()
	return id
}

func (f *batchFix) token(userID int64) string {
	f.t.Helper()
	tok, err := f.s.AuthStore.CreateToken(userID)
	if err != nil {
		f.t.Fatalf("token: %v", err)
	}
	return tok
}

func (f *batchFix) account(owner int64, name string) int64 {
	return f.exec(`INSERT INTO accounts (user_id, name, balance) VALUES (?, ?, 1000)`, owner, name)
}

func (f *batchFix) category(owner int64, name, typ string, deleted bool) int64 {
	var del any
	if deleted {
		del = "2025-01-01 00:00:00"
	}
	return f.exec(`INSERT INTO categories (user_id, name, type, deleted_at) VALUES (?, ?, ?, ?)`, owner, name, typ, del)
}

// txnSeed describes a transaction row. Zero values get defaults; desc, note,
// clientID and the marker fields are any so nil stores NULL.
type txnSeed struct {
	account, category int64
	typ               string  // default "expense"
	amount            float64 // default 12.5
	currency          string  // default "EUR"
	date              string  // default "2025-03-10T08:41:00+01:00"
	desc, note        any
	createdBy         int64 // 0 = the account owner
	nullCreator       bool  // creator deleted (ON DELETE SET NULL)
	clientID          any
	scheduledID       any
	occurrence        any
	occurrenceTotal   any
	deleted           bool
}

func (f *batchFix) txn(s txnSeed) int64 {
	f.t.Helper()
	if s.typ == "" {
		s.typ = "expense"
	}
	if s.amount == 0 {
		s.amount = 12.5
	}
	if s.currency == "" {
		s.currency = "EUR"
	}
	if s.date == "" {
		s.date = "2025-03-10T08:41:00+01:00"
	}
	var creator any = s.createdBy
	if s.createdBy == 0 {
		creator = f.ownerOf(s.account)
	}
	if s.nullCreator {
		creator = nil
	}
	var del any
	if s.deleted {
		del = "2025-04-01 10:00:00"
	}
	return f.exec(
		`INSERT INTO transactions (account_id, category_id, user_id, created_by_user_id, type, amount, currency,
		                           description, note, date, client_id, scheduled_id, occurrence, occurrence_total,
		                           deleted_at, created_at)
		 VALUES (?, ?, (SELECT user_id FROM accounts WHERE id = ?), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '2020-01-02 03:04:05')`,
		s.account, s.category, s.account, creator, s.typ, s.amount, s.currency,
		s.desc, s.note, s.date, s.clientID, s.scheduledID, s.occurrence, s.occurrenceTotal, del,
	)
}

// schedSeed describes a scheduled_transactions row; zero values get defaults.
type schedSeed struct {
	account, category int64
	typ               string  // default "expense"
	amount            float64 // default 800
	currency          string  // default "EUR"
	rrule             string  // default "FREQ=MONTHLY"
	next              string  // default "2026-12-27T09:00:00+01:00"
	desc, note        any
	maxOcc            any
	count             int64
	createdBy         int64 // 0 = the account owner
	clientID          any
	paused            bool // active = 0, still live
	finished          bool // active = 0 + deleted_at, as the scheduler leaves a finished series
	deleted           bool // deleted_at, as DELETE /api/scheduled/{id} leaves it
}

func (f *batchFix) sched(s schedSeed) int64 {
	f.t.Helper()
	if s.typ == "" {
		s.typ = "expense"
	}
	if s.amount == 0 {
		s.amount = 800
	}
	if s.currency == "" {
		s.currency = "EUR"
	}
	if s.rrule == "" {
		s.rrule = "FREQ=MONTHLY"
	}
	if s.next == "" {
		s.next = "2026-12-27T09:00:00+01:00"
	}
	creator := s.createdBy
	if creator == 0 {
		creator = f.ownerOf(s.account)
	}
	active := 1
	if s.paused || s.finished {
		active = 0
	}
	var del any
	if s.finished || s.deleted {
		del = "2025-05-01 12:00:00"
	}
	return f.exec(
		`INSERT INTO scheduled_transactions (account_id, category_id, user_id, created_by_user_id, type, amount, currency,
		                                     description, note, rrule, next_occurrence, active, max_occurrences,
		                                     occurrence_count, client_id, deleted_at, created_at)
		 VALUES (?, ?, (SELECT user_id FROM accounts WHERE id = ?), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '2020-01-02 03:04:05')`,
		s.account, s.category, s.account, creator, s.typ, s.amount, s.currency,
		s.desc, s.note, s.rrule, s.next, active, s.maxOcc, s.count, s.clientID, del,
	)
}

// materialized seeds occurrence occ of schedID the way the scheduler writes
// it: the schedule's account, category, type, amount, description and
// creator, a sched-<id>-<date> client_id, and the occurrence marker.
func (f *batchFix) materialized(schedID, occ int64) int64 {
	f.t.Helper()
	var acct, cat int64
	var typ string
	var amount float64
	var desc sql.NullString
	var creator, maxOcc sql.NullInt64
	if err := f.s.DB.QueryRow(
		`SELECT account_id, category_id, type, amount, description, created_by_user_id, max_occurrences
		 FROM scheduled_transactions WHERE id = ?`, schedID,
	).Scan(&acct, &cat, &typ, &amount, &desc, &creator, &maxOcc); err != nil {
		f.t.Fatalf("schedule %d: %v", schedID, err)
	}
	date := fmt.Sprintf("2025-%02d-27T09:00:00+01:00", occ)
	seed := txnSeed{
		account: acct, category: cat, typ: typ, amount: amount, date: date,
		clientID:    fmt.Sprintf("sched-%d-%s", schedID, date),
		scheduledID: schedID, occurrence: occ,
	}
	if desc.Valid {
		seed.desc = desc.String
	}
	if creator.Valid {
		seed.createdBy = creator.Int64
	} else {
		seed.nullCreator = true
	}
	if maxOcc.Valid {
		seed.occurrenceTotal = maxOcc.Int64
	}
	return f.txn(seed)
}

func (f *batchFix) ownerOf(account int64) int64 {
	f.t.Helper()
	var owner int64
	if err := f.s.DB.QueryRow(`SELECT user_id FROM accounts WHERE id = ?`, account).Scan(&owner); err != nil {
		f.t.Fatalf("owner of account %d: %v", account, err)
	}
	return owner
}

// cell returns quote(col) of one row.
func (f *batchFix) cell(table string, id int64, col string) string {
	f.t.Helper()
	var v string
	if err := f.s.DB.QueryRow(`SELECT quote(`+col+`) FROM `+table+` WHERE id = ?`, id).Scan(&v); err != nil {
		f.t.Fatalf("%s#%d.%s: %v", table, id, col, err)
	}
	return v
}

func (f *batchFix) expectCell(table string, id int64, col, want string) {
	f.t.Helper()
	if got := f.cell(table, id, col); got != want {
		f.t.Errorf("%s#%d.%s = %s, want %s", table, id, col, got, want)
	}
}

func (f *batchFix) syncVersion() int64 {
	f.t.Helper()
	var v int64
	if err := f.s.DB.QueryRow(`SELECT version FROM sync_meta WHERE id = 1`).Scan(&v); err != nil {
		f.t.Fatalf("sync_meta: %v", err)
	}
	return v
}

func (f *batchFix) post(path, token, body string) *httptest.ResponseRecorder {
	w := httptest.NewRecorder()
	f.h.ServeHTTP(w, authedRequest("POST", path, body, token))
	return w
}

// editReq marshals to a batch-update body; nil fields are omitted.
type editReq struct {
	IDs             []int64 `json:"ids"`
	Description     *string `json:"description,omitempty"`
	CategoryID      *int64  `json:"category_id,omitempty"`
	UpdateSchedules bool    `json:"update_schedules,omitempty"`
}

func (r editReq) json() string {
	b, err := json.Marshal(r)
	if err != nil {
		panic(err)
	}
	return string(b)
}

func legacyBody(ids []int64, categoryID int64) string {
	b, _ := json.Marshal(map[string]any{"ids": ids, "category_id": categoryID})
	return string(b)
}

func txnCounts(updated, unchanged, skipped, schedUpdated, schedUnchanged, schedSkipped int) map[string]int {
	return map[string]int{
		"updated": updated, "unchanged": unchanged, "skipped": skipped,
		"schedules_updated": schedUpdated, "schedules_unchanged": schedUnchanged, "schedules_skipped": schedSkipped,
	}
}

func schedCounts(updated, unchanged, skipped int) map[string]int {
	return map[string]int{"updated": updated, "unchanged": unchanged, "skipped": skipped}
}

// expectCounts asserts a 200 JSON response with exactly these keys and values.
func expectCounts(t *testing.T, w *httptest.ResponseRecorder, want map[string]int) {
	t.Helper()
	if w.Code != http.StatusOK {
		t.Fatalf("status %d (%s), want 200", w.Code, strings.TrimSpace(w.Body.String()))
	}
	if ct := w.Header().Get("Content-Type"); ct != "application/json" {
		t.Errorf("Content-Type = %q, want application/json", ct)
	}
	var got map[string]int
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("decode %s: %v", w.Body.String(), err)
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("counts = %v, want %v", got, want)
	}
}

func expectError(t *testing.T, w *httptest.ResponseRecorder, status int, msg string) {
	t.Helper()
	if got := strings.TrimSpace(w.Body.String()); w.Code != status || got != msg {
		t.Fatalf("got %d %q, want %d %q", w.Code, got, status, msg)
	}
}

// expectRejected sends the request and asserts the error and that the
// database is byte-identical afterwards.
func (f *batchFix) expectRejected(path, token, body string, status int, msg string) {
	f.t.Helper()
	before := takeSnapshot(f.t, f.s.DB)
	w := f.post(path, token, body)
	label := fmt.Sprintf("POST %s %.200s", path, body)
	if got := strings.TrimSpace(w.Body.String()); w.Code != status || got != msg {
		f.t.Fatalf("%s: got %d %q, want %d %q", label, w.Code, got, status, msg)
	}
	assertNoChanges(f.t, label, before, takeSnapshot(f.t, f.s.DB))
}

// expectEdited sends the request and asserts the counts and that exactly the
// given rows were edited.
func (f *batchFix) expectEdited(path, token, body string, counts map[string]int, txnIDs, schedIDs []int64) {
	f.t.Helper()
	before := takeSnapshot(f.t, f.s.DB)
	expectCounts(f.t, f.post(path, token, body), counts)
	assertOnlyEdited(f.t, before, takeSnapshot(f.t, f.s.DB), txnIDs, schedIDs)
}

// ===== POST /api/transactions/batch-update: preservation =====

func TestBatchUpdate_PreservesAllOtherColumns(t *testing.T) {
	f := newBatchFixture(t)
	sid := f.sched(schedSeed{account: f.shared, category: f.food, desc: "Rent", maxOcc: int64(12), count: 2, createdBy: f.guestID})
	date := "2026-06-11T08:41:00+02:00"
	// A scheduler-made entry from the guest's schedule in the admin's shared
	// account, with every optional column populated.
	id := f.txn(txnSeed{
		account: f.shared, category: f.food, desc: "Rent", note: "keys + deposit", amount: 812.34, currency: "USD",
		date: date, createdBy: f.guestID, clientID: fmt.Sprintf("sched-%d-%s", sid, date),
		scheduledID: sid, occurrence: int64(2), occurrenceTotal: int64(12),
	})
	f.txn(txnSeed{account: f.shared, category: f.food, desc: "Rent"})

	before := takeSnapshot(t, f.s.DB)
	w := f.post(pathTxnBatch, f.admin, editReq{IDs: []int64{id}, Description: strp("Rent (flat)"), CategoryID: int64p(f.transport)}.json())
	expectCounts(t, w, txnCounts(1, 0, 0, 0, 0, 0))
	after := takeSnapshot(t, f.s.DB)
	assertOnlyEdited(t, before, after, []int64{id}, nil)

	was, got := before["transactions"][id], after["transactions"][id]
	if got["description"] != sqlQuote("Rent (flat)") || got["category_id"] != sqlInt(f.transport) {
		t.Fatalf("edit not applied: description %s, category %s", got["description"], got["category_id"])
	}
	if was["date"] != sqlQuote(date) || was["amount"] != "812.34" || was["occurrence"] != "2" || was["deleted_at"] != "NULL" {
		t.Fatalf("fixture not as intended: %v", was)
	}
	for _, col := range []string{
		"account_id", "user_id", "created_by_user_id", "type", "amount", "currency", "note", "date",
		"client_id", "scheduled_id", "occurrence", "occurrence_total", "created_at", "deleted_at",
	} {
		if got[col] != was[col] {
			t.Errorf("%s changed: %s -> %s", col, was[col], got[col])
		}
	}
}

func TestBatchUpdate_BalancesUnchanged(t *testing.T) {
	f := newBatchFixture(t)
	// Created through the API so the balances are the real, maintained ones.
	var ids []int64
	for i, acct := range []int64{f.shared, f.private, f.private} {
		w := f.post("/api/transactions", f.admin, fmt.Sprintf(
			`{"account_id":%d,"category_id":%d,"type":"expense","amount":%d.25,"description":"Old","date":"2025-01-%02d"}`,
			acct, f.food, 10*(i+1), i+1))
		if w.Code != 201 {
			t.Fatalf("create: %d %s", w.Code, w.Body.String())
		}
		var out map[string]any
		json.Unmarshal(w.Body.Bytes(), &out)
		ids = append(ids, int64(out["id"].(float64)))
	}
	balance := func(acct int64) string { return f.cell("accounts", acct, "balance") }
	shared0, private0 := balance(f.shared), balance(f.private)
	if private0 == "1000.0" {
		t.Fatalf("fixture: creating entries did not move the balance (%s)", private0)
	}

	f.expectEdited(pathTxnBatch, f.admin,
		editReq{IDs: ids, Description: strp("New"), CategoryID: int64p(f.transport)}.json(),
		txnCounts(3, 0, 0, 0, 0, 0), ids, nil)

	if balance(f.shared) != shared0 || balance(f.private) != private0 {
		t.Errorf("balances moved: shared %s -> %s, private %s -> %s",
			shared0, balance(f.shared), private0, balance(f.private))
	}
}

func TestBatchUpdate_RowsOutsideIDsUntouched(t *testing.T) {
	f := newBatchFixture(t)
	target := f.txn(txnSeed{account: f.private, category: f.food, desc: "Stipendio"})
	f.txn(txnSeed{account: f.private, category: f.food, desc: "Stipendio Lisa"})
	f.txn(txnSeed{account: f.private, category: f.food, desc: "stipendio"})
	f.txn(txnSeed{account: f.shared, category: f.food, desc: "Stipendio"})
	f.txn(txnSeed{account: f.guestAcct, category: f.guestFood, desc: "Stipendio"})
	f.txn(txnSeed{account: f.private, category: f.food, desc: "Stipendio", deleted: true})
	f.sched(schedSeed{account: f.private, category: f.food, desc: "Stipendio"})

	f.expectEdited(pathTxnBatch, f.admin,
		editReq{IDs: []int64{target}, Description: strp("Stipendio Paolo"), CategoryID: int64p(f.transport), UpdateSchedules: true}.json(),
		txnCounts(1, 0, 0, 0, 0, 0), []int64{target}, nil)
}

// ===== Field changes =====

func TestBatchUpdate_RenameOnly(t *testing.T) {
	f := newBatchFixture(t)
	named := f.txn(txnSeed{account: f.private, category: f.food, desc: "Old"})
	unnamed := f.txn(txnSeed{account: f.private, category: f.food})

	f.expectEdited(pathTxnBatch, f.admin, editReq{IDs: []int64{named, unnamed}, Description: strp("New")}.json(),
		txnCounts(2, 0, 0, 0, 0, 0), []int64{named, unnamed}, nil)
	for _, id := range []int64{named, unnamed} {
		f.expectCell("transactions", id, "description", sqlQuote("New"))
		f.expectCell("transactions", id, "category_id", sqlInt(f.food))
	}
}

func TestBatchUpdate_CategoryOnly(t *testing.T) {
	f := newBatchFixture(t)
	named := f.txn(txnSeed{account: f.private, category: f.food, desc: "Keep"})
	unnamed := f.txn(txnSeed{account: f.private, category: f.food})

	f.expectEdited(pathTxnBatch, f.admin, editReq{IDs: []int64{named, unnamed}, CategoryID: int64p(f.transport)}.json(),
		txnCounts(2, 0, 0, 0, 0, 0), []int64{named, unnamed}, nil)
	f.expectCell("transactions", named, "description", sqlQuote("Keep"))
	f.expectCell("transactions", unnamed, "description", "NULL") // stays NULL, not ''
	for _, id := range []int64{named, unnamed} {
		f.expectCell("transactions", id, "category_id", sqlInt(f.transport))
	}
}

func TestBatchUpdate_Both(t *testing.T) {
	f := newBatchFixture(t)
	a := f.txn(txnSeed{account: f.private, category: f.food, desc: "Old"})
	b := f.txn(txnSeed{account: f.shared, category: f.transport, desc: "Older"})
	onlyCat := f.txn(txnSeed{account: f.private, category: f.food, desc: "New"})

	f.expectEdited(pathTxnBatch, f.admin,
		editReq{IDs: []int64{a, b, onlyCat}, Description: strp("New"), CategoryID: int64p(f.transport)}.json(),
		txnCounts(3, 0, 0, 0, 0, 0), []int64{a, b, onlyCat}, nil)
	for _, id := range []int64{a, b, onlyCat} {
		f.expectCell("transactions", id, "description", sqlQuote("New"))
		f.expectCell("transactions", id, "category_id", sqlInt(f.transport))
	}
}

func TestBatchUpdate_TrimsDescription(t *testing.T) {
	f := newBatchFixture(t)
	old := f.txn(txnSeed{account: f.private, category: f.food, desc: "Old"})
	same := f.txn(txnSeed{account: f.private, category: f.food, desc: "Stipendio"})
	lower := f.txn(txnSeed{account: f.private, category: f.food, desc: "stipendio"})

	// Stored trimmed; the row that already equals the trimmed value is
	// unchanged, and case differences are real changes.
	f.expectEdited(pathTxnBatch, f.admin,
		editReq{IDs: []int64{old, same, lower}, Description: strp("  Stipendio \t\n")}.json(),
		txnCounts(2, 1, 0, 0, 0, 0), []int64{old, lower}, nil)
	for _, id := range []int64{old, same, lower} {
		f.expectCell("transactions", id, "description", sqlQuote("Stipendio"))
	}
}

// ===== Input validation =====

func TestBatchUpdate_EmptyOrWhitespace400(t *testing.T) {
	f := newBatchFixture(t)
	id := f.txn(txnSeed{account: f.private, category: f.food, desc: "Old"})
	for _, d := range []string{"", " ", "   ", "\t\n ", "  "} {
		f.expectRejected(pathTxnBatch, f.admin, editReq{IDs: []int64{id}, Description: strp(d)}.json(), 400, msgEmptyDescription)
		f.expectRejected(pathTxnBatch, f.admin, editReq{IDs: []int64{id}, Description: strp(d), CategoryID: int64p(f.transport)}.json(), 400, msgEmptyDescription)
	}
}

func TestBatchUpdate_500ByteBoundary(t *testing.T) {
	f := newBatchFixture(t)
	id := f.txn(txnSeed{account: f.private, category: f.food, desc: "Old"})

	// Each accepted value differs from the previous one, so each is a write.
	for _, d := range []string{
		strings.Repeat("a", 500),
		strings.Repeat("è", 250), // 250 characters, 500 bytes
		strings.Repeat("è", 249) + "ab",
		"  " + strings.Repeat("b", 500) + " \t", // the limit applies after trimming
	} {
		f.expectEdited(pathTxnBatch, f.admin, editReq{IDs: []int64{id}, Description: strp(d)}.json(),
			txnCounts(1, 0, 0, 0, 0, 0), []int64{id}, nil)
		f.expectCell("transactions", id, "description", sqlQuote(strings.TrimSpace(d)))
	}
	for _, d := range []string{
		strings.Repeat("a", 501),
		strings.Repeat("è", 250) + "a", // 501 bytes
		strings.Repeat("è", 251),       // 251 characters, 502 bytes: the limit is bytes
	} {
		f.expectRejected(pathTxnBatch, f.admin, editReq{IDs: []int64{id}, Description: strp(d)}.json(), 400, msgDescriptionLong)
	}
}

func TestBatchUpdate_NothingToUpdate400(t *testing.T) {
	f := newBatchFixture(t)
	id := f.txn(txnSeed{account: f.private, category: f.food, desc: "Old"})
	for _, body := range []string{
		fmt.Sprintf(`{"ids":[%d]}`, id),
		fmt.Sprintf(`{"ids":[%d],"description":null}`, id),
		fmt.Sprintf(`{"ids":[%d],"description":null,"category_id":null}`, id),
		fmt.Sprintf(`{"ids":[%d],"update_schedules":true}`, id),
		`{"ids":[0]}`, // rule 3 runs before the id check
	} {
		f.expectRejected(pathTxnBatch, f.admin, body, 400, msgNothingToUpdate)
	}
}

func TestBatchUpdate_IdsRequired400(t *testing.T) {
	f := newBatchFixture(t)
	f.txn(txnSeed{account: f.private, category: f.food, desc: "Old"})
	for _, body := range []string{
		`{}`,
		`{"ids":[]}`,
		`{"ids":null,"description":"x"}`,
		`{"ids":[],"description":"x","category_id":1}`,
		`{"description":"x"}`,
	} {
		f.expectRejected(pathTxnBatch, f.admin, body, 400, msgIDsRequired)
	}
}

func TestBatchUpdate_InvalidID400(t *testing.T) {
	f := newBatchFixture(t)
	id := f.txn(txnSeed{account: f.private, category: f.food, desc: "Old"})
	for _, ids := range [][]int64{{0}, {-1}, {id, 0}, {id, -7}, {0, id}} {
		f.expectRejected(pathTxnBatch, f.admin, editReq{IDs: ids, Description: strp("New")}.json(), 400, msgInvalidID)
	}
}

func TestBatchUpdate_InvalidCategoryID400(t *testing.T) {
	f := newBatchFixture(t)
	id := f.txn(txnSeed{account: f.private, category: f.food, desc: "Old"})
	for _, c := range []int64{0, -1} {
		f.expectRejected(pathTxnBatch, f.admin, editReq{IDs: []int64{id}, CategoryID: int64p(c)}.json(), 400, msgInvalidCategory)
		f.expectRejected(pathTxnBatch, f.admin, editReq{IDs: []int64{id}, Description: strp("New"), CategoryID: int64p(c)}.json(), 400, msgInvalidCategory)
	}
	// Rule order: id (4) and description (6) come before category_id (7).
	f.expectRejected(pathTxnBatch, f.admin, editReq{IDs: []int64{0}, CategoryID: int64p(0)}.json(), 400, msgInvalidID)
	f.expectRejected(pathTxnBatch, f.admin, editReq{IDs: []int64{id}, Description: strp(" "), CategoryID: int64p(0)}.json(), 400, msgEmptyDescription)
}

func TestBatchUpdate_InvalidBody400(t *testing.T) {
	f := newBatchFixture(t)
	f.txn(txnSeed{account: f.private, category: f.food, desc: "Old"})
	for _, path := range []string{pathTxnBatch, pathSchedBatch} {
		for _, body := range []string{
			"",
			"not json",
			"[]",
			`{"ids":"1","description":"x"}`,
			`{"ids":[1.5],"description":"x"}`,
			`{"ids":[1],"category_id":"7"}`,
			`{"ids":[1],"category_id":7.5}`,
			`{"ids":[1],"description":5}`,
			`{"ids":[1],"description":"x","update_schedules":"yes"}`,
			`{"ids":[99999999999999999999],"description":"x"}`,
		} {
			f.expectRejected(path, f.admin, body, 400, msgInvalidBody)
		}
	}
}

func TestBatchUpdate_TooMany400(t *testing.T) {
	f := newBatchFixture(t)
	id := f.txn(txnSeed{account: f.private, category: f.food, desc: "Old"})
	sid := f.sched(schedSeed{account: f.private, category: f.food, desc: "Old"})
	// first, then n-1 ids that don't exist
	ids := func(first int64, n int) []int64 {
		out := []int64{first}
		for i := int64(1); len(out) < n; i++ {
			out = append(out, 1_000_000+i)
		}
		return out
	}

	f.expectRejected(pathTxnBatch, f.admin, editReq{IDs: ids(id, 5001), Description: strp("New")}.json(), 400, msgTooManyIDs)
	f.expectRejected(pathSchedBatch, f.admin, editReq{IDs: ids(sid, 5001), Description: strp("New")}.json(), 400, msgTooManyIDs)
	f.expectRejected(pathLegacyBatch, f.admin, legacyBody(ids(id, 5001), f.transport), 400, msgTooManyIDs)

	// Exactly 5000 distinct ids go through (one 5000-parameter IN list).
	f.expectEdited(pathTxnBatch, f.admin, editReq{IDs: ids(id, 5000), Description: strp("New")}.json(),
		txnCounts(1, 0, 4999, 0, 0, 0), []int64{id}, nil)
	f.expectEdited(pathSchedBatch, f.admin, editReq{IDs: ids(sid, 5000), Description: strp("New")}.json(),
		schedCounts(1, 0, 4999), nil, []int64{sid})
	// The cap counts distinct ids: 6000 entries, 5000 distinct.
	dup := append(ids(id, 5000), ids(id, 1000)...)
	f.expectEdited(pathTxnBatch, f.admin, editReq{IDs: dup, Description: strp("Newer")}.json(),
		txnCounts(1, 0, 4999, 0, 0, 0), []int64{id}, nil)

	before := takeSnapshot(t, f.s.DB)
	if w := f.post(pathLegacyBatch, f.admin, legacyBody(ids(id, 5000), f.transport)); w.Code != 204 {
		t.Fatalf("legacy with 5000 ids: %d %s", w.Code, w.Body.String())
	}
	assertOnlyEdited(t, before, takeSnapshot(t, f.s.DB), []int64{id}, nil)
}

func TestBatchUpdate_DuplicatesCountedOnce(t *testing.T) {
	f := newBatchFixture(t)
	a := f.txn(txnSeed{account: f.private, category: f.food, desc: "Old"})
	b := f.txn(txnSeed{account: f.private, category: f.food, desc: "Old"})

	// assertOnlyEdited also checks sync_meta moved by exactly 2.
	f.expectEdited(pathTxnBatch, f.admin, editReq{IDs: []int64{a, a, b, a, b}, Description: strp("New")}.json(),
		txnCounts(2, 0, 0, 0, 0, 0), []int64{a, b}, nil)
	f.expectEdited(pathTxnBatch, f.admin, editReq{IDs: []int64{a, a}, Description: strp("New")}.json(),
		txnCounts(0, 1, 0, 0, 0, 0), nil, nil)
	f.expectEdited(pathTxnBatch, f.admin, editReq{IDs: []int64{999999, 999999}, Description: strp("New")}.json(),
		txnCounts(0, 0, 1, 0, 0, 0), nil, nil)
}

// ===== Deleted and unchanged rows, sync =====

func TestBatchUpdate_SkipsMissingAndDeleted_NoResurrection(t *testing.T) {
	f := newBatchFixture(t)
	sid := f.sched(schedSeed{account: f.private, category: f.salary, typ: "income", desc: "Pay"})
	live := f.txn(txnSeed{account: f.private, category: f.food, desc: "Old"})
	gone := f.txn(txnSeed{account: f.private, category: f.food, desc: "Old", deleted: true})
	goneLinked := f.txn(txnSeed{account: f.private, category: f.salary, typ: "income", desc: "Pay", scheduledID: sid, deleted: true})

	f.expectEdited(pathTxnBatch, f.admin,
		editReq{IDs: []int64{live, gone, 999999}, Description: strp("New"), CategoryID: int64p(f.transport)}.json(),
		txnCounts(1, 0, 2, 0, 0, 0), []int64{live}, nil)
	f.expectCell("transactions", gone, "deleted_at", sqlQuote("2025-04-01 10:00:00"))
	f.expectCell("transactions", gone, "description", sqlQuote("Old"))

	// Nothing live: 200 with zero writes. The live schedule of a deleted
	// entry is not touched, and no category check runs (there is nothing to
	// protect), so even a deleted category is accepted.
	f.expectEdited(pathTxnBatch, f.admin,
		editReq{IDs: []int64{gone, goneLinked, 999999}, Description: strp("New"), CategoryID: int64p(f.deletedCat), UpdateSchedules: true}.json(),
		txnCounts(0, 0, 3, 0, 0, 0), nil, nil)
}

func TestBatchUpdate_UnchangedNotBumped(t *testing.T) {
	f := newBatchFixture(t)
	same := f.txn(txnSeed{account: f.private, category: f.food, desc: "Stipendio"})
	stale := f.txn(txnSeed{account: f.private, category: f.food, desc: "Old"})

	f.expectEdited(pathTxnBatch, f.admin,
		editReq{IDs: []int64{same, stale}, Description: strp("Stipendio"), CategoryID: int64p(f.food)}.json(),
		txnCounts(1, 1, 0, 0, 0, 0), []int64{stale}, nil)
	// Repeating it writes nothing at all (sync_meta included).
	f.expectEdited(pathTxnBatch, f.admin,
		editReq{IDs: []int64{same, stale}, Description: strp("Stipendio"), CategoryID: int64p(f.food)}.json(),
		txnCounts(0, 2, 0, 0, 0, 0), nil, nil)
	f.expectEdited(pathTxnBatch, f.admin, editReq{IDs: []int64{same, stale}, CategoryID: int64p(f.food)}.json(),
		txnCounts(0, 2, 0, 0, 0, 0), nil, nil)
}

func TestBatchUpdate_SyncReturnsOnlyChangedRows(t *testing.T) {
	f := newBatchFixture(t)
	sid := f.sched(schedSeed{account: f.private, category: f.salary, typ: "income", desc: "Old pay"})
	e1 := f.materialized(sid, 1)
	e2 := f.materialized(sid, 2)
	e3 := f.txn(txnSeed{account: f.private, category: f.salary, typ: "income", desc: "Pay", scheduledID: sid})
	f.txn(txnSeed{account: f.private, category: f.food, desc: "Other"})

	since := f.syncVersion()
	expectCounts(t, f.post(pathTxnBatch, f.admin,
		editReq{IDs: []int64{e1, e2, e3}, Description: strp("Pay"), UpdateSchedules: true}.json()),
		txnCounts(2, 1, 0, 1, 0, 0))

	resp := doSync(t, f.h, f.admin, since)
	txns := rowsByID(t, resp["transactions"].([]any))
	scheds := rowsByID(t, resp["scheduled_transactions"].([]any))
	if len(txns) != 2 || txns[e1] == nil || txns[e2] == nil {
		t.Fatalf("sync transactions = %v, want exactly %d and %d", txns, e1, e2)
	}
	if len(scheds) != 1 || scheds[sid] == nil {
		t.Fatalf("sync scheduled = %v, want exactly %d", scheds, sid)
	}
	for _, row := range []map[string]any{txns[e1], txns[e2], scheds[sid]} {
		if row["description"] != "Pay" {
			t.Errorf("synced description = %v, want Pay", row["description"])
		}
	}
	if n := len(resp["accounts"].([]any)) + len(resp["categories"].([]any)); n != 0 {
		t.Errorf("sync returned %d accounts/categories, want none", n)
	}
}

// ===== Access, ownership and type =====

func TestBatchUpdate_Forbidden403_ZeroChanges(t *testing.T) {
	f := newBatchFixture(t)
	own := f.txn(txnSeed{account: f.guestAcct, category: f.guestFood, desc: "Old"})
	priv := f.txn(txnSeed{account: f.private, category: f.food, desc: "Old"})
	shared := f.txn(txnSeed{account: f.shared, category: f.food, desc: "Old"})

	f.expectRejected(pathTxnBatch, f.guest, editReq{IDs: []int64{own, priv}, Description: strp("X")}.json(), 403, msgForbidden)
	f.expectRejected(pathTxnBatch, f.guest, editReq{IDs: []int64{priv}, CategoryID: int64p(f.food)}.json(), 403, msgForbidden)
	f.expectRejected(pathTxnBatch, f.guest, editReq{IDs: []int64{shared, priv}, Description: strp("X"), UpdateSchedules: true}.json(), 403, msgForbidden)
	f.expectRejected(pathTxnBatch, f.admin, editReq{IDs: []int64{own}, Description: strp("X")}.json(), 403, msgForbidden)
	f.expectRejected(pathTxnBatch, f.admin, editReq{IDs: []int64{priv, own}, Description: strp("X")}.json(), 403, msgForbidden)

	// A revoked share and a deleted account both lose access.
	f.exec(`UPDATE shared_account_access SET deleted_at = datetime('now')`)
	f.expectRejected(pathTxnBatch, f.guest, editReq{IDs: []int64{shared}, Description: strp("X")}.json(), 403, msgForbidden)
	f.exec(`UPDATE accounts SET deleted_at = datetime('now') WHERE id = ?`, f.private)
	f.expectRejected(pathTxnBatch, f.admin, editReq{IDs: []int64{priv}, Description: strp("X")}.json(), 403, msgForbidden)
}

func TestBatchUpdate_GuestEditsSharedAccount(t *testing.T) {
	f := newBatchFixture(t)
	byAdmin := f.txn(txnSeed{account: f.shared, category: f.food, desc: "Old", createdBy: f.adminID})
	byGuest := f.txn(txnSeed{account: f.shared, category: f.food, desc: "Old", createdBy: f.guestID})

	// The category of a shared account's entries is the owner's.
	f.expectEdited(pathTxnBatch, f.guest,
		editReq{IDs: []int64{byAdmin, byGuest}, Description: strp("New"), CategoryID: int64p(f.transport)}.json(),
		txnCounts(2, 0, 0, 0, 0, 0), []int64{byAdmin, byGuest}, nil)
	f.expectCell("transactions", byGuest, "created_by_user_id", sqlInt(f.guestID))
	f.expectCell("transactions", byGuest, "user_id", sqlInt(f.adminID))
	f.expectCell("transactions", byAdmin, "created_by_user_id", sqlInt(f.adminID))

	f.expectRejected(pathTxnBatch, f.guest, editReq{IDs: []int64{byAdmin}, CategoryID: int64p(f.guestFood)}.json(), 400, msgCategoryNotFound)
	// The owner can edit what the guest entered.
	f.expectEdited(pathTxnBatch, f.admin, editReq{IDs: []int64{byGuest}, Description: strp("Owner fix")}.json(),
		txnCounts(1, 0, 0, 0, 0, 0), []int64{byGuest}, nil)
}

func TestBatchUpdate_MultiOwnerCategory400_ZeroChanges(t *testing.T) {
	f := newBatchFixture(t)
	sharedRow := f.txn(txnSeed{account: f.shared, category: f.food, desc: "Old"})        // owner: admin
	guestRow := f.txn(txnSeed{account: f.guestAcct, category: f.guestFood, desc: "Old"}) // owner: guest

	f.expectRejected(pathTxnBatch, f.guest, editReq{IDs: []int64{sharedRow, guestRow}, CategoryID: int64p(f.food)}.json(), 400, msgMultiOwner)
	f.expectRejected(pathTxnBatch, f.guest, editReq{IDs: []int64{guestRow, sharedRow}, Description: strp("X"), CategoryID: int64p(f.guestFood)}.json(), 400, msgMultiOwner)
	// The owner check precedes the category lookup.
	f.expectRejected(pathTxnBatch, f.guest, editReq{IDs: []int64{sharedRow, guestRow}, CategoryID: int64p(999999)}.json(), 400, msgMultiOwner)
}

func TestBatchUpdate_MultiOwnerDescriptionOnlyOK(t *testing.T) {
	f := newBatchFixture(t)
	sharedRow := f.txn(txnSeed{account: f.shared, category: f.food, desc: "Old"})
	guestRow := f.txn(txnSeed{account: f.guestAcct, category: f.guestFood, desc: "Old"})

	f.expectEdited(pathTxnBatch, f.guest, editReq{IDs: []int64{sharedRow, guestRow}, Description: strp("Shared name")}.json(),
		txnCounts(2, 0, 0, 0, 0, 0), []int64{sharedRow, guestRow}, nil)
}

func TestBatchUpdate_CategoryOtherOwner400(t *testing.T) {
	f := newBatchFixture(t)
	id := f.txn(txnSeed{account: f.private, category: f.food, desc: "Old"})
	f.expectRejected(pathTxnBatch, f.admin, editReq{IDs: []int64{id}, CategoryID: int64p(f.guestFood)}.json(), 400, msgCategoryNotFound)
	f.expectRejected(pathTxnBatch, f.admin, editReq{IDs: []int64{id}, Description: strp("X"), CategoryID: int64p(f.guestFood)}.json(), 400, msgCategoryNotFound)
}

func TestBatchUpdate_CategoryDeleted400(t *testing.T) {
	f := newBatchFixture(t)
	id := f.txn(txnSeed{account: f.private, category: f.food, desc: "Old"})
	f.expectRejected(pathTxnBatch, f.admin, editReq{IDs: []int64{id}, CategoryID: int64p(f.deletedCat)}.json(), 400, msgCategoryNotFound)
}

func TestBatchUpdate_CategoryMissing400(t *testing.T) {
	f := newBatchFixture(t)
	id := f.txn(txnSeed{account: f.private, category: f.food, desc: "Old"})
	f.expectRejected(pathTxnBatch, f.admin, editReq{IDs: []int64{id}, CategoryID: int64p(999999)}.json(), 400, msgCategoryNotFound)
}

func TestBatchUpdate_MixedTypes400(t *testing.T) {
	f := newBatchFixture(t)
	exp := f.txn(txnSeed{account: f.private, category: f.food, desc: "Old"})
	inc := f.txn(txnSeed{account: f.private, category: f.salary, typ: "income", desc: "Old"})

	f.expectRejected(pathTxnBatch, f.admin, editReq{IDs: []int64{exp, inc}, CategoryID: int64p(f.food)}.json(), 400, msgMixedTypes)
	f.expectRejected(pathTxnBatch, f.admin, editReq{IDs: []int64{inc, exp}, CategoryID: int64p(f.salary)}.json(), 400, msgMixedTypes)
	// A rename doesn't care about types.
	f.expectEdited(pathTxnBatch, f.admin, editReq{IDs: []int64{exp, inc}, Description: strp("Either")}.json(),
		txnCounts(2, 0, 0, 0, 0, 0), []int64{exp, inc}, nil)
}

func TestBatchUpdate_TypeMismatch400(t *testing.T) {
	f := newBatchFixture(t)
	exp1 := f.txn(txnSeed{account: f.private, category: f.food, desc: "Old"})
	exp2 := f.txn(txnSeed{account: f.shared, category: f.food, desc: "Old"})
	inc := f.txn(txnSeed{account: f.private, category: f.salary, typ: "income", desc: "Old"})

	f.expectRejected(pathTxnBatch, f.admin, editReq{IDs: []int64{exp1, exp2}, CategoryID: int64p(f.salary)}.json(), 400, msgTypeMismatch)
	f.expectRejected(pathTxnBatch, f.admin, editReq{IDs: []int64{inc}, CategoryID: int64p(f.food)}.json(), 400, msgTypeMismatch)
	f.expectRejected(pathTxnBatch, f.admin, editReq{IDs: []int64{inc}, Description: strp("X"), CategoryID: int64p(f.food), UpdateSchedules: true}.json(), 400, msgTypeMismatch)
}

// ===== Linked schedules =====

func TestBatchUpdate_Schedules_LiveAndPausedOnly(t *testing.T) {
	f := newBatchFixture(t)
	mk := func(clientID string, s schedSeed) int64 {
		s.account, s.category, s.typ, s.desc, s.clientID = f.private, f.salary, "income", "Stipendio", clientID
		return f.sched(s)
	}
	live := mk("android-live", schedSeed{count: 3})
	paused := mk("android-paused", schedSeed{paused: true, count: 2})
	finished := mk("android-finished", schedSeed{finished: true, maxOcc: int64(3), count: 3})
	deleted := mk("android-deleted", schedSeed{deleted: true, count: 1})
	entries := []int64{
		f.materialized(live, 1), f.materialized(live, 2), f.materialized(paused, 1),
		f.materialized(finished, 3), f.materialized(deleted, 1),
	}

	before := takeSnapshot(t, f.s.DB)
	expectCounts(t, f.post(pathTxnBatch, f.admin,
		editReq{IDs: entries, Description: strp("Stipendio Paolo"), CategoryID: int64p(f.bonus), UpdateSchedules: true}.json()),
		txnCounts(5, 0, 0, 2, 0, 2))
	after := takeSnapshot(t, f.s.DB)
	assertOnlyEdited(t, before, after, entries, []int64{live, paused})

	for _, sid := range []int64{live, paused} {
		was, got := before["scheduled_transactions"][sid], after["scheduled_transactions"][sid]
		if got["description"] != sqlQuote("Stipendio Paolo") || got["category_id"] != sqlInt(f.bonus) {
			t.Errorf("schedule %d not edited: %s / %s", sid, got["description"], got["category_id"])
		}
		for _, col := range []string{
			"next_occurrence", "rrule", "amount", "occurrence_count", "max_occurrences", "active",
			"client_id", "deleted_at", "account_id", "user_id", "created_by_user_id", "type", "note", "currency",
		} {
			if got[col] != was[col] {
				t.Errorf("schedule %d: %s changed %s -> %s", sid, col, was[col], got[col])
			}
		}
	}

	// Repeating the edit writes nothing; the live schedules are now unchanged.
	f.expectEdited(pathTxnBatch, f.admin,
		editReq{IDs: entries, Description: strp("Stipendio Paolo"), CategoryID: int64p(f.bonus), UpdateSchedules: true}.json(),
		txnCounts(0, 5, 0, 0, 2, 2), nil, nil)
}

func TestBatchUpdate_Schedules_DefaultOff(t *testing.T) {
	f := newBatchFixture(t)
	sid := f.sched(schedSeed{account: f.private, category: f.salary, typ: "income", desc: "Stipendio"})
	e := f.materialized(sid, 1)

	f.expectEdited(pathTxnBatch, f.admin, editReq{IDs: []int64{e}, Description: strp("Stipendio Paolo"), CategoryID: int64p(f.bonus)}.json(),
		txnCounts(1, 0, 0, 0, 0, 0), []int64{e}, nil)
	f.expectEdited(pathTxnBatch, f.admin, fmt.Sprintf(`{"ids":[%d],"description":"Stipendio P.","update_schedules":false}`, e),
		txnCounts(1, 0, 0, 0, 0, 0), []int64{e}, nil)
}

func TestBatchUpdate_Schedules_TypeMismatch400_ZeroChanges(t *testing.T) {
	f := newBatchFixture(t)
	sid := f.sched(schedSeed{account: f.private, category: f.food, desc: "Gym"})
	// The entry was switched to income after the scheduler created it.
	e := f.txn(txnSeed{account: f.private, category: f.salary, typ: "income", desc: "Gym", scheduledID: sid})

	f.expectRejected(pathTxnBatch, f.admin, editReq{IDs: []int64{e}, CategoryID: int64p(f.bonus), UpdateSchedules: true}.json(), 400, msgScheduleType)
	f.expectRejected(pathTxnBatch, f.admin, editReq{IDs: []int64{e}, Description: strp("Gym+"), CategoryID: int64p(f.bonus), UpdateSchedules: true}.json(), 400, msgScheduleType)
	// Without update_schedules the schedule plays no part...
	f.expectEdited(pathTxnBatch, f.admin, editReq{IDs: []int64{e}, CategoryID: int64p(f.bonus)}.json(),
		txnCounts(1, 0, 0, 0, 0, 0), []int64{e}, nil)
	// ...and a rename carries over whatever the types.
	f.expectEdited(pathTxnBatch, f.admin, editReq{IDs: []int64{e}, Description: strp("Gym+"), UpdateSchedules: true}.json(),
		txnCounts(1, 0, 0, 1, 0, 0), []int64{e}, []int64{sid})
}

func TestBatchUpdate_Schedules_OwnerMismatch400_ZeroChanges(t *testing.T) {
	f := newBatchFixture(t)
	// The guest's schedule; its entry was later moved into the admin's
	// shared account. The guest can reach both.
	sid := f.sched(schedSeed{account: f.guestAcct, category: f.guestFood, desc: "Groceries"})
	e := f.txn(txnSeed{account: f.shared, category: f.food, desc: "Groceries", createdBy: f.guestID, scheduledID: sid})

	f.expectRejected(pathTxnBatch, f.guest, editReq{IDs: []int64{e}, CategoryID: int64p(f.transport), UpdateSchedules: true}.json(), 400, msgScheduleOwner)
	f.expectEdited(pathTxnBatch, f.guest, editReq{IDs: []int64{e}, Description: strp("Food shop"), UpdateSchedules: true}.json(),
		txnCounts(1, 0, 0, 1, 0, 0), []int64{e}, []int64{sid})
}

func TestBatchUpdate_Schedules_InaccessibleSkipped(t *testing.T) {
	f := newBatchFixture(t)
	// The schedule lives in the admin's private account; its entry was moved
	// into the shared one, so the guest sees the entry but not the schedule.
	sid := f.sched(schedSeed{account: f.private, category: f.salary, typ: "income", desc: "Lunch"})
	e := f.txn(txnSeed{account: f.shared, category: f.food, desc: "Lunch", scheduledID: sid})

	f.expectEdited(pathTxnBatch, f.guest, editReq{IDs: []int64{e}, Description: strp("Team lunch"), UpdateSchedules: true}.json(),
		txnCounts(1, 0, 0, 0, 0, 1), []int64{e}, nil)
	// A skipped schedule is not type-checked (it is income, the category expense).
	f.expectEdited(pathTxnBatch, f.guest, editReq{IDs: []int64{e}, CategoryID: int64p(f.transport), UpdateSchedules: true}.json(),
		txnCounts(1, 0, 0, 0, 0, 1), []int64{e}, nil)
	// The owner reaches it.
	f.expectEdited(pathTxnBatch, f.admin, editReq{IDs: []int64{e}, Description: strp("Lunch!"), UpdateSchedules: true}.json(),
		txnCounts(1, 0, 0, 1, 0, 0), []int64{e}, []int64{sid})
}

// TestBatchUpdate_Schedules_SchedulerContinues: after a bulk edit that
// carried over to the schedule, the scheduler still materializes the next
// occurrence (its next_occurrence was not touched, so the deterministic
// client_id does not collide), with the new name and category.
func TestBatchUpdate_Schedules_SchedulerContinues(t *testing.T) {
	f := newBatchFixture(t)
	due := time.Now().Add(-time.Hour).Format(time.RFC3339)
	sid := f.sched(schedSeed{account: f.private, category: f.salary, typ: "income", desc: "Stipendio", next: due, count: 1})
	e := f.materialized(sid, 1)

	expectCounts(t, f.post(pathTxnBatch, f.admin,
		editReq{IDs: []int64{e}, Description: strp("Stipendio Paolo"), CategoryID: int64p(f.bonus), UpdateSchedules: true}.json()),
		txnCounts(1, 0, 0, 1, 0, 0))
	f.expectCell("scheduled_transactions", sid, "next_occurrence", sqlQuote(due))

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	scheduler.Start(ctx, f.s.DB, time.Hour, nil)
	var next int64
	for deadline := time.Now().Add(5 * time.Second); ; {
		err := f.s.DB.QueryRow(`SELECT id FROM transactions WHERE scheduled_id = ? AND occurrence = 2`, sid).Scan(&next)
		if err == nil {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("scheduler did not materialize occurrence 2: %v", err)
		}
		time.Sleep(10 * time.Millisecond)
	}
	f.expectCell("transactions", next, "description", sqlQuote("Stipendio Paolo"))
	f.expectCell("transactions", next, "category_id", sqlInt(f.bonus))
	f.expectCell("transactions", next, "client_id", sqlQuote(fmt.Sprintf("sched-%d-%s", sid, due)))
	f.expectCell("scheduled_transactions", sid, "occurrence_count", "2")
	if got := f.cell("scheduled_transactions", sid, "next_occurrence"); got == sqlQuote(due) {
		t.Errorf("next_occurrence did not advance: %s", got)
	}
}

// ===== POST /api/scheduled/batch-update =====

func TestBatchUpdateScheduled_Basic(t *testing.T) {
	f := newBatchFixture(t)
	s1 := f.sched(schedSeed{account: f.private, category: f.food, desc: "Netflix"})
	s2 := f.sched(schedSeed{account: f.shared, category: f.food, desc: "netflix", paused: true})
	s3 := f.sched(schedSeed{account: f.private, category: f.transport, desc: "Streaming"})
	entry := f.materialized(s1, 1)

	// update_schedules means nothing here and is ignored.
	f.expectEdited(pathSchedBatch, f.admin,
		editReq{IDs: []int64{s1, s2, s3}, Description: strp(" Streaming "), CategoryID: int64p(f.transport), UpdateSchedules: true}.json(),
		schedCounts(2, 1, 0), nil, []int64{s1, s2})
	for _, sid := range []int64{s1, s2, s3} {
		f.expectCell("scheduled_transactions", sid, "description", sqlQuote("Streaming"))
		f.expectCell("scheduled_transactions", sid, "category_id", sqlInt(f.transport))
	}
	f.expectCell("transactions", entry, "description", sqlQuote("Netflix")) // entries are not touched

	f.expectEdited(pathSchedBatch, f.admin, editReq{IDs: []int64{s1}, CategoryID: int64p(f.food)}.json(),
		schedCounts(1, 0, 0), nil, []int64{s1})
}

func TestBatchUpdateScheduled_InputValidation400(t *testing.T) {
	f := newBatchFixture(t)
	sid := f.sched(schedSeed{account: f.private, category: f.food, desc: "Old"})
	for _, tc := range []struct{ body, msg string }{
		{"", msgInvalidBody},
		{`{}`, msgIDsRequired},
		{fmt.Sprintf(`{"ids":[%d]}`, sid), msgNothingToUpdate},
		{`{"ids":[0],"description":"x"}`, msgInvalidID},
		{editReq{IDs: []int64{sid}, Description: strp("  ")}.json(), msgEmptyDescription},
		{editReq{IDs: []int64{sid}, Description: strp(strings.Repeat("a", 501))}.json(), msgDescriptionLong},
		{editReq{IDs: []int64{sid}, CategoryID: int64p(0)}.json(), msgInvalidCategory},
	} {
		f.expectRejected(pathSchedBatch, f.admin, tc.body, 400, tc.msg)
	}
}

func TestBatchUpdateScheduled_SkipsDeleted(t *testing.T) {
	f := newBatchFixture(t)
	live := f.sched(schedSeed{account: f.private, category: f.food, desc: "Old"})
	userDeleted := f.sched(schedSeed{account: f.private, category: f.food, desc: "Old", deleted: true})
	finished := f.sched(schedSeed{account: f.private, category: f.food, desc: "Old", finished: true, maxOcc: int64(2), count: 2})

	f.expectEdited(pathSchedBatch, f.admin,
		editReq{IDs: []int64{live, userDeleted, finished, 999999}, Description: strp("New"), CategoryID: int64p(f.transport)}.json(),
		schedCounts(1, 0, 3), nil, []int64{live})
	f.expectEdited(pathSchedBatch, f.admin, editReq{IDs: []int64{userDeleted, finished}, Description: strp("New")}.json(),
		schedCounts(0, 0, 2), nil, nil)
}

func TestBatchUpdateScheduled_Forbidden403(t *testing.T) {
	f := newBatchFixture(t)
	priv := f.sched(schedSeed{account: f.private, category: f.food, desc: "Old"})
	shared := f.sched(schedSeed{account: f.shared, category: f.food, desc: "Old"})
	own := f.sched(schedSeed{account: f.guestAcct, category: f.guestFood, desc: "Old"})

	f.expectRejected(pathSchedBatch, f.guest, editReq{IDs: []int64{shared, priv}, Description: strp("X")}.json(), 403, msgForbidden)
	f.expectRejected(pathSchedBatch, f.guest, editReq{IDs: []int64{priv}, CategoryID: int64p(f.food)}.json(), 403, msgForbidden)
	f.expectRejected(pathSchedBatch, f.admin, editReq{IDs: []int64{own}, Description: strp("X")}.json(), 403, msgForbidden)
	f.expectEdited(pathSchedBatch, f.guest, editReq{IDs: []int64{shared, own}, Description: strp("X")}.json(),
		schedCounts(2, 0, 0), nil, []int64{shared, own})
}

func TestBatchUpdateScheduled_TypeMismatch400(t *testing.T) {
	f := newBatchFixture(t)
	inc := f.sched(schedSeed{account: f.private, category: f.salary, typ: "income", desc: "Pay"})
	exp := f.sched(schedSeed{account: f.private, category: f.food, desc: "Gym"})
	sharedExp := f.sched(schedSeed{account: f.shared, category: f.food, desc: "Gym"})
	guestExp := f.sched(schedSeed{account: f.guestAcct, category: f.guestFood, desc: "Gym"})

	f.expectRejected(pathSchedBatch, f.admin, editReq{IDs: []int64{inc}, CategoryID: int64p(f.food)}.json(), 400, msgTypeMismatch)
	f.expectRejected(pathSchedBatch, f.admin, editReq{IDs: []int64{exp}, CategoryID: int64p(f.bonus)}.json(), 400, msgTypeMismatch)
	f.expectRejected(pathSchedBatch, f.admin, editReq{IDs: []int64{inc, exp}, CategoryID: int64p(f.food)}.json(), 400, msgMixedTypes)
	f.expectRejected(pathSchedBatch, f.guest, editReq{IDs: []int64{sharedExp, guestExp}, CategoryID: int64p(f.guestFood)}.json(), 400, msgMultiOwner)
	f.expectRejected(pathSchedBatch, f.admin, editReq{IDs: []int64{exp}, CategoryID: int64p(f.guestFood)}.json(), 400, msgCategoryNotFound)
	f.expectRejected(pathSchedBatch, f.admin, editReq{IDs: []int64{exp}, CategoryID: int64p(f.deletedCat)}.json(), 400, msgCategoryNotFound)
}

func TestBatchUpdateScheduled_PreservesOtherColumns(t *testing.T) {
	f := newBatchFixture(t)
	sid := f.sched(schedSeed{
		account: f.shared, category: f.food, desc: "Rent", note: "flat", amount: 812.34, currency: "USD",
		rrule: "FREQ=MONTHLY;INTERVAL=2;UNTIL=20301231", next: "2026-12-27T09:00:00+01:00",
		maxOcc: int64(12), count: 5, createdBy: f.guestID, clientID: "android-uuid-7", paused: true,
	})

	before := takeSnapshot(t, f.s.DB)
	expectCounts(t, f.post(pathSchedBatch, f.guest,
		editReq{IDs: []int64{sid}, Description: strp("Rent (flat)"), CategoryID: int64p(f.transport)}.json()),
		schedCounts(1, 0, 0))
	after := takeSnapshot(t, f.s.DB)
	assertOnlyEdited(t, before, after, nil, []int64{sid})

	was, got := before["scheduled_transactions"][sid], after["scheduled_transactions"][sid]
	if got["description"] != sqlQuote("Rent (flat)") || got["category_id"] != sqlInt(f.transport) {
		t.Fatalf("edit not applied: %s / %s", got["description"], got["category_id"])
	}
	for _, col := range []string{
		"account_id", "user_id", "created_by_user_id", "type", "amount", "currency", "note", "rrule",
		"next_occurrence", "active", "max_occurrences", "occurrence_count", "client_id", "created_at", "deleted_at",
	} {
		if got[col] != was[col] {
			t.Errorf("%s changed: %s -> %s", col, was[col], got[col])
		}
	}
}

// ===== Legacy POST /api/transactions/batch-update-category =====

func TestBatchUpdateCategory_Legacy_StillWorks204(t *testing.T) {
	f := newBatchFixture(t)
	a := f.txn(txnSeed{account: f.private, category: f.food, desc: "A"})
	b := f.txn(txnSeed{account: f.shared, category: f.food, desc: "B"})
	already := f.txn(txnSeed{account: f.private, category: f.transport, desc: "C"})

	before := takeSnapshot(t, f.s.DB)
	w := f.post(pathLegacyBatch, f.admin, legacyBody([]int64{a, b, already, a}, f.transport))
	if w.Code != 204 || w.Body.Len() != 0 {
		t.Fatalf("got %d %q, want 204 with no body", w.Code, w.Body.String())
	}
	// The entry that already had the category is not rewritten.
	assertOnlyEdited(t, before, takeSnapshot(t, f.s.DB), []int64{a, b}, nil)
	f.expectCell("transactions", a, "description", sqlQuote("A"))

	before = takeSnapshot(t, f.s.DB)
	if w := f.post(pathLegacyBatch, f.admin, legacyBody([]int64{999999}, f.transport)); w.Code != 204 {
		t.Fatalf("no live ids: got %d, want 204", w.Code)
	}
	assertNoChanges(t, "no live ids", before, takeSnapshot(t, f.s.DB))
}

func TestBatchUpdateCategory_Legacy_TypeMismatch400(t *testing.T) {
	f := newBatchFixture(t)
	exp := f.txn(txnSeed{account: f.private, category: f.food, desc: "A"})
	inc := f.txn(txnSeed{account: f.private, category: f.salary, typ: "income", desc: "B"})

	f.expectRejected(pathLegacyBatch, f.admin, legacyBody([]int64{exp}, f.salary), 400, msgTypeMismatch)
	f.expectRejected(pathLegacyBatch, f.admin, legacyBody([]int64{exp, inc}, f.food), 400, msgTypeMismatch)
	f.expectRejected(pathLegacyBatch, f.admin, legacyBody([]int64{inc, exp}, f.salary), 400, msgTypeMismatch)
}

func TestBatchUpdateCategory_Legacy_MultiOwner403(t *testing.T) {
	f := newBatchFixture(t)
	sharedRow := f.txn(txnSeed{account: f.shared, category: f.food, desc: "A"})
	guestRow := f.txn(txnSeed{account: f.guestAcct, category: f.guestFood, desc: "B"})

	f.expectRejected(pathLegacyBatch, f.guest, legacyBody([]int64{sharedRow, guestRow}, f.food), 403, msgLegacyMultiOwner)
}

func TestBatchUpdateCategory_Legacy_ZeroChangesOnFailure(t *testing.T) {
	f := newBatchFixture(t)
	priv := f.txn(txnSeed{account: f.private, category: f.food, desc: "A"})
	shared := f.txn(txnSeed{account: f.shared, category: f.food, desc: "B"})

	f.expectRejected(pathLegacyBatch, f.guest, legacyBody([]int64{shared, priv}, f.food), 403, msgForbidden)
	f.expectRejected(pathLegacyBatch, f.admin, legacyBody([]int64{priv}, f.deletedCat), 400, msgCategoryNotFound)
	f.expectRejected(pathLegacyBatch, f.admin, legacyBody([]int64{priv}, f.guestFood), 400, msgCategoryNotFound)
	f.expectRejected(pathLegacyBatch, f.admin, legacyBody([]int64{priv, shared}, f.salary), 400, msgTypeMismatch)
	f.expectRejected(pathLegacyBatch, f.admin, `{"ids":[],"category_id":1}`, 400, "ids and category_id required")
	f.expectRejected(pathLegacyBatch, f.admin, fmt.Sprintf(`{"ids":[%d]}`, priv), 400, "ids and category_id required")
	f.expectRejected(pathLegacyBatch, f.admin, `nope`, 400, msgInvalidBody)
}

// ===== POST /api/transactions/batch-delete hardening =====

func (f *batchFix) balance(account int64) float64 {
	f.t.Helper()
	var b float64
	if err := f.s.DB.QueryRow(`SELECT balance FROM accounts WHERE id = ?`, account).Scan(&b); err != nil {
		f.t.Fatalf("balance of %d: %v", account, err)
	}
	return b
}

// assertOnlyDeleted checks a successful batch delete: exactly txnIDs were
// soft-deleted (deleted_at set, nothing else but the trigger columns), only
// the given accounts' balances moved, and nothing else changed.
func assertOnlyDeleted(t *testing.T, before, after dbSnapshot, txnIDs, accounts []int64) {
	t.Helper()
	wantTxn, wantAcct := map[int64]bool{}, map[int64]bool{}
	for _, id := range txnIDs {
		wantTxn[id] = true
	}
	for _, id := range accounts {
		wantAcct[id] = true
	}
	cells, rows := diffSnapshots(before, after)
	if len(rows) > 0 {
		t.Fatalf("rows appeared or vanished:\n%s", formatDiff(nil, rows))
	}
	deleted := map[int64]bool{}
	for _, c := range cells {
		switch {
		case c.table == "sync_meta":
		case c.table == "transactions" && wantTxn[c.id] && (c.col == "deleted_at" || c.col == "sync_version" || c.col == "updated_at"):
			if c.col == "deleted_at" && c.before == "NULL" && c.after != "NULL" {
				deleted[c.id] = true
			}
		case c.table == "accounts" && wantAcct[c.id] && (c.col == "balance" || c.col == "sync_version" || c.col == "updated_at"):
		default:
			t.Errorf("unexpected change %s", c)
		}
	}
	for id := range wantTxn {
		if !deleted[id] {
			t.Errorf("transactions#%d not soft-deleted", id)
		}
	}
	if t.Failed() {
		t.FailNow()
	}
}

func TestBatchDelete_DuplicateIdsBalanceOnce(t *testing.T) {
	f := newBatchFixture(t)
	start := f.balance(f.private)
	a := createVia(t, f, f.admin, f.private, f.food, "expense", "A", 10.25)
	b := createVia(t, f, f.admin, f.private, f.salary, "income", "B", 50)
	c := createVia(t, f, f.admin, f.shared, f.food, "expense", "C", 3)
	keep := createVia(t, f, f.admin, f.private, f.food, "expense", "Keep", 1)
	if got := f.balance(f.private); got != start-10.25+50-1 {
		t.Fatalf("fixture: private balance %v", got)
	}

	before := takeSnapshot(t, f.s.DB)
	w := f.post("/api/transactions/batch-delete", f.admin, fmt.Sprintf(`{"ids":[%d,%d,%d,%d,%d,%d]}`, a, a, b, a, c, b))
	if w.Code != 204 {
		t.Fatalf("batch delete: %d %s", w.Code, w.Body.String())
	}
	assertOnlyDeleted(t, before, takeSnapshot(t, f.s.DB), []int64{a, b, c}, []int64{f.private, f.shared})
	// Each entry's effect reversed exactly once: only "Keep" remains.
	if got := f.balance(f.private); got != start-1 {
		t.Errorf("private balance = %v, want %v", got, start-1)
	}
	if got := f.balance(f.shared); got != 1000 {
		t.Errorf("shared balance = %v, want 1000", got)
	}
	f.expectCell("transactions", keep, "deleted_at", "NULL")

	// Deleting them again is a no-op, balances included.
	before = takeSnapshot(t, f.s.DB)
	if w := f.post("/api/transactions/batch-delete", f.admin, fmt.Sprintf(`{"ids":[%d,%d,%d,%d]}`, a, b, c, a)); w.Code != 204 {
		t.Fatalf("repeat delete: %d %s", w.Code, w.Body.String())
	}
	assertNoChanges(t, "repeat delete", before, takeSnapshot(t, f.s.DB))
}

func TestBatchDelete_TooMany400_ZeroChanges(t *testing.T) {
	f := newBatchFixture(t)
	a := createVia(t, f, f.admin, f.private, f.food, "expense", "A", 10)
	b := createVia(t, f, f.admin, f.private, f.food, "expense", "B", 20)
	ids := func(first int64, n int) []int64 {
		out := []int64{first}
		for i := int64(1); len(out) < n; i++ {
			out = append(out, 1_000_000+i)
		}
		return out
	}
	body := func(ids []int64) string {
		out, _ := json.Marshal(map[string]any{"ids": ids})
		return string(out)
	}
	path := "/api/transactions/batch-delete"

	f.expectRejected(path, f.admin, body(ids(a, 5001)), 400, msgTooManyIDs)
	f.expectRejected(path, f.admin, body([]int64{a, 0}), 400, msgInvalidID)
	f.expectRejected(path, f.admin, body([]int64{-1, a}), 400, msgInvalidID)
	f.expectRejected(path, f.admin, `{"ids":[]}`, 400, msgIDsRequired)
	f.expectRejected(path, f.admin, `{"ids":"x"}`, 400, msgInvalidBody)
	f.expectRejected(path, f.guest, body([]int64{a}), 403, msgForbidden)

	start := f.balance(f.private)
	// Exactly 5000 distinct ids go through.
	before := takeSnapshot(t, f.s.DB)
	if w := f.post(path, f.admin, body(ids(a, 5000))); w.Code != 204 {
		t.Fatalf("5000 ids: %d %s", w.Code, w.Body.String())
	}
	assertOnlyDeleted(t, before, takeSnapshot(t, f.s.DB), []int64{a}, []int64{f.private})
	// The cap counts distinct ids: 6000 entries, 5000 distinct.
	before = takeSnapshot(t, f.s.DB)
	if w := f.post(path, f.admin, body(append(ids(b, 5000), ids(b, 1000)...))); w.Code != 204 {
		t.Fatalf("6000 ids / 5000 distinct: %d %s", w.Code, w.Body.String())
	}
	assertOnlyDeleted(t, before, takeSnapshot(t, f.s.DB), []int64{b}, []int64{f.private})
	if got := f.balance(f.private); got != start+30 {
		t.Errorf("private balance = %v, want %v", got, start+30)
	}
}
