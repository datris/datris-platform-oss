# Release Notes

## v1.23.0 — August 28, 2026

**Audit log: who did what, by login or API key.**

- A new opt-in Audit Log records every create, change, run, delete, login, and denied request — humans by their login, agents by their API key, scheduled runs as system. Admins read it under Configuration → Audit Log with filters by time, category, actor, and outcome, a detail view per entry, and CSV export. Entries are also written to the server log so an existing log aggregator picks them up.
- Actions the Assistant takes for you are attributed to you — in the audit log and in pipeline and tap version history — instead of to the platform's internal key. Every issued API key now carries a stable id, so a key that is revoked and later re-issued under the same name is never confused with its predecessor.
- Reads are left out by default to keep the trail focused on changes; turn them on to see every query and search an agent runs. Reading a secret is always recorded.
- Tap scripts that read platform data now work when API keys are required. Each run gets a short-lived, read-only credential that is attached automatically — scripts need no changes and no platform credential in their secret — and the audit log names the tap that made each call.

**Upgrading**

`docker compose pull && docker compose up -d --force-recreate`. No configuration changes required. To turn on the audit log, add `USE_AUDIT_LOG=true` to your `.env` and recreate the `datris` container; `AUDIT_LOG_RETENTION_DAYS` (default 90) and `AUDIT_LOG_READS` (default false) tune it.

---

## v1.22.0 — August 27, 2026

**Set real column types on pipelines that landed as text.**

See the [full v1.22.0 notes](release-notes/v1.22.0.md) for details.

---

## v1.21.0 — August 26, 2026

**Live progress feedback across the Assistant and tap building.**

See the [full v1.21.0 notes](release-notes/v1.21.0.md) for details.

---

## v1.20.2 — August 25, 2026

**Security refresh across all container images.**

See the [full v1.20.2 notes](release-notes/v1.20.2.md) for details.

---

## v1.20.1 — August 25, 2026

**MCP tool catalog now matches API-key permissions.**

See the [full v1.20.1 notes](release-notes/v1.20.1.md) for details.

---

## v1.20.0 — August 24, 2026

**Tap isolation on by default, opt-in Postgres TLS, Prometheus metrics + JSON logs, digest-pinned images + SBOMs.**

See the [full v1.20.0 notes](release-notes/v1.20.0.md) for details.

---

## v1.19.4 — August 21, 2026

**Dependency security cleanup across the server and UI.**

See the [full v1.19.4 notes](release-notes/v1.19.4.md) for details.

---

## v1.19.3 — August 21, 2026

**Server security updates and a friendlier vector-store default in the installer.**

See the [full v1.19.3 notes](release-notes/v1.19.3.md) for details.

---

## v1.19.2 — August 20, 2026

**Security updates across the UI and server, and a pipeline-delete fix.**

See the [full v1.19.2 notes](release-notes/v1.19.2.md) for details.
