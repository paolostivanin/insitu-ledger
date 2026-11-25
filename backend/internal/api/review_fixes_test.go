package api

import (
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"sort"
	"strings"
	"testing"

	"github.com/pstivanin/insitu-ledger/backend/internal"
)

// A database failure while checking access must fail the batch edit (500,
// nothing written), never read as "no access" (403) or quietly skip a linked
// schedule with a 200.
func TestBatchUpdate_AccessCheckDBError500_ZeroChanges(t *testing.T) {
	f := newBatchFixture(t)
	shared := f.txn(txnSeed{account: f.shared, category: f.food, desc: "Old"})
	sid := f.sched(schedSeed{account: f.shared, category: f.food, desc: "Rent"})
	own := f.txn(txnSeed{account: f.guestAcct, category: f.guestFood, desc: "Old", scheduledID: sid})
	// The guest's access to the shared account is checked via this table.
	f.exec(`ALTER TABLE shared_account_access RENAME COLUMN guest_user_id TO guest_user_id_gone`)

	f.expectRejected(pathTxnBatch, f.guest, editReq{IDs: []int64{shared}, Description: strp("X")}.json(), 500, "internal error")
	// The entry is the guest's own; only its linked schedule needs the share.
	f.expectRejected(pathTxnBatch, f.guest, editReq{IDs: []int64{own}, Description: strp("X"), UpdateSchedules: true}.json(), 500, "internal error")
}

func TestExport_CategoryFilterMatchesList(t *testing.T) {
	f := newBatchFixture(t)
	parent := f.category(f.adminID, "Home", "expense", false)
	child := f.exec(`INSERT INTO categories (user_id, parent_id, name, type) VALUES (?, ?, 'Rent', 'expense')`, f.adminID, parent)
	f.txn(txnSeed{account: f.private, category: parent, desc: "Fix sink"})
	f.txn(txnSeed{account: f.private, category: child, desc: "March rent"})
	f.txn(txnSeed{account: f.private, category: f.food, desc: "Pizza"})

	got := column(exportRows(t, f, f.admin, fmt.Sprintf("category_id=%d", parent)), 4)
	sort.Strings(got)
	if want := []string{"Fix sink", "March rent"}; strings.Join(got, "|") != strings.Join(want, "|") {
		t.Errorf("export filtered by parent = %q, want %q", got, want)
	}
	if got := exportRows(t, f, f.admin, fmt.Sprintf("category_id=%d", f.food)); len(got) != 1 {
		t.Errorf("export filtered by Food has %d rows, want 1", len(got))
	}
}

func TestHealthEndpoint_ReportsVersion(t *testing.T) {
	s, cleanup := setupTestServer(t)
	defer cleanup()
	w := httptest.NewRecorder()
	NewRouter(s).ServeHTTP(w, httptest.NewRequest("GET", "/api/health", nil))
	var body map[string]string
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode: %v (%q)", err, w.Body.String())
	}
	if w.Code != 200 || body["status"] != "ok" || body["version"] != internal.Version {
		t.Fatalf("health = %d %v, want 200 status=ok version=%s", w.Code, body, internal.Version)
	}
}
