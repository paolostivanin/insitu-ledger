-- InSitu Ledger - Database Schema
-- SQLite with WAL mode for concurrent read access

PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;
PRAGMA auto_vacuum = INCREMENTAL;

-- Monotonic sync version counter
CREATE TABLE IF NOT EXISTS sync_meta (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    version INTEGER NOT NULL DEFAULT 0
);
INSERT OR IGNORE INTO sync_meta (id, version) VALUES (1, 0);

CREATE TABLE IF NOT EXISTS sessions (
    token TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions(expires_at);

CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    email TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    is_admin INTEGER NOT NULL DEFAULT 0,
    force_password_change INTEGER NOT NULL DEFAULT 0,
    totp_secret TEXT,
    totp_enabled INTEGER NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT (datetime('now')),
    updated_at DATETIME NOT NULL DEFAULT (datetime('now')),
    sync_version INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS categories (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    parent_id INTEGER REFERENCES categories(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('income', 'expense')),
    icon TEXT,
    color TEXT,
    created_at DATETIME NOT NULL DEFAULT (datetime('now')),
    updated_at DATETIME NOT NULL DEFAULT (datetime('now')),
    deleted_at DATETIME,
    sync_version INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS accounts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    currency TEXT NOT NULL DEFAULT 'EUR',
    balance REAL NOT NULL DEFAULT 0.0,
    created_at DATETIME NOT NULL DEFAULT (datetime('now')),
    updated_at DATETIME NOT NULL DEFAULT (datetime('now')),
    deleted_at DATETIME,
    sync_version INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS transactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    category_id INTEGER NOT NULL REFERENCES categories(id) ON DELETE RESTRICT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type TEXT NOT NULL CHECK (type IN ('income', 'expense')),
    amount REAL NOT NULL CHECK (amount > 0),
    currency TEXT NOT NULL DEFAULT 'EUR',
    description TEXT,
    date TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT (datetime('now')),
    updated_at DATETIME NOT NULL DEFAULT (datetime('now')),
    deleted_at DATETIME,
    sync_version INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS scheduled_transactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    category_id INTEGER NOT NULL REFERENCES categories(id) ON DELETE RESTRICT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type TEXT NOT NULL CHECK (type IN ('income', 'expense')),
    amount REAL NOT NULL CHECK (amount > 0),
    currency TEXT NOT NULL DEFAULT 'EUR',
    description TEXT,
    rrule TEXT NOT NULL,
    next_occurrence TEXT NOT NULL,  -- YYYY-MM-DD or YYYY-MM-DDTHH:MM
    active INTEGER NOT NULL DEFAULT 1,
    max_occurrences INTEGER,
    occurrence_count INTEGER NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT (datetime('now')),
    updated_at DATETIME NOT NULL DEFAULT (datetime('now')),
    deleted_at DATETIME,
    sync_version INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS shared_access (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    guest_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    permission TEXT NOT NULL CHECK (permission IN ('read', 'write')),
    created_at DATETIME NOT NULL DEFAULT (datetime('now')),
    sync_version INTEGER NOT NULL DEFAULT 0,
    UNIQUE(owner_user_id, guest_user_id)
);

-- Indexes for query performance (critical for years of data)
CREATE INDEX IF NOT EXISTS idx_transactions_user_date ON transactions(user_id, date);
CREATE INDEX IF NOT EXISTS idx_transactions_category_date ON transactions(category_id, date);
CREATE INDEX IF NOT EXISTS idx_transactions_account_date ON transactions(account_id, date);
CREATE INDEX IF NOT EXISTS idx_transactions_deleted ON transactions(deleted_at);
CREATE INDEX IF NOT EXISTS idx_categories_user ON categories(user_id);
CREATE INDEX IF NOT EXISTS idx_categories_parent ON categories(parent_id);
CREATE INDEX IF NOT EXISTS idx_accounts_user ON accounts(user_id);
CREATE INDEX IF NOT EXISTS idx_scheduled_active ON scheduled_transactions(active, next_occurrence);
CREATE INDEX IF NOT EXISTS idx_shared_access_guest ON shared_access(guest_user_id);

-- Sync version indexes (for efficient sync queries)
CREATE INDEX IF NOT EXISTS idx_transactions_sync ON transactions(sync_version);
CREATE INDEX IF NOT EXISTS idx_categories_sync ON categories(sync_version);
CREATE INDEX IF NOT EXISTS idx_accounts_sync ON accounts(sync_version);
CREATE INDEX IF NOT EXISTS idx_scheduled_sync ON scheduled_transactions(sync_version);

-- Composite indexes for sync queries filtering by user_id + sync_version
CREATE INDEX IF NOT EXISTS idx_transactions_user_sync ON transactions(user_id, sync_version);
CREATE INDEX IF NOT EXISTS idx_categories_user_sync ON categories(user_id, sync_version);
CREATE INDEX IF NOT EXISTS idx_accounts_user_sync ON accounts(user_id, sync_version);
CREATE INDEX IF NOT EXISTS idx_scheduled_user_sync ON scheduled_transactions(user_id, sync_version);

-- Backup schedule settings (singleton row)
CREATE TABLE IF NOT EXISTS backup_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    enabled INTEGER NOT NULL DEFAULT 0,
    frequency TEXT NOT NULL DEFAULT 'daily' CHECK (frequency IN ('daily', 'weekly', 'monthly')),
    retention_count INTEGER NOT NULL DEFAULT 7,
    last_backup_at DATETIME,
    updated_at DATETIME NOT NULL DEFAULT (datetime('now'))
);
INSERT OR IGNORE INTO backup_settings (id) VALUES (1);

-- Audit log for admin actions
CREATE TABLE IF NOT EXISTS audit_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    admin_user_id INTEGER NOT NULL REFERENCES users(id),
    action TEXT NOT NULL,
    target_user_id INTEGER,
    details TEXT,
    ip_address TEXT,
    created_at DATETIME NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_audit_logs_created ON audit_logs(created_at);

-- Triggers to auto-increment sync_version on changes
CREATE TRIGGER IF NOT EXISTS trg_transactions_version AFTER INSERT ON transactions
BEGIN
    UPDATE sync_meta SET version = version + 1 WHERE id = 1;
    UPDATE transactions SET sync_version = (SELECT version FROM sync_meta WHERE id = 1) WHERE id = NEW.id;
END;

CREATE TRIGGER IF NOT EXISTS trg_transactions_update_version AFTER UPDATE ON transactions
BEGIN
    UPDATE sync_meta SET version = version + 1 WHERE id = 1;
    UPDATE transactions SET sync_version = (SELECT version FROM sync_meta WHERE id = 1), updated_at = datetime('now') WHERE id = NEW.id;
END;

CREATE TRIGGER IF NOT EXISTS trg_categories_version AFTER INSERT ON categories
BEGIN
    UPDATE sync_meta SET version = version + 1 WHERE id = 1;
    UPDATE categories SET sync_version = (SELECT version FROM sync_meta WHERE id = 1) WHERE id = NEW.id;
END;

CREATE TRIGGER IF NOT EXISTS trg_categories_update_version AFTER UPDATE ON categories
BEGIN
    UPDATE sync_meta SET version = version + 1 WHERE id = 1;
    UPDATE categories SET sync_version = (SELECT version FROM sync_meta WHERE id = 1), updated_at = datetime('now') WHERE id = NEW.id;
END;

CREATE TRIGGER IF NOT EXISTS trg_accounts_version AFTER INSERT ON accounts
BEGIN
    UPDATE sync_meta SET version = version + 1 WHERE id = 1;
    UPDATE accounts SET sync_version = (SELECT version FROM sync_meta WHERE id = 1) WHERE id = NEW.id;
END;

CREATE TRIGGER IF NOT EXISTS trg_accounts_update_version AFTER UPDATE ON accounts
BEGIN
    UPDATE sync_meta SET version = version + 1 WHERE id = 1;
    UPDATE accounts SET sync_version = (SELECT version FROM sync_meta WHERE id = 1), updated_at = datetime('now') WHERE id = NEW.id;
END;

CREATE TRIGGER IF NOT EXISTS trg_scheduled_version AFTER INSERT ON scheduled_transactions
BEGIN
    UPDATE sync_meta SET version = version + 1 WHERE id = 1;
    UPDATE scheduled_transactions SET sync_version = (SELECT version FROM sync_meta WHERE id = 1) WHERE id = NEW.id;
END;

CREATE TRIGGER IF NOT EXISTS trg_scheduled_update_version AFTER UPDATE ON scheduled_transactions
BEGIN
    UPDATE sync_meta SET version = version + 1 WHERE id = 1;
    UPDATE scheduled_transactions SET sync_version = (SELECT version FROM sync_meta WHERE id = 1), updated_at = datetime('now') WHERE id = NEW.id;
END;
