# InSitu Ledger

> **in situ** (Latin) — "in its original place." Your financial data stays where it belongs: on your own server, under your control.

A self-hosted personal finance tracker with a Go backend, SvelteKit frontend, and Android app with offline-first local storage and optional sync.

## Features

- **Transactions** — record income and expenses across multiple accounts with date and time
- **Search** — free-text search across transaction descriptions (Android)
- **Accounts** — manage multiple accounts with independent balances and currencies (default EUR)
- **Categories** — hierarchical categories (with parent/child, icons, and colors) for income and expense
- **Scheduled transactions** — recurring transactions with date and time (daily, weekly, monthly, yearly), automatically materialized by the backend scheduler (checks every minute) and by the Android local WorkManager (every 15 minutes). Future-dated transactions are automatically converted to one-time scheduled entries
- **Reports** — spending by category, by month, and trend analysis (powered by ECharts)
- **Multi-user** — admin-created users, shared access with read/write permissions
- **Authentication** — bcrypt passwords, bearer-token sessions (30-day expiry), TOTP two-factor authentication
- **Mobile sync** — version-based incremental sync API for offline-first mobile clients
- **Dark/Light mode** — theme toggle with localStorage persistence and FOUC prevention
- **Keyboard shortcuts** — `n` (new item), `Escape` (close), `?` (help)
- **Batch operations** — multi-select transactions for bulk delete or category change; swipe-to-delete on Android
- **CSV import/export** — export filtered transactions; import with category/account name matching
- **Audit logging** — all admin actions are logged with timestamps, IP addresses, and target users
- **Database backup** — one-click backup download via `VACUUM INTO` (admin only)
- **PWA support** — installable as a Progressive Web App with offline caching and service worker
- **API documentation** — interactive Swagger UI at `/api/docs`
- **Soft deletes** — all entities support soft deletion with `deleted_at` timestamps
- **Single binary** — compiles to a single Go binary that serves the SvelteKit SPA as static files

## Tech Stack

| Layer    | Technology                        |
|----------|-----------------------------------|
| Backend  | Go (stdlib `net/http`, no framework) |
| Database | SQLite (WAL mode, via `modernc.org/sqlite`) |
| Frontend | SvelteKit 2, Svelte 5, TypeScript |
| Charts   | ECharts 6                         |
| Auth     | bcrypt + bearer tokens + TOTP (`pquerna/otp`) |
| Tests    | Go `testing` + `httptest`, Vitest + jsdom |
| Mobile   | Android (Kotlin, Jetpack Compose, Room, Hilt, WorkManager) |

## Project Structure

```
├── backend/
│   ├── cmd/server/main.go        # Entry point
│   ├── internal/
│   │   ├── api/                  # HTTP handlers, router, middleware, tests
│   │   ├── auth/                 # Password hashing, sessions, token management
│   │   ├── db/                   # SQLite setup and schema
│   │   ├── models/               # Data structures
│   │   └── scheduler/            # Recurring transaction processor
│   ├── go.mod
│   └── go.sum
├── frontend/
│   ├── src/
│   │   ├── lib/api/client.ts     # API client
│   │   ├── lib/stores/           # Auth, theme, and toast state management
│   │   ├── lib/components/       # Shared components (ThemeToggle, ToastContainer, etc.)
│   │   └── routes/               # SvelteKit pages
│   ├── static/                   # PWA manifest, service worker, icons
│   └── package.json
├── android/                      # Android app (Jetpack Compose, local-first with optional sync)
├── Dockerfile                    # Multi-stage build
└── docker-compose.yml
```

## API Endpoints

Full interactive documentation is available at `/api/docs` (Swagger UI).

### Public
- `GET /api/health` — health check (returns `{"status":"ok"}`)
- `POST /api/auth/login` — rate-limited (10 attempts per IP per 15 minutes)
- `GET /api/docs` — Swagger UI
- `GET /api/docs/openapi.yaml` — OpenAPI 3.0 spec

### Protected (Bearer token required)
- **Auth** — `POST logout`, `POST change-password`, `PUT profile`, `GET me`
- **TOTP** — `POST totp/setup`, `POST totp/verify`, `POST totp/reset`
- **Transactions** — `GET`, `POST`, `PUT {id}`, `DELETE {id}`
- **Batch** — `POST batch-delete`, `POST batch-update-category`
- **CSV** — `GET export`, `POST import`
- **Categories** — `GET`, `POST`, `PUT {id}`, `DELETE {id}`
- **Accounts** — `GET`, `POST`, `PUT {id}`, `DELETE {id}`
- **Scheduled** — `GET`, `POST`, `PUT {id}`, `DELETE {id}`
- **Sync** — `GET /api/sync?since_version=N`
- **Reports** — `GET by-category`, `GET by-month`, `GET trend`
- **Shared access** — `GET`, `POST`, `DELETE {id}`

### Admin (admin users only)
- **Users** — `GET`, `POST`, `PUT {id}`, `DELETE {id}`, `POST {id}/reset-password`, `POST {id}/toggle-admin`, `POST {id}/disable-totp`
- **Audit logs** — `GET /api/admin/audit-logs`
- **Backup** — `GET /api/admin/backup`

## Getting Started

### Prerequisites

- Go 1.24+
- Node.js 22+

### Development

```bash
# Backend
cd backend
go run ./cmd/server -addr :8080 -data ./data

# Frontend (separate terminal)
cd frontend
npm install
npm run dev
```

The backend serves the API on `:8080`. In development, run the SvelteKit dev server separately and proxy API calls to the backend.

### Running Tests

```bash
# Backend
cd backend && go test ./...

# Frontend
cd frontend && npm test
```

### Docker

```bash
docker compose up --build
```

This builds a multi-stage image (Node for the frontend, Go for the backend) and runs the server on port 8080. Data is persisted in a named Docker volume.

### Reverse Proxy (required)

InSitu Ledger does **not** handle TLS. You must run it behind a reverse proxy such as [Caddy](https://caddyserver.com/) or nginx for HTTPS termination. Example Caddyfile:

```
ledger.example.com {
    reverse_proxy localhost:8080
}
```

### Configuration

| Flag / Env Var      | Default   | Description                  |
|---------------------|-----------|------------------------------|
| `-addr` / `INSITU_ADDR`     | `:8080`   | Listen address               |
| `-data` / `INSITU_DATA_DIR` | `./data`  | Directory for the SQLite database |

Environment variables take precedence over flags.

### First-boot admin

When the backend starts against an empty database, it creates a single admin user (`admin@localhost`) and prints a randomly generated initial password to **stderr**, once. Capture it from your container/service logs and change it on first login.

## Security & Privacy

- See [`SECURITY.md`](SECURITY.md) for the threat model, supported versions, and how to report a vulnerability.
- See [`PRIVACY.md`](PRIVACY.md) for what data the software collects (spoiler: nothing leaves your server / device).

A few notes worth surfacing:

- The web UI stores its bearer token in `localStorage`. This is the standard SPA tradeoff; an XSS in any frontend dependency would be able to read it. Mitigations: a strict Content-Security-Policy is set, and dependencies are kept current.
- The Android app intentionally lets the home-screen widget add a transaction without biometric unlock. The widget never displays existing data.
- The Android app keeps `minSdk = 34` (Android 14). This is a deliberate choice in exchange for relying on modern platform security primitives (Keystore-bound DB encryption, predictive back, edge-to-edge); it does narrow the supported device range.
