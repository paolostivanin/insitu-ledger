package api

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/pstivanin/insitu-ledger/backend/internal/auth"
	"github.com/pstivanin/insitu-ledger/backend/internal/db"
)

// setupRestoreTestServer creates a Server backed by a real on-disk SQLite DB
// in a tempdir, so restore tests can exercise the file-swap path.
func setupRestoreTestServer(t *testing.T) (*Server, string, func()) {
	t.Helper()
	dataDir := t.TempDir()

	conn, err := db.Open(dataDir)
	if err != nil {
		t.Fatalf("db.Open: %v", err)
	}

	// db.Open seeds an admin with a random password — replace it with the
	// fixed test password so loginAdmin works.
	hash, _ := auth.HashPassword("testpassword")
	if _, err := conn.Exec(
		"UPDATE users SET password_hash = ?, force_password_change = 0 WHERE username = 'admin'",
		hash,
	); err != nil {
		conn.Close()
		t.Fatalf("seed admin: %v", err)
	}

	s := &Server{
		DB:        conn,
		AuthStore: auth.NewStore(conn),
		DataDir:   dataDir,
		// Production wires SIGTERM here. Tests inject a no-op so the test
		// binary isn't killed on the happy-path test.
		OnRestoreComplete: func() {},
	}

	cleanup := func() { conn.Close() }
	return s, dataDir, cleanup
}

func makeBackupOf(t *testing.T, conn *sql.DB) []byte {
	t.Helper()
	dst := filepath.Join(t.TempDir(), "snap.db")
	if _, err := conn.Exec("VACUUM INTO ?", dst); err != nil {
		t.Fatalf("VACUUM INTO: %v", err)
	}
	data, err := os.ReadFile(dst)
	if err != nil {
		t.Fatalf("read snap: %v", err)
	}
	return data
}

func multipartBody(t *testing.T, content []byte) (*bytes.Buffer, string) {
	t.Helper()
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	fw, err := mw.CreateFormFile("file", "backup.db")
	if err != nil {
		t.Fatalf("CreateFormFile: %v", err)
	}
	if _, err := io.Copy(fw, bytes.NewReader(content)); err != nil {
		t.Fatalf("copy: %v", err)
	}
	mw.Close()
	return &buf, mw.FormDataContentType()
}

func postRestore(t *testing.T, handler http.Handler, token string, content []byte) *httptest.ResponseRecorder {
	t.Helper()
	body, ctype := multipartBody(t, content)
	req := httptest.NewRequest("POST", "/api/admin/restore", body)
	req.Header.Set("Content-Type", ctype)
	req.Header.Set("Authorization", "Bearer "+token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	return w
}

func TestRestore_RequiresAdmin(t *testing.T) {
	s, _, cleanup := setupRestoreTestServer(t)
	defer cleanup()
	handler := NewRouter(s)

	hash, _ := auth.HashPassword("userpw12345")
	if _, err := s.DB.Exec(
		"INSERT INTO users (username, email, name, password_hash, is_admin) VALUES ('joe', 'joe@test.com', 'Joe', ?, 0)",
		hash,
	); err != nil {
		t.Fatalf("seed user: %v", err)
	}

	req := httptest.NewRequest("POST", "/api/auth/login", strings.NewReader(`{"login":"joe","password":"userpw12345"}`))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("login: %d %s", w.Code, w.Body.String())
	}
	var resp map[string]any
	json.Unmarshal(w.Body.Bytes(), &resp)
	token := resp["token"].(string)

	w = postRestore(t, handler, token, []byte("doesn't matter"))
	if w.Code != http.StatusForbidden {
		t.Errorf("non-admin restore: got %d, want 403", w.Code)
	}
}

func TestRestore_MissingFile(t *testing.T) {
	s, _, cleanup := setupRestoreTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	fw, _ := mw.CreateFormFile("not_file", "x.db")
	fw.Write([]byte("x"))
	mw.Close()

	req := httptest.NewRequest("POST", "/api/admin/restore", &buf)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Authorization", "Bearer "+token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != http.StatusBadRequest {
		t.Errorf("missing file: got %d, want 400", w.Code)
	}
}

func TestRestore_RejectsNonSQLite(t *testing.T) {
	s, _, cleanup := setupRestoreTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	w := postRestore(t, handler, token, []byte("hello, this is not a SQLite file"))
	if w.Code != http.StatusBadRequest {
		t.Fatalf("non-sqlite: got %d, want 400. body=%s", w.Code, w.Body.String())
	}
	if !strings.Contains(w.Body.String(), "SQLite") {
		t.Errorf("error should mention SQLite, got %q", w.Body.String())
	}
}

func TestRestore_RejectsCorruptSQLite(t *testing.T) {
	s, _, cleanup := setupRestoreTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	good := makeBackupOf(t, s.DB)
	if len(good) < 200 {
		t.Fatalf("snapshot too small to truncate")
	}
	bad := good[:100] // first 100 bytes: keeps the magic header

	w := postRestore(t, handler, token, bad)
	if w.Code != http.StatusBadRequest {
		t.Errorf("corrupt: got %d, want 400. body=%s", w.Code, w.Body.String())
	}
}

func TestRestore_RejectsMissingTables(t *testing.T) {
	s, _, cleanup := setupRestoreTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	otherDir := t.TempDir()
	otherPath := filepath.Join(otherDir, "other.db")
	otherConn, err := sql.Open("sqlite", "file:"+otherPath)
	if err != nil {
		t.Fatalf("open other: %v", err)
	}
	if _, err := otherConn.Exec("CREATE TABLE foo (x INTEGER)"); err != nil {
		t.Fatalf("create foo: %v", err)
	}
	otherConn.Close()

	data, err := os.ReadFile(otherPath)
	if err != nil {
		t.Fatalf("read other: %v", err)
	}

	w := postRestore(t, handler, token, data)
	if w.Code != http.StatusBadRequest {
		t.Errorf("foreign db: got %d, want 400. body=%s", w.Code, w.Body.String())
	}
	if !strings.Contains(w.Body.String(), "missing required table") {
		t.Errorf("error should mention missing table, got %q", w.Body.String())
	}
}

func TestRestore_RejectsBackupWithoutAdmin(t *testing.T) {
	s, _, cleanup := setupRestoreTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	noAdminDir := t.TempDir()
	noAdminConn, err := db.Open(noAdminDir)
	if err != nil {
		t.Fatalf("db.Open: %v", err)
	}
	if _, err := noAdminConn.Exec("UPDATE users SET is_admin = 0"); err != nil {
		t.Fatalf("demote: %v", err)
	}
	data := makeBackupOf(t, noAdminConn)
	noAdminConn.Close()

	w := postRestore(t, handler, token, data)
	if w.Code != http.StatusBadRequest {
		t.Errorf("no admin: got %d, want 400. body=%s", w.Code, w.Body.String())
	}
	if !strings.Contains(w.Body.String(), "no admin") {
		t.Errorf("error should mention missing admin, got %q", w.Body.String())
	}
}

func TestRestore_HappyPath(t *testing.T) {
	s, dataDir, cleanup := setupRestoreTestServer(t)
	defer cleanup()
	handler := NewRouter(s)
	token := loginAdmin(t, handler)

	var hookCalls atomic.Int32
	s.OnRestoreComplete = func() { hookCalls.Add(1) }

	if _, err := s.DB.Exec(
		"INSERT INTO accounts (user_id, name, currency, balance) VALUES (1, 'WILL_BE_KEPT', 'EUR', 0)",
	); err != nil {
		t.Fatalf("seed marker: %v", err)
	}

	// Snapshot now (contains WILL_BE_KEPT). Then add a row that should not
	// survive the restore.
	snapshot := makeBackupOf(t, s.DB)
	if _, err := s.DB.Exec(
		"INSERT INTO accounts (user_id, name, currency, balance) VALUES (1, 'POST_SNAPSHOT', 'EUR', 0)",
	); err != nil {
		t.Fatalf("seed post-snapshot marker: %v", err)
	}

	w := postRestore(t, handler, token, snapshot)
	if w.Code != http.StatusOK {
		t.Fatalf("restore: got %d, want 200. body=%s", w.Code, w.Body.String())
	}

	var resp map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode response: %v. body=%s", err, w.Body.String())
	}
	if resp["status"] != "restored" {
		t.Errorf("status = %v, want restored", resp["status"])
	}
	preSnap, _ := resp["pre_restore_snapshot"].(string)
	if preSnap == "" {
		t.Errorf("pre_restore_snapshot empty")
	}
	preSnapPath := filepath.Join(dataDir, "backups", preSnap)
	if _, err := os.Stat(preSnapPath); err != nil {
		t.Errorf("pre-restore snapshot file missing: %v", err)
	}

	livePath := filepath.Join(dataDir, "insitu-ledger.db")
	verifyConn, err := sql.Open("sqlite", "file:"+livePath+"?mode=ro")
	if err != nil {
		t.Fatalf("reopen live: %v", err)
	}
	defer verifyConn.Close()

	var keptCount, postSnapCount int
	verifyConn.QueryRow("SELECT COUNT(*) FROM accounts WHERE name = 'WILL_BE_KEPT'").Scan(&keptCount)
	verifyConn.QueryRow("SELECT COUNT(*) FROM accounts WHERE name = 'POST_SNAPSHOT'").Scan(&postSnapCount)
	if keptCount != 1 {
		t.Errorf("WILL_BE_KEPT count = %d, want 1 (was in snapshot)", keptCount)
	}
	if postSnapCount != 0 {
		t.Errorf("POST_SNAPSHOT count = %d, want 0 (added after snapshot)", postSnapCount)
	}

	var auditCount int
	verifyConn.QueryRow("SELECT COUNT(*) FROM audit_logs WHERE action = 'restore'").Scan(&auditCount)
	if auditCount != 1 {
		t.Errorf("audit log restore count = %d, want 1", auditCount)
	}

	// The restart hook fires via time.AfterFunc(200ms, ...). Poll briefly.
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) && hookCalls.Load() == 0 {
		time.Sleep(20 * time.Millisecond)
	}
	if hookCalls.Load() != 1 {
		t.Errorf("OnRestoreComplete fired %d times, want 1", hookCalls.Load())
	}
}
