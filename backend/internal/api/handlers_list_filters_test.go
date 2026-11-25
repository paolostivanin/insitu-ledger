package api

import (
	"encoding/csv"
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"sort"
	"strings"
	"testing"
)

// Tests for the `match=contains|exact` and `added_by=me` filters on
// GET /api/transactions, GET /api/transactions/export and
// GET /api/reports/summary. Reuses the batchFix fixture from
// handlers_batch_update_test.go.

const (
	msgInvalidMatch   = "invalid match: use 'contains' or 'exact'"
	msgInvalidAddedBy = "invalid added_by: use 'me'"
)

// listIDs returns the sorted ids GET /api/transactions returns for rawQuery.
func listIDs(t *testing.T, f *batchFix, token, rawQuery string) []int64 {
	t.Helper()
	w := httptest.NewRecorder()
	f.h.ServeHTTP(w, authedRequest("GET", "/api/transactions?limit=500&"+rawQuery, "", token))
	if w.Code != 200 {
		t.Fatalf("GET ?%s: %d %s", rawQuery, w.Code, strings.TrimSpace(w.Body.String()))
	}
	var rows []map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &rows); err != nil {
		t.Fatalf("decode: %v", err)
	}
	ids := []int64{}
	for _, r := range rows {
		ids = append(ids, int64(r["id"].(float64)))
	}
	sort.Slice(ids, func(i, j int) bool { return ids[i] < ids[j] })
	return ids
}

func sortedIDs(ids ...int64) []int64 {
	out := append([]int64{}, ids...)
	sort.Slice(out, func(i, j int) bool { return out[i] < out[j] })
	return out
}

func sameIDs(a, b []int64) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

// exportRows returns the CSV data rows (header dropped) of the export.
func exportRows(t *testing.T, f *batchFix, token, rawQuery string) [][]string {
	t.Helper()
	w := httptest.NewRecorder()
	f.h.ServeHTTP(w, authedRequest("GET", "/api/transactions/export?"+rawQuery, "", token))
	if w.Code != 200 {
		t.Fatalf("export ?%s: %d %s", rawQuery, w.Code, strings.TrimSpace(w.Body.String()))
	}
	records, err := csv.NewReader(strings.NewReader(w.Body.String())).ReadAll()
	if err != nil {
		t.Fatalf("parse csv: %v", err)
	}
	if len(records) == 0 || records[0][4] != "description" || records[0][8] != "added_by" {
		t.Fatalf("unexpected CSV header: %v", records)
	}
	return records[1:]
}

// column returns one CSV column, sorted.
func column(rows [][]string, i int) []string {
	out := []string{}
	for _, r := range rows {
		out = append(out, r[i])
	}
	sort.Strings(out)
	return out
}

// summaryRows returns GET /api/reports/summary for rawQuery.
func summaryRows(t *testing.T, f *batchFix, token, rawQuery string) []map[string]any {
	t.Helper()
	w := httptest.NewRecorder()
	f.h.ServeHTTP(w, authedRequest("GET", "/api/reports/summary?"+rawQuery, "", token))
	if w.Code != 200 {
		t.Fatalf("summary ?%s: %d %s", rawQuery, w.Code, strings.TrimSpace(w.Body.String()))
	}
	var rows []map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &rows); err != nil {
		t.Fatalf("decode: %v", err)
	}
	return rows
}

func expectSummary(t *testing.T, rows []map[string]any, count, income, expense float64) {
	t.Helper()
	if len(rows) != 1 {
		t.Fatalf("summary = %v, want one EUR row", rows)
	}
	r := rows[0]
	if r["currency"] != "EUR" || r["count"] != count || r["income"] != income || r["expense"] != expense {
		t.Fatalf("summary = %v, want count %v income %v expense %v", r, count, income, expense)
	}
}

// ===== match=exact =====

func TestListTransactions_ExactMatch(t *testing.T) {
	f := newBatchFixture(t)
	ids := map[string]int64{}
	var all []int64
	for _, d := range []any{
		"Stipendio", "Stipendio Lisa", "  stipendio  ", "STIPENDIO", "Stipendió",
		"50%", "500", "a_b", "axb", `a\b`, "ab", nil,
	} {
		id := f.txn(txnSeed{account: f.private, category: f.food, desc: d})
		key, _ := d.(string)
		if d == nil {
			key = "<NULL>"
		}
		ids[key] = id
		all = append(all, id)
	}
	pick := func(keys ...string) []int64 {
		out := []int64{}
		for _, k := range keys {
			out = append(out, ids[k])
		}
		return sortedIDs(out...)
	}
	stipendio := pick("Stipendio", "  stipendio  ", "STIPENDIO")

	for _, tc := range []struct {
		name, query string
		want        []int64
	}{
		{"contains is the default", "q=Stipendio", pick("Stipendio", "Stipendio Lisa", "  stipendio  ", "STIPENDIO")},
		{"contains explicit", "q=Stipendio&match=contains", pick("Stipendio", "Stipendio Lisa", "  stipendio  ", "STIPENDIO")},
		{"contains percent literal", "q=50%25", pick("50%")},
		{"contains underscore literal", "q=a_b", pick("a_b")},
		{"contains backslash literal", "q=a%5Cb", pick(`a\b`)},
		{"exact", "q=Stipendio&match=exact", stipendio},
		{"exact folds ASCII case", "q=sTiPeNdIo&match=exact", stipendio},
		{"exact trims q", "q=%20%20Stipendio%20&match=exact", stipendio},
		{"exact whole name", "q=Stipendio%20Lisa&match=exact", pick("Stipendio Lisa")},
		{"exact is not infix", "q=Lisa&match=exact", nil},
		{"exact is not prefix", "q=Stip&match=exact", nil},
		{"exact non-ASCII", "q=Stipendi%C3%B3&match=exact", pick("Stipendió")},
		{"exact folds ASCII case only", "q=STIPENDI%C3%93&match=exact", nil},
		{"exact percent literal", "q=50%25&match=exact", pick("50%")},
		{"exact lone percent", "q=%25&match=exact", nil},
		{"exact underscore literal", "q=a_b&match=exact", pick("a_b")},
		{"exact lone underscore", "q=_&match=exact", nil},
		{"exact backslash literal", "q=a%5Cb&match=exact", pick(`a\b`)},
		{"exact without q is no filter", "match=exact", sortedIDs(all...)},
		{"exact with blank q is no filter", "q=%20%20&match=exact", sortedIDs(all...)},
	} {
		t.Run(tc.name, func(t *testing.T) {
			want := tc.want
			if want == nil {
				want = []int64{}
			}
			if got := listIDs(t, f, f.admin, tc.query); !sameIDs(got, want) {
				t.Errorf("?%s = %v, want %v", tc.query, got, want)
			}
		})
	}
}

func TestListTransactions_InvalidMatch400(t *testing.T) {
	f := newBatchFixture(t)
	for _, path := range []string{"/api/transactions", "/api/transactions/export", "/api/reports/summary"} {
		for _, m := range []string{"EXACT", "fuzzy", "exact%20", "1", "contains,exact"} {
			for _, q := range []string{"q=x&", ""} {
				url := path + "?" + q + "match=" + m
				w := httptest.NewRecorder()
				f.h.ServeHTTP(w, authedRequest("GET", url, "", f.admin))
				expectError(t, w, 400, msgInvalidMatch)
			}
		}
	}
}

func TestListTransactions_ExactComposesWithFilters(t *testing.T) {
	f := newBatchFixture(t)
	jan := "2025-01-15T09:00:00+01:00"
	target := f.txn(txnSeed{account: f.private, category: f.food, desc: "Stipendio", date: jan})
	f.txn(txnSeed{account: f.private, category: f.food, desc: "Stipendio", date: "2025-06-15"})
	otherCat := f.txn(txnSeed{account: f.private, category: f.transport, desc: "Stipendio", date: jan})
	otherAcct := f.txn(txnSeed{account: f.shared, category: f.food, desc: "stipendio ", date: jan})
	lisa := f.txn(txnSeed{account: f.private, category: f.food, desc: "Stipendio Lisa", date: jan})
	f.txn(txnSeed{account: f.private, category: f.food, desc: "Stipendio", date: jan, deleted: true})
	byGuest := f.txn(txnSeed{account: f.shared, category: f.food, desc: "STIPENDIO", date: jan, createdBy: f.guestID})

	janRange := "from=2025-01-01&to=2025-01-31"
	narrow := fmt.Sprintf("%s&category_id=%d&account_id=%d", janRange, f.food, f.private)
	check := func(token, query string, want ...int64) {
		t.Helper()
		if got := listIDs(t, f, token, query); !sameIDs(got, sortedIDs(want...)) {
			t.Errorf("?%s = %v, want %v", query, got, sortedIDs(want...))
		}
	}
	check(f.admin, "q=Stipendio&match=exact&"+narrow, target)
	check(f.admin, "q=Stipendio&"+narrow, target, lisa)
	// The category-sort variant of the query joins categories.
	check(f.admin, "q=Stipendio&match=exact&sort_by=category&"+janRange, target, otherCat, otherAcct, byGuest)
	check(f.admin, "q=Stipendio&match=exact&added_by=me&"+janRange, target, otherCat, otherAcct)
	check(f.admin, fmt.Sprintf("q=Stipendio&match=exact&account_id=%d", f.shared), otherAcct, byGuest)
	check(f.guest, "q=Stipendio&match=exact", otherAcct, byGuest)
	check(f.guest, "q=Stipendio&match=exact&added_by=me", byGuest)
}

func TestExport_ExactMatch(t *testing.T) {
	f := newBatchFixture(t)
	for _, d := range []string{"Stipendio", "  stipendio  ", "STIPENDIO", "Stipendio Lisa", "Rent"} {
		f.txn(txnSeed{account: f.private, category: f.food, desc: d})
	}
	got := column(exportRows(t, f, f.admin, "q=Stipendio&match=exact"), 4)
	if want := []string{"  stipendio  ", "STIPENDIO", "Stipendio"}; strings.Join(got, "|") != strings.Join(want, "|") {
		t.Errorf("exact export = %q, want %q", got, want)
	}
	if got := exportRows(t, f, f.admin, "q=Stipendio"); len(got) != 4 {
		t.Errorf("contains export has %d rows, want 4", len(got))
	}
	if got := exportRows(t, f, f.admin, "match=exact"); len(got) != 5 {
		t.Errorf("export without q has %d rows, want 5", len(got))
	}
}

// Entries without a name used to fail the export's Scan into a plain string
// and vanish from the CSV without any error.
func TestExport_IncludesEntriesWithoutDescription(t *testing.T) {
	f := newBatchFixture(t)
	f.txn(txnSeed{account: f.private, category: f.food, desc: "Rent"})
	f.txn(txnSeed{account: f.private, category: f.food, desc: nil})
	f.txn(txnSeed{account: f.private, category: f.food, desc: nil, note: "only a note"})
	rows := exportRows(t, f, f.admin, "")
	if len(rows) != 3 {
		t.Fatalf("export has %d rows, want 3: %q", len(rows), rows)
	}
	got := column(rows, 4)
	sort.Strings(got)
	if want := []string{"", "", "Rent"}; strings.Join(got, "|") != strings.Join(want, "|") {
		t.Errorf("descriptions = %q, want %q", got, want)
	}
}

func TestReportSummary_ExactMatch(t *testing.T) {
	f := newBatchFixture(t)
	for _, s := range []struct {
		desc   string
		amount float64
	}{{"Stipendio", 100}, {"  stipendio  ", 20}, {"STIPENDIO", 3}, {"Stipendio Lisa", 1000}, {"Rent", 7}} {
		f.txn(txnSeed{account: f.private, category: f.food, desc: s.desc, amount: s.amount})
	}
	f.txn(txnSeed{account: f.private, category: f.salary, typ: "income", desc: "stipendio", amount: 50})

	expectSummary(t, summaryRows(t, f, f.admin, "q=Stipendio&match=exact"), 4, 50, 123)
	expectSummary(t, summaryRows(t, f, f.admin, "q=Stipendio"), 5, 50, 1123)
	expectSummary(t, summaryRows(t, f, f.admin, fmt.Sprintf("q=Stipendio&match=exact&category_id=%d", f.food)), 3, 0, 123)
}

// ===== added_by=me =====

// createVia adds an entry through POST /api/transactions, so attribution
// comes from the real create path.
func createVia(t *testing.T, f *batchFix, token string, account, category int64, typ, desc string, amount float64) int64 {
	t.Helper()
	w := f.post("/api/transactions", token, fmt.Sprintf(
		`{"account_id":%d,"category_id":%d,"type":%q,"amount":%v,"description":%q,"date":"2025-02-01"}`,
		account, category, typ, amount, desc))
	if w.Code != 201 {
		t.Fatalf("create %q: %d %s", desc, w.Code, w.Body.String())
	}
	var out map[string]any
	json.Unmarshal(w.Body.Bytes(), &out)
	return int64(out["id"].(float64))
}

func TestListTransactions_AddedByMe(t *testing.T) {
	f := newBatchFixture(t)
	adminShared1 := createVia(t, f, f.admin, f.shared, f.food, "expense", "Stipendio", 1)
	adminShared2 := createVia(t, f, f.admin, f.shared, f.food, "expense", "Coffee", 1)
	guestShared1 := createVia(t, f, f.guest, f.shared, f.food, "expense", "Stipendio", 1)
	guestShared2 := createVia(t, f, f.guest, f.shared, f.food, "expense", "Groceries", 1)
	adminPrivate := createVia(t, f, f.admin, f.private, f.food, "expense", "stipendio", 1)
	guestOwn := createVia(t, f, f.guest, f.guestAcct, f.guestFood, "expense", "Gym", 1)
	// Scheduler-created entries carry the schedule creator.
	guestSchedEntry := f.materialized(f.sched(schedSeed{account: f.shared, category: f.food, desc: "Stipendio", createdBy: f.guestID}), 1)
	adminSchedEntry := f.materialized(f.sched(schedSeed{account: f.private, category: f.food, desc: "Rent", createdBy: f.adminID}), 1)
	// The creator was deleted (ON DELETE SET NULL): nobody's.
	orphan := f.txn(txnSeed{account: f.shared, category: f.food, desc: "Stipendio", nullCreator: true})

	check := func(name, token, query string, want ...int64) {
		t.Helper()
		if got := listIDs(t, f, token, query); !sameIDs(got, sortedIDs(want...)) {
			t.Errorf("%s: ?%s = %v, want %v", name, query, got, sortedIDs(want...))
		}
	}
	check("admin, no filter", f.admin, "",
		adminShared1, adminShared2, guestShared1, guestShared2, adminPrivate, guestSchedEntry, adminSchedEntry, orphan)
	check("guest, no filter", f.guest, "",
		adminShared1, adminShared2, guestShared1, guestShared2, guestOwn, guestSchedEntry, orphan)
	check("admin, mine", f.admin, "added_by=me", adminShared1, adminShared2, adminPrivate, adminSchedEntry)
	check("guest, mine", f.guest, "added_by=me", guestShared1, guestShared2, guestOwn, guestSchedEntry)
	check("admin, mine, exact", f.admin, "q=Stipendio&match=exact&added_by=me", adminShared1, adminPrivate)
	check("guest, mine, exact", f.guest, "q=Stipendio&match=exact&added_by=me", guestShared1, guestSchedEntry)
	check("guest, mine, contains", f.guest, "q=o&added_by=me", guestShared1, guestShared2, guestSchedEntry)
	check("guest, mine, shared account", f.guest, fmt.Sprintf("added_by=me&account_id=%d", f.shared),
		guestShared1, guestShared2, guestSchedEntry)
	check("admin, mine, guest's account", f.admin, fmt.Sprintf("added_by=me&account_id=%d", f.guestAcct))
}

func TestListTransactions_AddedByInvalid400(t *testing.T) {
	f := newBatchFixture(t)
	for _, path := range []string{"/api/transactions", "/api/transactions/export", "/api/reports/summary"} {
		for _, v := range []string{fmt.Sprint(f.guestID), fmt.Sprint(f.adminID), "ME", "Me", "all", "me%20", "you", "me,2"} {
			w := httptest.NewRecorder()
			f.h.ServeHTTP(w, authedRequest("GET", path+"?added_by="+v, "", f.guest))
			expectError(t, w, 400, msgInvalidAddedBy)
		}
	}
}

func TestExport_AddedByMe(t *testing.T) {
	f := newBatchFixture(t)
	createVia(t, f, f.admin, f.shared, f.food, "expense", "Coffee", 1)
	createVia(t, f, f.guest, f.shared, f.food, "expense", "Groceries", 1)
	createVia(t, f, f.guest, f.guestAcct, f.guestFood, "expense", "Gym", 1)
	f.materialized(f.sched(schedSeed{account: f.shared, category: f.food, desc: "Netflix", createdBy: f.guestID}), 1)
	f.txn(txnSeed{account: f.shared, category: f.food, desc: "Orphan", nullCreator: true})

	guestRows := exportRows(t, f, f.guest, "added_by=me")
	if got := strings.Join(column(guestRows, 4), "|"); got != "Groceries|Gym|Netflix" {
		t.Errorf("guest export = %s, want Groceries|Gym|Netflix", got)
	}
	for _, r := range guestRows {
		if r[8] != "Guest" {
			t.Errorf("guest export row added_by = %q, want Guest: %v", r[8], r)
		}
	}
	if got := strings.Join(column(exportRows(t, f, f.admin, "added_by=me"), 4), "|"); got != "Coffee" {
		t.Errorf("admin export = %s, want Coffee", got)
	}
	if got := strings.Join(column(exportRows(t, f, f.admin, "q=Groceries&match=exact&added_by=me"), 4), "|"); got != "" {
		t.Errorf("admin export of the guest's entry = %s, want nothing", got)
	}
	if got := len(exportRows(t, f, f.admin, "")); got != 4 {
		t.Errorf("admin export without filter has %d rows, want 4", got)
	}
}

func TestReportSummary_AddedByMe(t *testing.T) {
	f := newBatchFixture(t)
	createVia(t, f, f.admin, f.shared, f.food, "expense", "Trip", 10)
	createVia(t, f, f.guest, f.shared, f.food, "expense", "Trip", 5)
	createVia(t, f, f.guest, f.shared, f.salary, "income", "trip", 7)
	createVia(t, f, f.guest, f.shared, f.food, "expense", "Trip snacks", 2)
	f.txn(txnSeed{account: f.shared, category: f.food, desc: "Trip", amount: 100, nullCreator: true})

	expectSummary(t, summaryRows(t, f, f.admin, "q=Trip"), 5, 7, 117)
	expectSummary(t, summaryRows(t, f, f.admin, "q=Trip&added_by=me"), 1, 0, 10)
	expectSummary(t, summaryRows(t, f, f.guest, "q=Trip&added_by=me"), 3, 7, 7)
	expectSummary(t, summaryRows(t, f, f.guest, "q=Trip&match=exact&added_by=me"), 2, 7, 5)
}
