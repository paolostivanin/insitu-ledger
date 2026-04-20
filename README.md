# InSitu Ledger

> **in situ** (Latin) — "in its original place." Your financial data stays where it belongs: on your own server, under your control.

A self-hosted personal finance tracker with a Go backend, SvelteKit frontend, and Android app with offline-first local storage and optional sync.

## Features

- **Transactions** — record income and expenses across multiple accounts with date, time, and free-text notes
- **Search** — free-text search across transaction descriptions (Android)
- **Accounts** — manage multiple accounts with independent balances and currencies (default EUR)
- **Categories** — hierarchical categories (with parent/child, icons, and colors) for income and expense
- **Scheduled transactions** — recurring transactions with date and time (daily, weekly, monthly, yearly), automatically materialized by the backend scheduler (checks every minute) and by the Android local WorkManager (every 15 minutes). Future-dated transactions are automatically converted to one-time scheduled entries
- **Reports** — spending by category, by month, and trend analysis (powered by ECharts)
- **Multi-user** — admin-created users, shared access with read/write permissions
- **Authentication** — bcrypt passwords, bearer-token sessions (30-day expiry, hashed at rest), TOTP two-factor authentication
- **Rate limiting** — login (10 per IP per 15 min), TOTP verification, and per-IP API throttling
- **Mobile sync** — version-based incremental sync API for offline-first mobile clients
- **Android app** — local-first with optional sync; SQLCipher-encrypted Room database (Keystore-bound), encrypted local backups (PBKDF2 + AES-256-GCM via SAF), optional mTLS client-certificate authentication for sync, home-screen quick-add widget, swipe-to-delete
- **Dark/Light mode** — theme toggle with localStorage persistence and FOUC prevention
- **Keyboard shortcuts** — `n` (new item), `Escape` (close), `?` (help)
- **Batch operations** — multi-select transactions for bulk delete or category change
- **CSV import/export** — export filtered transactions; import with category/account name matching
- **Audit logging** — all admin actions are logged with timestamps, IP addresses, and target users
- **Database backup** — admin one-click download via `VACUUM INTO`, plus automatic scheduled backups (daily/weekly/monthly with retention) written to `data/backups/`
- **PWA support** — installable as a Progressive Web App with offline caching and service worker
- **API documentation** — interactive Swagger UI at `/api/docs`
- **Soft deletes** — all entities support soft deletion with `deleted_at` timestamps
- **Single binary** — compiles to a single Go binary that serves the SvelteKit SPA as static files

## Tech Stack

| Layer    | Technology                        |
|----------|-----------------------------------|
| Backend  | Go (stdlib `net/http`, no framework) |
| Database | SQLite (WAL mode, via `modernc.org/sqlite`) with scheduled `VACUUM INTO` backups |
| Frontend | SvelteKit 2, Svelte 5, TypeScript |
| Charts   | ECharts 6                         |
| Auth     | bcrypt + bearer tokens + TOTP (`pquerna/otp`) |
| Tests    | Go `testing` + `httptest`, Vitest + jsdom |
| Mobile   | Android (Kotlin, Jetpack Compose, Room + SQLCipher, Hilt, WorkManager) |

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

- Go 1.26+
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

### Proxmox VE (LXC) deployment

InSitu Ledger fits comfortably in a small unprivileged LXC container on Proxmox VE. The single Go binary, embedded SPA, and on-disk SQLite mean there are no external services to provision.

**Recommended sizing** (single-user / small household):

| Resource    | Alpine 3.21 | Debian 12  | Notes |
|-------------|-------------|------------|-------|
| vCPUs       | 1           | 1          | Bursts during CSV import / report generation |
| RAM         | 256 MB      | 512 MB     | Backend idles around ~30 MB |
| Swap        | 256 MB      | 256 MB     | |
| Disk        | 4 GB        | 4 GB       | OS + binary + years of data; grows with `data/backups/` retention |
| Unprivileged| yes         | yes        | |
| Network     | bridged     | bridged    | Static IP recommended for the reverse-proxy upstream |

**Option A — Docker-in-LXC** (matches the published `Dockerfile`):

1. On the PvE host, enable nesting and keyctl on the container so Docker can run inside it:
   ```
   pct set <vmid> -features nesting=1,keyctl=1
   ```
2. Inside the container, install Docker (`apk add docker docker-cli-compose` on Alpine, or follow the official docker-ce instructions on Debian) and enable the service.
3. Clone the repository (or copy `Dockerfile` + `docker-compose.yml`) and run:
   ```
   docker compose up -d --build
   ```
4. Back up the named volume (`<project>_ledger_data`) via PvE's container backups or a separate snapshot job.

**Option B — Native binary** (no Docker, smallest footprint):

The Go binary serves the SvelteKit SPA from a `static/` directory next to its working directory. Build both on a workstation (avoids installing Node and Go inside the LXC), then ship the artefacts across.

**Step 1 — On your workstation: build binary + SPA**

```bash
git clone https://github.com/pstivanin/insitu-ledger.git
cd insitu-ledger

# Build the SPA (output lands in frontend/build/)
( cd frontend && npm ci && npm run build )

# Build a static Linux/amd64 binary
( cd backend && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
    go build -trimpath -ldflags="-s -w" \
    -o insitu-ledger ./cmd/server )

# Stage the layout the runtime expects (binary + ./static/) and tar it up
rm -rf /tmp/insitu-stage && mkdir -p /tmp/insitu-stage/static
cp backend/insitu-ledger /tmp/insitu-stage/
cp -r frontend/build/. /tmp/insitu-stage/static/
tar -C /tmp/insitu-stage -czf /tmp/insitu-ledger.tgz .

# Ship it to the LXC (replace 10.0.0.42 with the container's IP)
scp /tmp/insitu-ledger.tgz root@10.0.0.42:/tmp/
```

**Step 2a — Inside a Debian 12 LXC**

```bash
# Minimal runtime deps (the binary is static; only need ca-certs + tzdata)
apt-get update
apt-get install -y --no-install-recommends ca-certificates tzdata

# Dedicated unprivileged service user with no shell and no home
adduser --system --group --no-create-home --shell /usr/sbin/nologin insitu

# Lay out files: binary + SPA in /opt/insitu-ledger, data in /var/lib/insitu-ledger
install -d -o root   -g root   -m 0755 /opt/insitu-ledger
install -d -o insitu -g insitu -m 0750 /var/lib/insitu-ledger
tar -xzf /tmp/insitu-ledger.tgz -C /opt/insitu-ledger
chmod 0755 /opt/insitu-ledger/insitu-ledger

# Write the systemd unit
cat >/etc/systemd/system/insitu-ledger.service <<'EOF'
[Unit]
Description=InSitu Ledger (self-hosted personal finance tracker)
Documentation=https://github.com/pstivanin/insitu-ledger
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=insitu
Group=insitu
WorkingDirectory=/opt/insitu-ledger
ExecStart=/opt/insitu-ledger/insitu-ledger
Restart=on-failure
RestartSec=5s

Environment=INSITU_ADDR=:8080
Environment=INSITU_DATA_DIR=/var/lib/insitu-ledger
# Uncomment only if a trusted reverse proxy fronts this service:
#Environment=INSITU_TRUST_PROXY=true

# Hardening
NoNewPrivileges=true
PrivateTmp=true
PrivateDevices=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
RestrictNamespaces=true
RestrictRealtime=true
LockPersonality=true
SystemCallArchitectures=native
ReadWritePaths=/var/lib/insitu-ledger
CapabilityBoundingSet=
AmbientCapabilities=

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now insitu-ledger

# Grab the one-shot admin password printed on first boot
journalctl -u insitu-ledger --no-pager | grep -i "initial admin password"
```

**Step 2b — Inside an Alpine 3.21 LXC**

```bash
# Runtime deps (binary is static; tzdata for local-time scheduling)
apk add --no-cache ca-certificates tzdata

# Dedicated unprivileged service user
addgroup -S insitu
adduser -S -D -H -G insitu -s /sbin/nologin insitu

# Lay out files: binary + SPA in /opt/insitu-ledger, data in /var/lib/insitu-ledger
install -d -o root   -g root   -m 0755 /opt/insitu-ledger
install -d -o insitu -g insitu -m 0750 /var/lib/insitu-ledger
tar -xzf /tmp/insitu-ledger.tgz -C /opt/insitu-ledger
chmod 0755 /opt/insitu-ledger/insitu-ledger

# Write the OpenRC init script
cat >/etc/init.d/insitu-ledger <<'EOF'
#!/sbin/openrc-run

name="insitu-ledger"
description="InSitu Ledger (self-hosted personal finance tracker)"
directory="/opt/insitu-ledger"
command="/opt/insitu-ledger/insitu-ledger"
command_user="insitu:insitu"
command_background="yes"
pidfile="/run/${RC_SVCNAME}.pid"
output_log="/var/log/${RC_SVCNAME}.log"
error_log="/var/log/${RC_SVCNAME}.log"

export INSITU_ADDR=":8080"
export INSITU_DATA_DIR="/var/lib/insitu-ledger"
# export INSITU_TRUST_PROXY="true"   # only if behind a trusted reverse proxy

depend() {
    need net
    after firewall
}

start_pre() {
    checkpath -f -m 0640 -o insitu:insitu "$output_log"
}
EOF
chmod +x /etc/init.d/insitu-ledger

rc-update add insitu-ledger default
rc-service insitu-ledger start

# Grab the one-shot admin password printed on first boot
grep -i "initial admin password" /var/log/insitu-ledger.log
```

**Step 3 — Terminate TLS upstream**

Point Caddy / nginx (on the PvE host or in a separate LXC) at `http://<lxc-ip>:8080`. Set `INSITU_TRUST_PROXY=true` only after the proxy is in place and stripping inbound `X-Forwarded-For`.

**Upgrades**: rebuild + retar on your workstation, `scp` to `/tmp/`, then:

```bash
# Wipe old SPA assets so removed files don't linger
rm -rf /opt/insitu-ledger/static
tar -xzf /tmp/insitu-ledger.tgz -C /opt/insitu-ledger
chmod 0755 /opt/insitu-ledger/insitu-ledger

systemctl restart insitu-ledger    # Debian
# rc-service insitu-ledger restart # Alpine
```

The SQLite schema migrates automatically on startup; back up `/var/lib/insitu-ledger/` (or take a PvE container snapshot) before upgrading.

### Configuration

| Flag / Env Var              | Default   | Description                  |
|-----------------------------|-----------|------------------------------|
| `-addr` / `INSITU_ADDR`     | `:8080`   | Listen address               |
| `-data` / `INSITU_DATA_DIR` | `./data`  | Directory for the SQLite database (and `backups/` subdirectory) |
| `INSITU_TRUST_PROXY`        | `false`   | When `true`, trust `X-Forwarded-For` for client IP (use only behind a reverse proxy that sets it) |

Environment variables take precedence over flags.

### First-boot admin

When the backend starts against an empty database, it creates a single admin user (`admin@localhost`) and prints a randomly generated initial password to **stderr**, once. Capture it from your container/service logs and change it on first login.

## Security & Privacy

- See [`SECURITY.md`](SECURITY.md) for the threat model, supported versions, and how to report a vulnerability.
- See [`PRIVACY.md`](PRIVACY.md) for what data the software collects (spoiler: nothing leaves your server / device).

A few notes worth surfacing:

- The web UI stores its bearer token in `localStorage`. This is the standard SPA tradeoff; an XSS in any frontend dependency would be able to read it. Mitigations: a strict Content-Security-Policy is set, and dependencies are kept current.
- The backend writes scheduled backups to `data/backups/` on the same volume as the live database. Off-host copies (PvE backup, restic, rclone, etc.) are the operator's responsibility — that's the actual disaster-recovery story.
- The Android app encrypts its Room database at rest with SQLCipher; the key is stored in the Android Keystore and is non-exportable. Local manual backups (via SAF) are encrypted with a user-chosen passphrase (PBKDF2 + AES-256-GCM).
- The Android app intentionally lets the home-screen widget add a transaction without biometric unlock. The widget never displays existing data.
- The Android app keeps `minSdk = 34` (Android 14). This is a deliberate choice in exchange for relying on modern platform security primitives (Keystore-bound DB encryption, predictive back, edge-to-edge); it does narrow the supported device range.
