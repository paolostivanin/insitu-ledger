package scheduler

import (
	"testing"
	"time"
)

func mustLoadLocation(t *testing.T, name string) *time.Location {
	t.Helper()
	loc, err := time.LoadLocation(name)
	if err != nil {
		t.Fatalf("LoadLocation(%q): %v", name, err)
	}
	return loc
}

// withLocal swaps time.Local for the duration of a test. The tests in this
// package do not run in parallel and spawn no goroutines, so this is safe.
func withLocal(t *testing.T, loc *time.Location) {
	t.Helper()
	orig := time.Local
	time.Local = loc
	t.Cleanup(func() { time.Local = orig })
}

// assertSteps advances start repeatedly with rrule in home and checks each
// produced value in order.
func assertSteps(t *testing.T, start, rrule string, home *time.Location, want []string) {
	t.Helper()
	cur := start
	for i, w := range want {
		next, _ := advanceDate(cur, rrule, home)
		if next != w {
			t.Fatalf("step %d: advanceDate(%q, %q) = %q, want %q", i+1, cur, rrule, next, w)
		}
		cur = next
	}
}

// TestAdvanceDateHomeZoneMonthlyFollowsDST: with INSITU_TIMEZONE=Europe/Rome
// a 09:00 Rome schedule stays at 09:00 local time: +01:00 through winter,
// +02:00 after the March switch, and back to +01:00 after October.
func TestAdvanceDateHomeZoneMonthlyFollowsDST(t *testing.T) {
	rome := mustLoadLocation(t, "Europe/Rome")
	assertSteps(t, "2026-11-20T09:00:00+01:00", "FREQ=MONTHLY", rome, []string{
		"2026-12-20T09:00:00+01:00",
		"2027-01-20T09:00:00+01:00",
		"2027-02-20T09:00:00+01:00",
		"2027-03-20T09:00:00+01:00",
		"2027-04-20T09:00:00+02:00",
		"2027-05-20T09:00:00+02:00",
		"2027-06-20T09:00:00+02:00",
		"2027-07-20T09:00:00+02:00",
		"2027-08-20T09:00:00+02:00",
		"2027-09-20T09:00:00+02:00",
		"2027-10-20T09:00:00+02:00",
		"2027-11-20T09:00:00+01:00",
	})
}

// TestAdvanceDateHomeZoneFrequencies covers the remaining frequencies, the
// no-seconds offset input, and the month-end clamp on the home-zone path.
func TestAdvanceDateHomeZoneFrequencies(t *testing.T) {
	rome := mustLoadLocation(t, "Europe/Rome")
	utc := mustLoadLocation(t, "UTC")
	cases := []struct {
		name  string
		input string
		rrule string
		home  *time.Location
		want  string
	}{
		{"daily across the March switch", "2027-03-27T09:00:00+01:00", "FREQ=DAILY", rome, "2027-03-28T09:00:00+02:00"},
		{"weekly across the October switch", "2026-10-21T09:00:00+02:00", "FREQ=WEEKLY", rome, "2026-10-28T09:00:00+01:00"},
		{"biweekly across the March switch", "2027-03-20T09:00:00+01:00", "FREQ=WEEKLY;INTERVAL=2", rome, "2027-04-03T09:00:00+02:00"},
		{"yearly winter to winter", "2026-12-01T09:00:00+01:00", "FREQ=YEARLY", rome, "2027-12-01T09:00:00+01:00"},
		{"six-monthly winter to summer", "2026-11-20T09:00:00+01:00", "FREQ=MONTHLY;INTERVAL=6", rome, "2027-05-20T09:00:00+02:00"},
		{"no-seconds offset input", "2027-03-20T09:00+01:00", "FREQ=MONTHLY", rome, "2027-04-20T09:00:00+02:00"},
		{"month-end clamp keeps semantics", "2027-01-31T09:00:00+01:00", "FREQ=MONTHLY", rome, "2027-02-28T09:00:00+01:00"},
		{"UTC home renders Z", "2027-03-20T09:00:00Z", "FREQ=MONTHLY", utc, "2027-04-20T09:00:00Z"},
		{"UTC home, +00:00 input renders Z like today", "2027-03-20T09:00:00+00:00", "FREQ=MONTHLY", utc, "2027-04-20T09:00:00Z"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, _ := advanceDate(tc.input, tc.rrule, tc.home)
			if got != tc.want {
				t.Errorf("advanceDate(%q, %q) = %q, want %q", tc.input, tc.rrule, got, tc.want)
			}
		})
	}
}

// TestAdvanceDateHomeZoneStickyClamp: the month-end clamp stays sticky on the
// home-zone path too (31 Jan → 28 Feb → 28 Mar), matching addMonthsClamped.
func TestAdvanceDateHomeZoneStickyClamp(t *testing.T) {
	rome := mustLoadLocation(t, "Europe/Rome")
	assertSteps(t, "2027-01-31T09:00:00+01:00", "FREQ=MONTHLY", rome, []string{
		"2027-02-28T09:00:00+01:00",
		"2027-03-28T09:00:00+02:00",
		"2027-04-28T09:00:00+02:00",
	})
}

// TestAdvanceDateHomeZoneGapShiftsForward: a wall-clock reading that does not
// exist (spring-forward gap) is shifted forward by the gap length.
//
// The shifted value is what gets stored, so the next step continues from it
// (03:30 → 03:30 next day). That matches the Android client, whose
// Rrule.advance steps the stored LocalDateTime and resolves it with
// ZonedDateTime semantics: a stored "03:30+02:00" carries no memory of the
// original 02:30.
func TestAdvanceDateHomeZoneGapShiftsForward(t *testing.T) {
	rome := mustLoadLocation(t, "Europe/Rome")
	newYork := mustLoadLocation(t, "America/New_York")
	lordHowe := mustLoadLocation(t, "Australia/Lord_Howe")

	t.Run("rome daily 02:30 across 2027-03-28", func(t *testing.T) {
		assertSteps(t, "2027-03-27T02:30:00+01:00", "FREQ=DAILY", rome, []string{
			"2027-03-28T03:30:00+02:00",
			"2027-03-29T03:30:00+02:00",
		})
	})

	cases := []struct {
		name  string
		input string
		rrule string
		home  *time.Location
		want  string
	}{
		// Stepping over the gap day keeps the 02:30 wall clock on the
		// regular summer offset.
		{"rome 02:30 every 2 days skips the gap day", "2027-03-27T02:30:00+01:00", "FREQ=DAILY;INTERVAL=2", rome, "2027-03-29T02:30:00+02:00"},
		{"rome monthly into the gap", "2027-02-28T02:30:00+01:00", "FREQ=MONTHLY", rome, "2027-03-28T03:30:00+02:00"},
		{"rome gap boundary 02:00 becomes 03:00", "2027-03-27T02:00:00+01:00", "FREQ=DAILY", rome, "2027-03-28T03:00:00+02:00"},
		{"rome 03:00 right after the gap is unaffected", "2027-03-27T03:00:00+01:00", "FREQ=DAILY", rome, "2027-03-28T03:00:00+02:00"},
		{"rome 01:59 right before the gap is unaffected", "2027-03-27T01:59:00+01:00", "FREQ=DAILY", rome, "2027-03-28T01:59:00+01:00"},
		{"new york 02:30 on 2027-03-14", "2027-03-13T02:30:00-05:00", "FREQ=DAILY", newYork, "2027-03-14T03:30:00-04:00"},
		// Lord Howe's DST step is 30 minutes (+10:30 → +11:00 at 02:00 on
		// 2026-10-04): the shift must use the real gap length, not 1h.
		{"lord howe half-hour gap", "2026-10-03T02:15:00+10:30", "FREQ=DAILY", lordHowe, "2026-10-04T02:45:00+11:00"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, _ := advanceDate(tc.input, tc.rrule, tc.home)
			if got != tc.want {
				t.Errorf("advanceDate(%q, %q) = %q, want %q", tc.input, tc.rrule, got, tc.want)
			}
		})
	}
}

// TestAdvanceDateHomeZoneOverlapTakesEarlierOffset: a wall-clock reading that
// occurs twice (fall-back overlap) resolves to the earlier instant, i.e. the
// pre-transition offset.
func TestAdvanceDateHomeZoneOverlapTakesEarlierOffset(t *testing.T) {
	rome := mustLoadLocation(t, "Europe/Rome")
	lordHowe := mustLoadLocation(t, "Australia/Lord_Howe")

	t.Run("rome daily 02:30 across 2026-10-25", func(t *testing.T) {
		assertSteps(t, "2026-10-24T02:30:00+02:00", "FREQ=DAILY", rome, []string{
			"2026-10-25T02:30:00+02:00",
			"2026-10-26T02:30:00+01:00",
		})
	})

	cases := []struct {
		name  string
		input string
		home  *time.Location
		want  string
	}{
		// The second 02:30 of the overlap day also matches Rome (+01:00 is in
		// effect at that instant) and steps normally.
		{"rome later overlap instance steps normally", "2026-10-25T02:30:00+01:00", rome, "2026-10-26T02:30:00+01:00"},
		{"rome 03:00 after the overlap is unambiguous", "2026-10-24T03:00:00+02:00", rome, "2026-10-25T03:00:00+01:00"},
		// Lord Howe falls back 30 minutes (+11:00 → +10:30 at 02:00 on
		// 2027-04-04), so 01:45 occurs twice.
		{"lord howe half-hour overlap", "2027-04-03T01:45:00+11:00", lordHowe, "2027-04-04T01:45:00+11:00"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, _ := advanceDate(tc.input, "FREQ=DAILY", tc.home)
			if got != tc.want {
				t.Errorf("advanceDate(%q) = %q, want %q", tc.input, got, tc.want)
			}
		})
	}
}

// TestAdvanceDateHomeZoneForeignOffsetUnchanged: an offset that is not the
// home zone's offset at that instant (a schedule created while travelling)
// keeps the fixed-offset stepping, exactly as with no home zone.
func TestAdvanceDateHomeZoneForeignOffsetUnchanged(t *testing.T) {
	rome := mustLoadLocation(t, "Europe/Rome")
	cases := []struct {
		input string
		rrule string
		want  string
	}{
		{"2027-03-20T09:00:00-05:00", "FREQ=MONTHLY", "2027-04-20T09:00:00-05:00"},
		{"2027-03-27T09:00:00-05:00", "FREQ=DAILY", "2027-03-28T09:00:00-05:00"},
		{"2027-03-27T02:30:00-05:00", "FREQ=DAILY", "2027-03-28T02:30:00-05:00"},
		// Rome's summer offset in winter is foreign too.
		{"2026-11-20T09:00:00+02:00", "FREQ=MONTHLY", "2026-12-20T09:00:00+02:00"},
		// The scheduler_test.go format-matrix and clamp cases.
		{"2026-03-18T09:00:00+02:00", "FREQ=MONTHLY", "2026-04-18T09:00:00+02:00"},
		{"2026-01-31T09:00:00+02:00", "FREQ=MONTHLY", "2026-02-28T09:00:00+02:00"},
	}
	for _, tc := range cases {
		got, _ := advanceDate(tc.input, tc.rrule, rome)
		if got != tc.want {
			t.Errorf("advanceDate(%q, %q, Rome) = %q, want %q", tc.input, tc.rrule, got, tc.want)
		}
		if unset, _ := advanceDate(tc.input, tc.rrule, nil); got != unset {
			t.Errorf("advanceDate(%q, %q): Rome = %q, unset = %q, want identical", tc.input, tc.rrule, got, unset)
		}
	}
}

// TestAdvanceDateHomeZoneNaiveAndDateOnlyUnchanged: values without an explicit
// offset are never reinterpreted in the home zone.
func TestAdvanceDateHomeZoneNaiveAndDateOnlyUnchanged(t *testing.T) {
	rome := mustLoadLocation(t, "Europe/Rome")
	cases := []struct {
		input string
		rrule string
		want  string
	}{
		{"2027-03-20T09:00", "FREQ=MONTHLY", "2027-04-20T09:00"},
		{"2027-03-20", "FREQ=MONTHLY", "2027-04-20"},
		{"2027-01-31", "FREQ=MONTHLY", "2027-02-28"},
		{"2026-12-30T09:00", "FREQ=DAILY", "2026-12-31T09:00"},
	}
	for _, tc := range cases {
		got, _ := advanceDate(tc.input, tc.rrule, rome)
		if got != tc.want {
			t.Errorf("advanceDate(%q, %q, Rome) = %q, want %q", tc.input, tc.rrule, got, tc.want)
		}
		if unset, _ := advanceDate(tc.input, tc.rrule, nil); got != unset {
			t.Errorf("advanceDate(%q, %q): Rome = %q, unset = %q, want identical", tc.input, tc.rrule, got, unset)
		}
	}
}

// TestAdvanceDateUnsetZoneKeepsFixedOffset pins today's behaviour of the
// Docker image (no INSITU_TIMEZONE, process zone UTC): a Rome-offset schedule
// keeps its +01:00 across the March switch.
func TestAdvanceDateUnsetZoneKeepsFixedOffset(t *testing.T) {
	withLocal(t, time.UTC)
	assertSteps(t, "2027-02-20T09:00:00+01:00", "FREQ=MONTHLY", nil, []string{
		"2027-03-20T09:00:00+01:00",
		"2027-04-20T09:00:00+01:00",
	})
	got, _ := advanceDate("2027-03-27T02:30:00+01:00", "FREQ=DAILY", nil)
	if want := "2027-03-28T02:30:00+01:00"; got != want {
		t.Errorf("daily across the gap, unset zone = %q, want %q", got, want)
	}
}

// TestAdvanceDateHomeZoneHealsDriftedSchedule: a schedule that drifted to a
// fixed +01:00 during summer (fixed-offset stepping before the home zone was
// configured) is foreign until winter, then matches Rome again and follows
// DST from there on.
func TestAdvanceDateHomeZoneHealsDriftedSchedule(t *testing.T) {
	rome := mustLoadLocation(t, "Europe/Rome")
	assertSteps(t, "2027-09-20T09:00:00+01:00", "FREQ=MONTHLY", rome, []string{
		"2027-10-20T09:00:00+01:00", // still foreign (Rome is +02:00): fixed
		"2027-11-20T09:00:00+01:00", // fixed, and now matches Rome again
		"2027-12-20T09:00:00+01:00",
		"2028-01-20T09:00:00+01:00",
		"2028-02-20T09:00:00+01:00",
		"2028-03-20T09:00:00+01:00",
		"2028-04-20T09:00:00+02:00", // home-zone stepping: follows DST
	})
}

// TestAdvanceDateHomeZoneUntilUsesResolvedInstant: UNTIL handling is
// unchanged — it is compared against the resolved next instant, which on the
// home-zone path carries the post-DST offset.
func TestAdvanceDateHomeZoneUntilUsesResolvedInstant(t *testing.T) {
	rome := mustLoadLocation(t, "Europe/Rome")
	// Next is 2027-03-28T09:00:00+02:00 = 07:00Z, before 07:30Z.
	next, pastUntil := advanceDate("2027-03-27T09:00:00+01:00", "FREQ=DAILY;UNTIL=20270328T073000Z", rome)
	if next != "2027-03-28T09:00:00+02:00" || pastUntil {
		t.Errorf("got (%q, %v), want (%q, false)", next, pastUntil, "2027-03-28T09:00:00+02:00")
	}
	// Date-only UNTIL is inclusive of the whole day, as before.
	next, pastUntil = advanceDate("2027-03-28T09:00:00+02:00", "FREQ=DAILY;UNTIL=20270328", rome)
	if next != "2027-03-29T09:00:00+02:00" || !pastUntil {
		t.Errorf("got (%q, %v), want (%q, true)", next, pastUntil, "2027-03-29T09:00:00+02:00")
	}
}

// TestResolveWallClockMatchesExplicitRules cross-checks resolveWallClock
// against hand-computed instants for a normal reading, a gap and an overlap.
func TestResolveWallClockMatchesExplicitRules(t *testing.T) {
	rome := mustLoadLocation(t, "Europe/Rome")
	cases := []struct {
		name              string
		y                 int
		m                 time.Month
		d, hh, mm         int
		wantUTC           time.Time
		wantOffsetSeconds int
	}{
		{"winter", 2027, time.January, 15, 9, 0, time.Date(2027, 1, 15, 8, 0, 0, 0, time.UTC), 3600},
		{"summer", 2027, time.July, 15, 9, 0, time.Date(2027, 7, 15, 7, 0, 0, 0, time.UTC), 7200},
		{"gap", 2027, time.March, 28, 2, 30, time.Date(2027, 3, 28, 1, 30, 0, 0, time.UTC), 7200},
		{"overlap", 2026, time.October, 25, 2, 30, time.Date(2026, 10, 25, 0, 30, 0, 0, time.UTC), 7200},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := resolveWallClock(tc.y, tc.m, tc.d, tc.hh, tc.mm, 0, 0, rome)
			if !got.Equal(tc.wantUTC) {
				t.Errorf("instant = %v, want %v", got.UTC(), tc.wantUTC)
			}
			if _, off := got.Zone(); off != tc.wantOffsetSeconds {
				t.Errorf("offset = %ds, want %ds", off, tc.wantOffsetSeconds)
			}
		})
	}
}
