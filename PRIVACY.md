# Privacy Policy

InSitu Ledger is **self-hosted software**. We — the developers — never see your data. This policy describes what data the software handles and where it goes when you run it.

## Self-hosted backend (Go server + web UI)

When you run the backend on your own server:

- **All data is stored locally** in a single SQLite file under your configured data directory (default `./data/insitu-ledger.db`).
- **No telemetry, no analytics, no crash reporting** is sent anywhere by the backend or the web UI.
- **Logs** (HTTP access log + structured application log) are written to stderr. They include request method, path, response status, duration, and the requester IP. They do **not** include passwords, TOTP codes, bearer tokens, or transaction contents.
- **Audit log** (in the database) records administrative actions (create/delete user, reset password, toggle admin, disable TOTP, backup) and data-access events (export, sync). This log is visible only to admins.
- **TLS** is provided by the reverse proxy you put in front of the backend; the backend itself speaks HTTP. See `README.md` for setup.

## Android app

The Android app is **local-first**:

- On install, all data lives **only on your device**, in a Room SQLite database encrypted at rest with a key bound to the Android Keystore.
- The app starts no network requests until you opt in to "Connect to Server" in Settings.
- **No analytics, no crash reporting, no advertising IDs** are collected.
- **Permissions requested**: `INTERNET` and `ACCESS_NETWORK_STATE` (only used when sync is configured).
- **Cleartext network traffic** is allowed only for the host you configure as your sync server, so that LAN HTTP setups (`http://192.168.x.y:8080`) work. All other hosts are denied cleartext.
- **Backup files** (created via Settings → Export) are encrypted with AES-GCM using a passphrase you supply at export time. We cannot recover a backup if you forget the passphrase.
- **The home-screen widget** lets anyone holding the unlocked device add a new transaction without biometric. It does not display existing data.

## Data shared between the Android app and your self-hosted backend (if sync is enabled)

If you turn on sync in Settings:

- The app sends transaction, account, category, scheduled-transaction, and shared-access data to **only** the backend URL you configured.
- Authentication uses a bearer token issued by your backend. The token is stored in `EncryptedSharedPreferences` on the device.
- Optional mTLS client certificates can be loaded from the system credential store.

## What we (the maintainers) collect

**Nothing.** This project has no hosted service. Bug reports and security advisories are filed manually on GitHub by users who choose to.

## Changes to this policy

This file lives in the repository. Changes are visible in `git log PRIVACY.md`.
