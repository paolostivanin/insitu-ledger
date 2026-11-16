package scheduler

import (
	"database/sql"
	"strconv"
	"testing"
	"time"
)

// marker is one materialized row's recurring-entry marker.
type marker struct {
	scheduledID, occurrence, occurrenceTotal sql.NullInt64
}

func readMarkers(t *testing.T, db *sql.DB, schedID int64) []marker {
	t.Helper()
	rows, err := db.Query(
		`SELECT scheduled_id, occurrence, occurrence_total FROM transactions
		 WHERE client_id LIKE ? ORDER BY id`,
		"sched-"+strconv.FormatInt(schedID, 10)+"-%",
	)
	if err != nil {
		t.Fatalf("query markers: %v", err)
	}
	defer rows.Close()
	var out []marker
	for rows.Next() {
		var m marker
		if err := rows.Scan(&m.scheduledID, &m.occurrence, &m.occurrenceTotal); err != nil {
			t.Fatalf("scan marker: %v", err)
		}
		out = append(out, m)
	}
	if err := rows.Err(); err != nil {
		t.Fatalf("iterate markers: %v", err)
	}
	return out
}

// assertMarkers checks that the schedule produced one row per wanted
// occurrence, each linked to schedID, numbered as wanted, and carrying total
// (nil = open-ended, i.e. occurrence_total NULL).
func assertMarkers(t *testing.T, db *sql.DB, schedID int64, wantOcc []int64, total *int64) {
	t.Helper()
	got := readMarkers(t, db, schedID)
	if len(got) != len(wantOcc) {
		t.Fatalf("materialized %d rows, want %d: %+v", len(got), len(wantOcc), got)
	}
	for i, m := range got {
		if !m.scheduledID.Valid || m.scheduledID.Int64 != schedID {
			t.Errorf("row %d: scheduled_id = %+v, want %d", i, m.scheduledID, schedID)
		}
		if !m.occurrence.Valid || m.occurrence.Int64 != wantOcc[i] {
			t.Errorf("row %d: occurrence = %+v, want %d", i, m.occurrence, wantOcc[i])
		}
		switch {
		case total == nil && m.occurrenceTotal.Valid:
			t.Errorf("row %d: occurrence_total = %d, want NULL", i, m.occurrenceTotal.Int64)
		case total != nil && (!m.occurrenceTotal.Valid || m.occurrenceTotal.Int64 != *total):
			t.Errorf("row %d: occurrence_total = %+v, want %d", i, m.occurrenceTotal, *total)
		}
	}
}

// TestProcessDueMarkerOpenEndedCatchUp: three overdue occurrences of an
// open-ended schedule caught up in one tick are numbered 1, 2, 3 with no total.
func TestProcessDueMarkerOpenEndedCatchUp(t *testing.T) {
	db := newTestDB(t)
	seedOwner(t, db)
	id := seedSchedule(t, db, "FREQ=WEEKLY", overdueWeeklyStart().Format(time.RFC3339), nil)

	processDue(db, nil)

	assertMarkers(t, db, id, []int64{1, 2, 3}, nil)
}

// TestProcessDueMarkerBoundedSeries: a bounded schedule records its
// max_occurrences as occurrence_total on every materialized row.
func TestProcessDueMarkerBoundedSeries(t *testing.T) {
	db := newTestDB(t)
	seedOwner(t, db)
	maxOcc := int64(5)
	id := seedSchedule(t, db, "FREQ=WEEKLY", overdueWeeklyStart().Format(time.RFC3339), &maxOcc)

	processDue(db, nil)

	assertMarkers(t, db, id, []int64{1, 2, 3}, &maxOcc)
	if st := readSchedule(t, db, id); st.count != 3 || st.active != 1 {
		t.Errorf("state = %+v, want count 3, still active", st)
	}
}

// TestProcessDueMarkerContinuesSeries: occurrence follows the schedule's
// stored occurrence_count, so a series already partly materialized continues
// at the next position.
func TestProcessDueMarkerContinuesSeries(t *testing.T) {
	db := newTestDB(t)
	seedOwner(t, db)
	maxOcc := int64(4)
	start := time.Now().UTC().Truncate(time.Second).Add(-time.Hour)
	id := seedSchedule(t, db, "FREQ=MONTHLY", start.Format(time.RFC3339), &maxOcc)
	if _, err := db.Exec(`UPDATE scheduled_transactions SET occurrence_count = 2 WHERE id = ?`, id); err != nil {
		t.Fatalf("set occurrence_count: %v", err)
	}

	processDue(db, nil)

	assertMarkers(t, db, id, []int64{3}, &maxOcc)
}

// TestProcessDueMarkerOneTime: a one-time scheduled entry (the future-date
// path: FREQ=DAILY, max 1) is marked 1 of 1, and the exhausted schedule row
// is soft-deleted but kept, so scheduled_id still resolves in the database.
func TestProcessDueMarkerOneTime(t *testing.T) {
	db := newTestDB(t)
	seedOwner(t, db)
	one := int64(1)
	start := time.Now().UTC().Truncate(time.Second).Add(-time.Minute)
	id := seedSchedule(t, db, "FREQ=DAILY", start.Format(time.RFC3339), &one)

	processDue(db, nil)

	assertMarkers(t, db, id, []int64{1}, &one)
	if st := readSchedule(t, db, id); st.active != 0 || !st.deletedAt.Valid {
		t.Errorf("state = %+v, want inactive and soft-deleted", st)
	}
}
