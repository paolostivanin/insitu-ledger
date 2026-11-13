package scheduler

import (
	"database/sql"
	"strconv"
	"testing"
	"time"
)

// seedOwner creates user 1, account 1 and category 1.
func seedOwner(t *testing.T, db *sql.DB) {
	t.Helper()
	for _, q := range []string{
		`INSERT INTO users (name) VALUES ('Owner')`,
		`INSERT INTO accounts (user_id, name) VALUES (1, 'Solo')`,
		`INSERT INTO categories (user_id, name, type) VALUES (1, 'X', 'expense')`,
	} {
		if _, err := db.Exec(q); err != nil {
			t.Fatalf("seed %q: %v", q, err)
		}
	}
}

func seedSchedule(t *testing.T, db *sql.DB, rrule, next string, maxOcc *int64) int64 {
	t.Helper()
	res, err := db.Exec(
		`INSERT INTO scheduled_transactions
		 (account_id, category_id, user_id, created_by_user_id, type, amount, currency, rrule, next_occurrence, max_occurrences)
		 VALUES (1, 1, 1, 1, 'expense', 10.0, 'EUR', ?, ?, ?)`,
		rrule, next, maxOcc,
	)
	if err != nil {
		t.Fatalf("seed scheduled: %v", err)
	}
	id, _ := res.LastInsertId()
	return id
}

func materializedDates(t *testing.T, db *sql.DB, schedID int64) []string {
	t.Helper()
	rows, err := db.Query(
		`SELECT date FROM transactions WHERE client_id LIKE ? ORDER BY id`,
		"sched-"+strconv.FormatInt(schedID, 10)+"-%",
	)
	if err != nil {
		t.Fatalf("query transactions: %v", err)
	}
	defer rows.Close()
	var dates []string
	for rows.Next() {
		var d string
		if err := rows.Scan(&d); err != nil {
			t.Fatalf("scan: %v", err)
		}
		dates = append(dates, d)
	}
	return dates
}

type schedState struct {
	next      string
	count     int64
	active    int64
	deletedAt sql.NullString
}

func readSchedule(t *testing.T, db *sql.DB, id int64) schedState {
	t.Helper()
	var s schedState
	if err := db.QueryRow(
		`SELECT next_occurrence, occurrence_count, active, deleted_at FROM scheduled_transactions WHERE id = ?`, id,
	).Scan(&s.next, &s.count, &s.active, &s.deletedAt); err != nil {
		t.Fatalf("read scheduled %d: %v", id, err)
	}
	return s
}

func equalStrings(a, b []string) bool {
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

// overdueWeeklyStart is a weekly start 20 days ago: the occurrences at -20d,
// -13d and -6d are due, the one at +1d is not.
func overdueWeeklyStart() time.Time {
	return time.Now().UTC().Truncate(time.Second).AddDate(0, 0, -20)
}

func weeklyDates(start time.Time, n int) []string {
	out := make([]string, n)
	for i := range out {
		out[i] = start.AddDate(0, 0, 7*i).Format(time.RFC3339)
	}
	return out
}

// TestProcessDueCatchesUpOverdueOccurrences: a weekly schedule three periods
// overdue materializes all three occurrences in one processDue call and stays
// active with next_occurrence in the future.
func TestProcessDueCatchesUpOverdueOccurrences(t *testing.T) {
	db := newTestDB(t)
	seedOwner(t, db)
	start := overdueWeeklyStart()
	id := seedSchedule(t, db, "FREQ=WEEKLY", start.Format(time.RFC3339), nil)

	processDue(db, nil)

	if got, want := materializedDates(t, db, id), weeklyDates(start, 3); !equalStrings(got, want) {
		t.Fatalf("materialized = %v, want %v", got, want)
	}
	st := readSchedule(t, db, id)
	if want := start.AddDate(0, 0, 21).Format(time.RFC3339); st.next != want {
		t.Errorf("next_occurrence = %q, want %q", st.next, want)
	}
	if st.count != 3 || st.active != 1 || st.deletedAt.Valid {
		t.Errorf("state = %+v, want count 3, active, not deleted", st)
	}
	var balance float64
	if err := db.QueryRow(`SELECT balance FROM accounts WHERE id = 1`).Scan(&balance); err != nil {
		t.Fatalf("balance: %v", err)
	}
	if balance != -30 {
		t.Errorf("balance = %v, want -30", balance)
	}

	// A second call finds nothing due and changes nothing.
	processDue(db, nil)
	if got := materializedDates(t, db, id); len(got) != 3 {
		t.Errorf("after second call: %d materialized, want 3", len(got))
	}
}

// TestProcessDueCatchUpRespectsMaxOccurrences: catch-up stops at
// max_occurrences and deactivates the schedule.
func TestProcessDueCatchUpRespectsMaxOccurrences(t *testing.T) {
	db := newTestDB(t)
	seedOwner(t, db)
	start := overdueWeeklyStart()
	maxOcc := int64(2)
	id := seedSchedule(t, db, "FREQ=WEEKLY", start.Format(time.RFC3339), &maxOcc)

	processDue(db, nil)

	if got, want := materializedDates(t, db, id), weeklyDates(start, 2); !equalStrings(got, want) {
		t.Fatalf("materialized = %v, want %v", got, want)
	}
	st := readSchedule(t, db, id)
	if st.count != 2 || st.active != 0 || !st.deletedAt.Valid {
		t.Errorf("state = %+v, want count 2, inactive, deleted_at set", st)
	}
}

// TestProcessDueCatchUpRespectsUntil: catch-up stops once the next occurrence
// would fall after UNTIL and deactivates the schedule.
func TestProcessDueCatchUpRespectsUntil(t *testing.T) {
	db := newTestDB(t)
	seedOwner(t, db)
	start := overdueWeeklyStart()
	until := start.AddDate(0, 0, 7).Format("20060102T150405Z") // second occurrence, inclusive
	id := seedSchedule(t, db, "FREQ=WEEKLY;UNTIL="+until, start.Format(time.RFC3339), nil)

	processDue(db, nil)

	if got, want := materializedDates(t, db, id), weeklyDates(start, 2); !equalStrings(got, want) {
		t.Fatalf("materialized = %v, want %v", got, want)
	}
	st := readSchedule(t, db, id)
	if st.count != 2 || st.active != 0 || !st.deletedAt.Valid {
		t.Errorf("state = %+v, want count 2, inactive, deleted_at set", st)
	}
}

// TestProcessDueCatchUpIsolatesFailingRow: a row that fails (here: its
// occurrence was already materialized, so the client_id UNIQUE index rejects
// it) is tried once and left untouched, while another overdue row still
// catches up fully in the same call.
func TestProcessDueCatchUpIsolatesFailingRow(t *testing.T) {
	db := newTestDB(t)
	seedOwner(t, db)
	start := overdueWeeklyStart()
	badNext := start.Format(time.RFC3339)
	bad := seedSchedule(t, db, "FREQ=WEEKLY", badNext, nil)
	good := seedSchedule(t, db, "FREQ=WEEKLY", start.Format(time.RFC3339), nil)
	if _, err := db.Exec(
		`INSERT INTO transactions (account_id, category_id, user_id, created_by_user_id, type, amount, currency, date, client_id)
		 VALUES (1, 1, 1, 1, 'expense', 10.0, 'EUR', ?, ?)`,
		badNext, "sched-"+strconv.FormatInt(bad, 10)+"-"+badNext,
	); err != nil {
		t.Fatalf("pre-seed: %v", err)
	}

	processDue(db, nil)

	if got := materializedDates(t, db, bad); len(got) != 1 {
		t.Errorf("failing row: %d transactions, want only the pre-seeded 1", len(got))
	}
	if st := readSchedule(t, db, bad); st.next != badNext || st.count != 0 {
		t.Errorf("failing row state = %+v, want unchanged next %q, count 0", st, badNext)
	}
	if got, want := materializedDates(t, db, good), weeklyDates(start, 3); !equalStrings(got, want) {
		t.Errorf("healthy row materialized = %v, want %v", got, want)
	}
}

// TestProcessDueCatchUpCapsPassesPerTick: a schedule more than
// maxCatchUpPasses periods overdue materializes at most maxCatchUpPasses
// occurrences per call and continues on the next call.
func TestProcessDueCatchUpCapsPassesPerTick(t *testing.T) {
	db := newTestDB(t)
	seedOwner(t, db)
	start := time.Now().UTC().Truncate(time.Second).AddDate(0, 0, -(maxCatchUpPasses + 50))
	id := seedSchedule(t, db, "FREQ=DAILY", start.Format(time.RFC3339), nil)

	processDue(db, nil)
	if st := readSchedule(t, db, id); st.count != maxCatchUpPasses {
		t.Fatalf("after first call: count = %d, want %d", st.count, maxCatchUpPasses)
	}

	// The remaining 51 occurrences: day 400 through day 450, which is "now"
	// truncated to the second and therefore due.
	processDue(db, nil)
	st := readSchedule(t, db, id)
	if st.count != maxCatchUpPasses+51 {
		t.Errorf("after second call: count = %d, want %d", st.count, maxCatchUpPasses+51)
	}
	if got := materializedDates(t, db, id); int64(len(got)) != st.count {
		t.Errorf("materialized %d rows, occurrence_count %d", len(got), st.count)
	}
}

// TestProcessDueCatchUpUsesHomeZone: catch-up goes through advanceDate with
// the configured home zone, so occurrences straddling the October switch keep
// 09:00 Rome time. Unset, they keep the fixed +02:00 (process zone pinned to
// UTC like the Docker image: time.Parse adopts time.Local when the offset
// matches it, so on a Rome host the unset path would already follow DST).
func TestProcessDueCatchUpUsesHomeZone(t *testing.T) {
	withLocal(t, time.UTC)
	rome := mustLoadLocation(t, "Europe/Rome")
	maxOcc := int64(4) // bounds the run to fixed dates, all in the past
	cases := []struct {
		name string
		home *time.Location
		want []string
	}{
		{"home Europe/Rome", rome, []string{
			"2026-10-18T09:00:00+02:00",
			"2026-10-25T09:00:00+01:00",
			"2026-11-01T09:00:00+01:00",
			"2026-11-08T09:00:00+01:00",
		}},
		{"unset", nil, []string{
			"2026-10-18T09:00:00+02:00",
			"2026-10-25T09:00:00+02:00",
			"2026-11-01T09:00:00+02:00",
			"2026-11-08T09:00:00+02:00",
		}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			db := newTestDB(t)
			seedOwner(t, db)
			id := seedSchedule(t, db, "FREQ=WEEKLY", "2026-10-18T09:00:00+02:00", &maxOcc)

			processDue(db, tc.home)

			if got := materializedDates(t, db, id); !equalStrings(got, tc.want) {
				t.Errorf("materialized = %v, want %v", got, tc.want)
			}
			if st := readSchedule(t, db, id); st.active != 0 {
				t.Errorf("state = %+v, want deactivated after %d", st, maxOcc)
			}
		})
	}
}
