package auth

import (
	"testing"
	"time"
)

func TestCreateAndValidateTrustedDevice(t *testing.T) {
	db := setupTestDB(t)
	defer db.Close()
	store := NewStore(db)

	tok, err := store.CreateTrustedDevice(7, "test-ua", time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	if tok == "" {
		t.Fatal("token should not be empty")
	}

	ok, err := store.ValidateTrustedDevice(tok, 7)
	if err != nil {
		t.Fatal(err)
	}
	if !ok {
		t.Error("trusted device should validate for its owner")
	}
}

func TestTrustedDeviceWrongUser(t *testing.T) {
	db := setupTestDB(t)
	defer db.Close()
	store := NewStore(db)

	tok, _ := store.CreateTrustedDevice(7, "ua", time.Hour)
	ok, err := store.ValidateTrustedDevice(tok, 8)
	if err != nil {
		t.Fatal(err)
	}
	if ok {
		t.Error("trusted device must NOT validate for a different user")
	}
}

func TestTrustedDeviceExpired(t *testing.T) {
	db := setupTestDB(t)
	defer db.Close()
	store := NewStore(db)

	tok, _ := store.CreateTrustedDevice(1, "ua", -time.Minute)
	ok, err := store.ValidateTrustedDevice(tok, 1)
	if err != nil {
		t.Fatal(err)
	}
	if ok {
		t.Error("expired trusted device must not validate")
	}
	// And the row should be cleaned up by the validation path.
	var count int
	db.QueryRow("SELECT COUNT(*) FROM trusted_devices WHERE user_id = 1").Scan(&count)
	if count != 0 {
		t.Errorf("expired row should be deleted, got count=%d", count)
	}
}

func TestTrustedDeviceEmptyToken(t *testing.T) {
	db := setupTestDB(t)
	defer db.Close()
	store := NewStore(db)

	ok, err := store.ValidateTrustedDevice("", 1)
	if err != nil {
		t.Fatal(err)
	}
	if ok {
		t.Error("empty token must not validate")
	}
}

func TestListAndRevokeTrustedDevice(t *testing.T) {
	db := setupTestDB(t)
	defer db.Close()
	store := NewStore(db)

	t1, _ := store.CreateTrustedDevice(5, "Firefox", time.Hour)
	_, _ = store.CreateTrustedDevice(5, "Chrome", time.Hour)
	_, _ = store.CreateTrustedDevice(99, "Other", time.Hour)

	devs, err := store.ListTrustedDevices(5)
	if err != nil {
		t.Fatal(err)
	}
	if len(devs) != 2 {
		t.Fatalf("expected 2 devices for user 5, got %d", len(devs))
	}

	// Revoking with a wrong user_id must not delete anything.
	if err := store.RevokeTrustedDevice(99, devs[0].ID); err != nil {
		t.Fatal(err)
	}
	stillThere, _ := store.ValidateTrustedDevice(t1, 5)
	if !stillThere {
		t.Error("device should not be revocable by a non-owner")
	}

	// Real revoke
	if err := store.RevokeTrustedDevice(5, devs[0].ID); err != nil {
		t.Fatal(err)
	}
	devs2, _ := store.ListTrustedDevices(5)
	if len(devs2) != 1 {
		t.Errorf("after revoke want 1 device, got %d", len(devs2))
	}
}

func TestRevokeAllTrustedDevicesForUser(t *testing.T) {
	db := setupTestDB(t)
	defer db.Close()
	store := NewStore(db)

	store.CreateTrustedDevice(3, "a", time.Hour)
	store.CreateTrustedDevice(3, "b", time.Hour)
	store.CreateTrustedDevice(4, "c", time.Hour)

	store.RevokeAllTrustedDevicesForUser(3)

	devs3, _ := store.ListTrustedDevices(3)
	if len(devs3) != 0 {
		t.Errorf("user 3 should have 0 devices, got %d", len(devs3))
	}
	devs4, _ := store.ListTrustedDevices(4)
	if len(devs4) != 1 {
		t.Errorf("user 4 should still have 1 device, got %d", len(devs4))
	}
}

func TestCleanupExpiredTrustedDevices(t *testing.T) {
	db := setupTestDB(t)
	defer db.Close()
	store := NewStore(db)

	store.CreateTrustedDevice(1, "old", -time.Hour)
	store.CreateTrustedDevice(1, "new", time.Hour)

	store.cleanupTrusted()

	devs, _ := store.ListTrustedDevices(1)
	if len(devs) != 1 || devs[0].Label != "new" {
		t.Errorf("expected only the non-expired device, got %+v", devs)
	}
}
