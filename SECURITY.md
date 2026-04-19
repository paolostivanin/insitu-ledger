# Security Policy

## Supported Versions

InSitu Ledger releases security fixes for the latest minor version of each component.

| Component         | Supported version |
|-------------------|-------------------|
| Backend + Frontend | latest `1.x`     |
| Android app        | latest `1.x`     |

Older versions do not receive backports. Self-hosters are expected to keep up with the current release.

## Reporting a Vulnerability

Please report security issues **privately** via GitHub Security Advisories:

> https://github.com/paolostivanin/insitu-ledger/security/advisories/new

Do **not** open public issues for security problems.

Include in your report:

- A description of the issue and the impact
- Steps to reproduce (or a proof-of-concept)
- The component (backend, frontend, Android) and version affected
- Whether you would like to be credited in the advisory

We aim to acknowledge reports within 7 days and to ship a fix or mitigation within 30 days for high-severity issues.

## Threat Model

InSitu Ledger is a **self-hosted** personal-finance app. The threat model assumes:

- **The operator controls the server.** We do not protect data from a malicious operator with shell access to the host.
- **TLS is provided by a reverse proxy.** The backend speaks plain HTTP; HTTPS termination, certificate management, and HSTS at the edge are the operator's responsibility. The backend additionally sets HSTS response headers to encourage browser-side enforcement when reached over HTTPS.
- **The Android app is local-first.** Without sync configured, no data ever leaves the device.

### What we protect against

- Anonymous network attackers attempting to read or modify another user's data
- Brute-force attacks on the login endpoint (rate-limited per IP)
- TOTP brute-force (per-user attempt counter)
- XSS attempts: a Content-Security-Policy is set; user input is rendered with Svelte's default escaping
- SQL injection: all queries are parameterized
- Compromise of the on-disk database leaking active sessions (bearer tokens are stored as SHA-256 hashes, not plaintext)
- Local data theft on Android: the Room database is encrypted with a Keystore-bound key

### What is **not** in scope

- Side-channel attacks against bcrypt or TOTP verification timing
- Attacks that require the device unlock code (Android: anyone holding an unlocked device can use the home-screen widget to add a transaction; this is intentional for usability)
- Attacks that require already-compromised browser extensions (the bearer token lives in `localStorage` for the SPA; this is the standard SPA tradeoff and is documented in `README.md`)
- Multi-tenant operator-vs-user separation; the app assumes a single trusted operator
