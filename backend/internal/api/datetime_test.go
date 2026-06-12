package api

import (
	"strings"
	"testing"
	"time"
)

func TestParseClientDate_AllFormats(t *testing.T) {
	tests := []struct {
		name    string
		input   string
		wantErr bool
	}{
		{"rfc3339 with offset", "2026-06-11T08:41:00+02:00", false},
		{"rfc3339 with negative offset", "2026-06-11T08:41:00-05:00", false},
		{"rfc3339 with Z", "2026-06-11T08:41:00Z", false},
		{"offset without seconds", "2026-06-11T08:41+02:00", false},
		{"naive with seconds", "2026-06-11T08:41:05", false},
		{"naive no seconds", "2026-06-11T08:41", false},
		{"date only", "2026-06-11", false},
		{"empty", "", true},
		{"garbage", "not a date", true},
		{"trailing junk", "2026-06-11T08:41:00+02:00garbage", true},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			_, err := parseClientDate(tc.input)
			if tc.wantErr && err == nil {
				t.Errorf("expected error, got nil")
			}
			if !tc.wantErr && err != nil {
				t.Errorf("unexpected error: %v", err)
			}
		})
	}
}

func TestParseClientDate_PreservesOffset(t *testing.T) {
	got, err := parseClientDate("2026-06-11T08:41:00+03:00")
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	_, offSec := got.Zone()
	if offSec != 3*3600 {
		t.Errorf("offset = %ds, want %ds", offSec, 3*3600)
	}
}

func TestParseClientDate_NaiveUsesLocal(t *testing.T) {
	got, err := parseClientDate("2026-06-11T08:41")
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	wantOff := time.Now().In(time.Local).Format("-07:00")
	gotOff := got.Format("-07:00")
	if gotOff != wantOff {
		t.Errorf("offset = %s, want server local %s", gotOff, wantOff)
	}
}

func TestCanonicalizeClientDate_DateOnlyPassthrough(t *testing.T) {
	parsed, err := parseClientDate("2026-06-11")
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	got := canonicalizeClientDate("2026-06-11", parsed)
	if got != "2026-06-11" {
		t.Errorf("date-only canonicalize = %q, want verbatim %q", got, "2026-06-11")
	}
}

func TestCanonicalizeClientDate_AddsSecondsAndKeepsOffset(t *testing.T) {
	parsed, err := parseClientDate("2026-06-11T08:41+03:00")
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	got := canonicalizeClientDate("2026-06-11T08:41+03:00", parsed)
	if !strings.HasPrefix(got, "2026-06-11T08:41:00") {
		t.Errorf("canonicalize = %q, want seconds added", got)
	}
	if !strings.HasSuffix(got, "+03:00") {
		t.Errorf("canonicalize = %q, want +03:00 preserved", got)
	}
}

func TestCanonicalizeClientDate_LegacyNaiveGetsServerOffset(t *testing.T) {
	parsed, err := parseClientDate("2026-06-11T08:41")
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	got := canonicalizeClientDate("2026-06-11T08:41", parsed)
	if !strings.Contains(got, "T") {
		t.Errorf("canonicalize = %q, want RFC3339-style", got)
	}
	wantOff := time.Now().In(time.Local).Format("-07:00")
	if !strings.HasSuffix(got, wantOff) {
		t.Errorf("canonicalize = %q, want suffix %q (server local)", got, wantOff)
	}
}
