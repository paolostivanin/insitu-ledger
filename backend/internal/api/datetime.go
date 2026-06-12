package api

import (
	"errors"
	"strings"
	"time"
)

// parseClientDate accepts, in order:
//
//   - RFC3339 with offset:          "2026-06-11T08:41:00+02:00"  (post-1.18 clients)
//   - RFC3339 with Z:               "2026-06-11T08:41:00Z"
//   - offset without seconds:       "2026-06-11T08:41+02:00"     (defense in depth)
//   - naive datetime with seconds:  "2026-06-11T08:41:00"        (legacy: assume time.Local)
//   - naive datetime, no seconds:   "2026-06-11T08:41"           (legacy: assume time.Local)
//   - date-only:                    "2026-06-11"                 (legacy: start-of-day, time.Local)
//
// Returns the parsed time. Caller decides whether to compare in UTC or render.
func parseClientDate(s string) (time.Time, error) {
	if t, err := time.Parse(time.RFC3339, s); err == nil {
		return t, nil
	}
	if t, err := time.Parse("2006-01-02T15:04Z07:00", s); err == nil {
		return t, nil
	}
	if t, err := time.ParseInLocation("2006-01-02T15:04:05", s, time.Local); err == nil {
		return t, nil
	}
	if t, err := time.ParseInLocation("2006-01-02T15:04", s, time.Local); err == nil {
		return t, nil
	}
	if t, err := time.ParseInLocation("2006-01-02", s, time.Local); err == nil {
		return t, nil
	}
	return time.Time{}, errors.New("invalid datetime format")
}

// canonicalizeClientDate normalizes a client-supplied date string for storage.
//
//   - Date-only inputs (no 'T') are returned VERBATIM — they are TZ-agnostic
//     by design; appending an offset would corrupt them for both
//     SQLite's datetime() and Go's parsers.
//   - Everything else is emitted as RFC3339 with offset. Legacy naive inputs
//     (parsed in time.Local) get the server's current offset; offset-bearing
//     inputs keep the client's offset (display shows the typed wall-clock).
func canonicalizeClientDate(original string, t time.Time) string {
	if !strings.Contains(original, "T") {
		return original
	}
	return t.Format(time.RFC3339)
}
