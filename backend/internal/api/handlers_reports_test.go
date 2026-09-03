package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
)

// /reports/summary answers "how did this trip net out": what went out, what
// came back, and the balance — split per currency so a trip abroad can't
// silently add dollars to euros.
func TestReportSummary_SearchTotalsPerCurrency(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	acctID := mustCreateAccount(t, handler, token, `{"name":"Wallet"}`)
	travelID := mustCreateCategory(t, handler, token, `{"name":"Travel","type":"expense"}`)
	hotelID := mustCreateCategory(t, handler, token,
		`{"name":"Hotels","type":"expense","parent_id":`+strconv.FormatInt(travelID, 10)+`}`)
	otherID := mustCreateCategory(t, handler, token, `{"name":"Other","type":"expense"}`)

	seed := []struct {
		catID    int64
		typ      string
		amount   float64
		currency string
		desc     string
		date     string
	}{
		{hotelID, "expense", 310.00, "EUR", "Valencia hotel", "2026-08-14"},
		{travelID, "expense", 54.20, "EUR", "Valencia dinner", "2026-08-13"},
		{travelID, "income", 80.00, "EUR", "Marco - Valencia split", "2026-08-14"},
		// Different currency, same trip — must not merge into the EUR row.
		{travelID, "expense", 40.00, "USD", "Valencia airport snack", "2026-08-12"},
		// Case-insensitivity: LIKE folds ASCII case with no pragma set.
		{travelID, "expense", 5.80, "EUR", "VALENCIA metro", "2026-08-13"},
		// Must not match "valencia" at all.
		{otherID, "expense", 999.00, "EUR", "Weekly shop", "2026-08-14"},
		// Matches the term but sits outside the date window used below.
		{travelID, "expense", 22.00, "EUR", "Valencia 2024 trip", "2024-05-02"},
	}
	for _, x := range seed {
		if _, err := s.DB.Exec(
			`INSERT INTO transactions (account_id, category_id, user_id, created_by_user_id, type, amount, currency, description, date)
			 VALUES (?, ?, 1, 1, ?, ?, ?, ?, ?)`,
			acctID, x.catID, x.typ, x.amount, x.currency, x.desc, x.date,
		); err != nil {
			t.Fatalf("seed %q: %v", x.desc, err)
		}
	}

	get := func(path string) []map[string]any {
		t.Helper()
		req := authedRequest("GET", path, "", token)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, req)
		if w.Code != http.StatusOK {
			t.Fatalf("GET %s: got %d: %s", path, w.Code, w.Body.String())
		}
		var out []map[string]any
		if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil {
			t.Fatalf("GET %s: unmarshal: %v", path, err)
		}
		return out
	}

	got := get("/api/reports/summary?q=valencia")
	if len(got) != 2 {
		t.Fatalf("want 2 currency rows, got %d: %v", len(got), got)
	}

	// EUR first: it has the larger SUM(amount) (310+54.20+80+5.80+22 vs 40).
	eur := got[0]
	if eur["currency"] != "EUR" {
		t.Fatalf("want EUR first (dominant currency), got %v", eur["currency"])
	}
	// 310.00 + 54.20 + 5.80 + 22.00 out, 80.00 back.
	if eur["expense"].(float64) != 392.00 {
		t.Errorf("EUR expense = %v, want 392", eur["expense"])
	}
	if eur["income"].(float64) != 80.00 {
		t.Errorf("EUR income = %v, want 80", eur["income"])
	}
	if eur["net"].(float64) != -312.00 {
		t.Errorf("EUR net = %v, want -312", eur["net"])
	}
	if eur["count"].(float64) != 5 {
		t.Errorf("EUR count = %v, want 5", eur["count"])
	}

	usd := got[1]
	if usd["currency"] != "USD" || usd["expense"].(float64) != 40.00 || usd["net"].(float64) != -40.00 {
		t.Errorf("USD row = %v, want expense 40 / net -40", usd)
	}

	// Date window drops the 2024 trip but keeps the 2026 one.
	windowed := get("/api/reports/summary?q=valencia&from=2026-01-01&to=2026-12-31")
	if windowed[0]["expense"].(float64) != 370.00 {
		t.Errorf("windowed EUR expense = %v, want 370 (2024 row excluded)", windowed[0]["expense"])
	}

	// category_id expands to children, so filtering on the parent keeps the
	// hotel row that is filed under the sub-category.
	scoped := get("/api/reports/summary?q=valencia&category_id=" + strconv.FormatInt(travelID, 10))
	if scoped[0]["count"].(float64) != 5 {
		t.Errorf("parent-scoped count = %v, want 5 (child rows included)", scoped[0]["count"])
	}

	// No match is an empty array, not null — clients iterate it directly.
	if none := get("/api/reports/summary?q=nosuchtrip"); len(none) != 0 {
		t.Errorf("want no rows for a non-matching search, got %v", none)
	}

	// A LIKE wildcard in the query is escaped, not treated as a pattern.
	if wild := get("/api/reports/summary?q=%25"); len(wild) != 0 {
		t.Errorf("bare %% should match nothing (escaped), got %v", wild)
	}

	// Omitting q summarises everything the other filters match.
	all := get("/api/reports/summary")
	var eurAll map[string]any
	for _, r := range all {
		if r["currency"] == "EUR" {
			eurAll = r
		}
	}
	if eurAll["count"].(float64) != 6 {
		t.Errorf("unfiltered EUR count = %v, want 6 (all EUR rows)", eurAll["count"])
	}
}

// by-category carries the parent link so clients can roll child spend up into
// the parent. parent_id must come from the joined *live* parent row, not from
// c.parent_id: a child whose parent was soft-deleted has to report as its own
// top-level bucket, otherwise clients keying on `parent_id ?? category_id`
// would build a bucket with no name.
func TestReportByCategory_CarriesLiveParentOnly(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	acctID := mustCreateAccount(t, handler, token, `{"name":"Wallet"}`)
	foodID := mustCreateCategory(t, handler, token, `{"name":"Food","type":"expense","color":"#ff0000"}`)
	groceriesID := mustCreateCategory(t, handler, token,
		`{"name":"Groceries","type":"expense","color":"#00ff00","parent_id":`+strconv.FormatInt(foodID, 10)+`}`)
	// Second parent, soft-deleted below once its child exists.
	goneID := mustCreateCategory(t, handler, token, `{"name":"Gone","type":"expense"}`)
	orphanID := mustCreateCategory(t, handler, token,
		`{"name":"Orphan","type":"expense","parent_id":`+strconv.FormatInt(goneID, 10)+`}`)

	for _, catID := range []int64{foodID, groceriesID, orphanID} {
		if _, err := s.DB.Exec(
			`INSERT INTO transactions (account_id, category_id, user_id, created_by_user_id, type, amount, currency, date)
			 VALUES (?, ?, 1, 1, 'expense', 5.0, 'EUR', '2026-03-04')`,
			acctID, catID,
		); err != nil {
			t.Fatalf("seed transaction for category %d: %v", catID, err)
		}
	}

	// "Gone" has no transactions of its own, so DELETE is allowed even though
	// its child does — that is exactly how a dangling parent link arises.
	req := authedRequest("DELETE", "/api/categories/"+strconv.FormatInt(goneID, 10), "", token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != http.StatusNoContent {
		t.Fatalf("delete parent category: got %d: %s", w.Code, w.Body.String())
	}

	req = authedRequest("GET", "/api/reports/by-category?type=expense", "", token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("by-category: got %d: %s", w.Code, w.Body.String())
	}
	var got []map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("by-category: unmarshal: %v", err)
	}

	byID := map[int64]map[string]any{}
	for _, r := range got {
		byID[int64(r["category_id"].(float64))] = r
	}
	if len(byID) != 3 {
		t.Fatalf("by-category returned %d rows, want 3: %v", len(byID), got)
	}

	// Child of a live parent: full parent link.
	child := byID[groceriesID]
	if child["parent_id"] == nil || int64(child["parent_id"].(float64)) != foodID {
		t.Errorf("Groceries parent_id = %v, want %d", child["parent_id"], foodID)
	}
	if child["parent_name"] != "Food" {
		t.Errorf("Groceries parent_name = %v, want Food", child["parent_name"])
	}
	if child["parent_color"] != "#ff0000" {
		t.Errorf("Groceries parent_color = %v, want #ff0000", child["parent_color"])
	}

	// Top-level category: no parent link.
	if parent := byID[foodID]; parent["parent_id"] != nil {
		t.Errorf("Food parent_id = %v, want null", parent["parent_id"])
	}

	// Child of a soft-deleted parent: reports as its own top-level bucket.
	orphan := byID[orphanID]
	if orphan["parent_id"] != nil {
		t.Errorf("Orphan parent_id = %v, want null (parent is soft-deleted)", orphan["parent_id"])
	}
	if orphan["parent_name"] != nil {
		t.Errorf("Orphan parent_name = %v, want null", orphan["parent_name"])
	}
}

// Regression test for the v1.18.0 SUBSTR bucketing fix (PLAN §4.5).
//
// SQLite's strftime/datetime treat a trailing "+02:00" as a UTC-offset
// modifier and normalize to UTC. Under the old strftime bucketing, a
// transaction typed "2026-07-01T00:30:00+02:00" (= 2026-06-30T22:30Z) would
// bucket into JUNE, and a New-Year's row would shift years. Bucketing must
// follow the typed wall-clock (SUBSTR prefix), matching display.
func TestReports_OffsetRowsBucketByTypedWallClock(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	acctID := mustCreateAccount(t, handler, token, `{"name":"Wallet"}`)
	catID := mustCreateCategory(t, handler, token, `{"name":"Food","type":"expense"}`)

	// Insert directly (these calendar dates may be in the future relative to
	// the test run; POSTing them would re-route to scheduled_transactions).
	rows := []struct {
		amount float64
		date   string
	}{
		// 30 min past midnight, +02:00 — strftime would re-bucket into June.
		{10.0, "2026-07-01T00:30:00+02:00"},
		// New-Year's edge — strftime would re-bucket into 2025.
		{20.0, "2026-01-01T00:30:00+02:00"},
	}
	for _, r := range rows {
		if _, err := s.DB.Exec(
			`INSERT INTO transactions (account_id, category_id, user_id, created_by_user_id, type, amount, currency, date)
			 VALUES (?, ?, 1, 1, 'expense', ?, 'EUR', ?)`,
			acctID, catID, r.amount, r.date,
		); err != nil {
			t.Fatalf("seed transaction %q: %v", r.date, err)
		}
	}

	get := func(path string) []map[string]any {
		t.Helper()
		req := authedRequest("GET", path, "", token)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, req)
		if w.Code != http.StatusOK {
			t.Fatalf("GET %s: got %d: %s", path, w.Code, w.Body.String())
		}
		var got []map[string]any
		if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
			t.Fatalf("GET %s: unmarshal: %v", path, err)
		}
		return got
	}

	// by-month: the July row must land in "2026-07" (not "2026-06").
	months := map[string]float64{}
	for _, r := range get("/api/reports/by-month") {
		months[r["month"].(string)] = r["total"].(float64)
	}
	if months["2026-07"] != 10.0 {
		t.Errorf("by-month[2026-07] = %v, want 10 (months: %v)", months["2026-07"], months)
	}
	if months["2026-01"] != 20.0 {
		t.Errorf("by-month[2026-01] = %v, want 20 (months: %v)", months["2026-01"], months)
	}
	if _, ok := months["2026-06"]; ok {
		t.Errorf("by-month contains 2026-06 — offset row was UTC-normalized into the prior month")
	}

	// by-month with year filter: both rows are typed-2026 and must survive the
	// filter (the Jan 1 row is 2025 in UTC — strftime('%Y') would drop it).
	yearMonths := map[string]float64{}
	for _, r := range get("/api/reports/by-month?year=2026") {
		yearMonths[r["month"].(string)] = r["total"].(float64)
	}
	if len(yearMonths) != 2 {
		t.Errorf("by-month?year=2026 returned %d buckets, want 2 (%v)", len(yearMonths), yearMonths)
	}

	// trend daily: the July row must bucket on its typed date.
	days := map[string]float64{}
	for _, r := range get("/api/reports/trend?group_by=day") {
		days[r["period"].(string)] = r["total"].(float64)
	}
	if days["2026-07-01"] != 10.0 {
		t.Errorf("trend[2026-07-01] = %v, want 10 (days: %v)", days["2026-07-01"], days)
	}
	if _, ok := days["2026-06-30"]; ok {
		t.Errorf("trend contains 2026-06-30 — offset row was UTC-normalized into the prior day")
	}

	// trend monthly (default group_by) sanity.
	periods := map[string]float64{}
	for _, r := range get("/api/reports/trend") {
		periods[r["period"].(string)] = r["total"].(float64)
	}
	if periods["2026-07"] != 10.0 {
		t.Errorf("trend monthly[2026-07] = %v, want 10 (%v)", periods["2026-07"], periods)
	}

	// trend weekly: strftime on the offset-stripped prefix — the typed date's
	// week, not the UTC-shifted one. 2026-07-01 is in strftime week %W = "26";
	// compute it via SQLite itself to avoid hardcoding week math.
	var wantWeek string
	if err := s.DB.QueryRow(`SELECT strftime('%Y-W%W', '2026-07-01')`).Scan(&wantWeek); err != nil {
		t.Fatalf("compute want week: %v", err)
	}
	weeks := map[string]float64{}
	for _, r := range get("/api/reports/trend?group_by=week") {
		weeks[r["period"].(string)] = r["total"].(float64)
	}
	if weeks[wantWeek] != 10.0 {
		t.Errorf("trend weekly[%s] = %v, want 10 (%v)", wantWeek, weeks[wantWeek], weeks)
	}
}
