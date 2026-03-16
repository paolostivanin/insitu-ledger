# InSitu Ledger

A self-hosted personal finance tracker with a Go backend, SvelteKit frontend, and mobile sync support.

## Features

- **Transactions** — record income and expenses across multiple accounts
- **Accounts** — manage multiple accounts with independent balances and currencies (default EUR)
- **Categories** — hierarchical categories (with parent/child, icons, and colors) for income and expense
- **Scheduled transactions** — recurring transactions via RRULE (daily, weekly, monthly, yearly with configurable intervals), automatically materialized by a background scheduler
- **Reports** — spending by category, by month, and trend analysis (powered by ECharts)
- **Multi-user** — admin-created users, shared access with read/write permissions
- **Authentication** — bcrypt passwords, bearer-token sessions (30-day expiry), TOTP two-factor authentication
- **Mobile sync** — version-based incremental sync API for offline-first mobile clients
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
| Mobile   | Android (planned)                 |

## Project Structure

```
├── backend/
│   ├── cmd/server/main.go        # Entry point
│   ├── internal/
│   │   ├── api/                  # HTTP handlers and router
│   │   ├── auth/                 # Password hashing, sessions, token management
│   │   ├── db/                   # SQLite setup and schema
│   │   ├── models/               # Data structures
│   │   └── scheduler/            # Recurring transaction processor
│   ├── go.mod
│   └── go.sum
├── frontend/
│   ├── src/
│   │   ├── lib/api/client.ts     # API client
│   │   ├── lib/stores/auth.ts    # Auth state management
│   │   ├── lib/components/       # Shared components
│   │   └── routes/               # SvelteKit pages
│   └── package.json
├── mobile/                       # Android app (planned)
├── Dockerfile                    # Multi-stage build
└── docker-compose.yml
```

## API Endpoints

### Public
- `GET /api/health` — health check (returns `{"status":"ok"}`)
- `POST /api/auth/login` — rate-limited (10 attempts per IP per 15 minutes)

### Protected (Bearer token required)
- **Auth** — `POST logout`, `POST change-password`, `PUT profile`, `GET me`
- **TOTP** — `POST totp/setup`, `POST totp/verify`, `POST totp/reset`
- **Transactions** — `GET`, `POST`, `PUT {id}`, `DELETE {id}`
- **Categories** — `GET`, `POST`, `PUT {id}`, `DELETE {id}`
- **Accounts** — `GET`, `POST`, `PUT {id}`, `DELETE {id}`
- **Scheduled** — `GET`, `POST`, `PUT {id}`, `DELETE {id}`
- **Sync** — `GET /api/sync?since_version=N`
- **Reports** — `GET by-category`, `GET by-month`, `GET trend`
- **Shared access** — `GET`, `POST`, `DELETE {id}`

### Admin (admin users only)
- **Users** — `GET`, `POST`, `PUT {id}`, `DELETE {id}`, `POST {id}/reset-password`, `POST {id}/toggle-admin`, `POST {id}/disable-totp`

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

## License

All rights reserved.
