package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

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
