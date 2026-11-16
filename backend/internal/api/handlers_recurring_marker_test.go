package api

import (
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"testing"
)

func int64p(v int64) *int64 { return &v }

// assertMarkerJSON checks a transaction object's recurring-entry marker. The
// three keys must always be present; a nil want means JSON null.
func assertMarkerJSON(t *testing.T, label string, row map[string]any, sid, occ, total *int64) {
	t.Helper()
	for i, key := range []string{"scheduled_id", "occurrence", "occurrence_total"} {
		want := []*int64{sid, occ, total}[i]
		got, ok := row[key]
		if !ok {
			t.Errorf("%s: %s missing from %v", label, key, row)
			continue
		}
		if want == nil {
			if got != nil {
				t.Errorf("%s: %s = %v, want null", label, key, got)
			}
			continue
		}
		if f, isNum := got.(float64); !isNum || int64(f) != *want {
			t.Errorf("%s: %s = %v, want %d", label, key, got, *want)
		}
	}
}

func rowsByID(t *testing.T, rows []any) map[int64]map[string]any {
	t.Helper()
	out := map[int64]map[string]any{}
	for _, r := range rows {
		m := r.(map[string]any)
		out[int64(m["id"].(float64))] = m
	}
	return out
}

// TestRecurringMarkerInListAndSync runs against the production schema
// (db.Open): GET /api/transactions and GET /api/sync expose scheduled_id /
// occurrence / occurrence_total (null for ordinary entries), clients cannot
// set them on create, and an edit keeps the scheduler-set marker.
func TestRecurringMarkerInListAndSync(t *testing.T) {
	s, _, cleanup := setupRestoreTestServer(t)
	defer cleanup()
	h := NewRouter(s)
	token := loginAdmin(t, h)
	acctID, catID := createTestAccountAndCategory(t, h, token)
	var ownerID int64
	if err := s.DB.QueryRow(`SELECT user_id FROM accounts WHERE id = ?`, acctID).Scan(&ownerID); err != nil {
		t.Fatalf("account owner: %v", err)
	}

	// Ordinary entry: marker fields in the request body are ignored.
	w := httptest.NewRecorder()
	h.ServeHTTP(w, authedRequest("POST", "/api/transactions", fmt.Sprintf(
		`{"account_id":%d,"category_id":%d,"type":"expense","amount":5,"date":"2025-01-15","description":"Coffee",
		  "scheduled_id":999,"occurrence":5,"occurrence_total":9}`, acctID, catID), token))
	if w.Code != 201 {
		t.Fatalf("create: %d %s", w.Code, w.Body.String())
	}
	var created map[string]any
	json.Unmarshal(w.Body.Bytes(), &created)
	ordinaryID := int64(created["id"].(float64))

	// Two schedules and one entry each, written as the scheduler writes them.
	insertSchedule := func(maxOcc any, count int64) int64 {
		t.Helper()
		res, err := s.DB.Exec(
			`INSERT INTO scheduled_transactions (account_id, category_id, user_id, created_by_user_id, type, amount, rrule, next_occurrence, max_occurrences, occurrence_count)
			 VALUES (?, ?, ?, ?, 'expense', 800, 'FREQ=MONTHLY', '2026-03-01', ?, ?)`,
			acctID, catID, ownerID, ownerID, maxOcc, count,
		)
		if err != nil {
			t.Fatalf("insert schedule: %v", err)
		}
		id, _ := res.LastInsertId()
		return id
	}
	insertMaterialized := func(schedID, occ int64, total any) int64 {
		t.Helper()
		res, err := s.DB.Exec(
			`INSERT INTO transactions (account_id, category_id, user_id, created_by_user_id, type, amount, description, date, client_id, scheduled_id, occurrence, occurrence_total)
			 VALUES (?, ?, ?, ?, 'expense', 800, 'Rent', '2026-02-01', ?, ?, ?, ?)`,
			acctID, catID, ownerID, ownerID, fmt.Sprintf("sched-%d-2026-02-01", schedID), schedID, occ, total,
		)
		if err != nil {
			t.Fatalf("insert materialized: %v", err)
		}
		id, _ := res.LastInsertId()
		return id
	}
	boundedSched := insertSchedule(int64(3), 2)
	boundedID := insertMaterialized(boundedSched, 2, int64(3))
	openSched := insertSchedule(nil, 4)
	openID := insertMaterialized(openSched, 4, nil)

	list := func() map[int64]map[string]any {
		t.Helper()
		w := httptest.NewRecorder()
		h.ServeHTTP(w, authedRequest("GET", "/api/transactions", "", token))
		if w.Code != 200 {
			t.Fatalf("list: %d %s", w.Code, w.Body.String())
		}
		var rows []any
		if err := json.Unmarshal(w.Body.Bytes(), &rows); err != nil {
			t.Fatalf("decode list: %v", err)
		}
		return rowsByID(t, rows)
	}

	rows := list()
	if len(rows) != 3 {
		t.Fatalf("list returned %d rows, want 3", len(rows))
	}
	assertMarkerJSON(t, "list ordinary", rows[ordinaryID], nil, nil, nil)
	assertMarkerJSON(t, "list bounded", rows[boundedID], int64p(boundedSched), int64p(2), int64p(3))
	assertMarkerJSON(t, "list open-ended", rows[openID], int64p(openSched), int64p(4), nil)

	// Editing a materialized entry keeps its marker; body values are ignored.
	w = httptest.NewRecorder()
	h.ServeHTTP(w, authedRequest("PUT", fmt.Sprintf("/api/transactions/%d", boundedID), fmt.Sprintf(
		`{"account_id":%d,"category_id":%d,"type":"expense","amount":810,"currency":"EUR","date":"2026-02-01","description":"Rent (adjusted)",
		  "scheduled_id":null,"occurrence":1,"occurrence_total":null}`, acctID, catID), token))
	if w.Code != 204 {
		t.Fatalf("update: %d %s", w.Code, w.Body.String())
	}
	rows = list()
	if rows[boundedID]["description"] != "Rent (adjusted)" {
		t.Fatalf("update not applied: %v", rows[boundedID])
	}
	assertMarkerJSON(t, "list after edit", rows[boundedID], int64p(boundedSched), int64p(2), int64p(3))

	sync := doSync(t, h, token, 0)
	synced := rowsByID(t, sync["transactions"].([]any))
	if len(synced) != 3 {
		t.Fatalf("sync returned %d transactions, want 3", len(synced))
	}
	assertMarkerJSON(t, "sync ordinary", synced[ordinaryID], nil, nil, nil)
	assertMarkerJSON(t, "sync bounded", synced[boundedID], int64p(boundedSched), int64p(2), int64p(3))
	assertMarkerJSON(t, "sync open-ended", synced[openID], int64p(openSched), int64p(4), nil)
}
