# Release Notes

## Unreleased — August 24, 2026

**Tap isolation on by default for docker compose.**

- Compose (and prod compose) now default `USE_TAP_RUNNER=true`. Isolated taps cannot open direct DB / MinIO / Vault connections — they return records; this is existing behavior, called out because isolation is now the compose default.
- `install.sh` / `vault-init` mint `TAP_RUNNER_TOKEN` on first boot. The server refuses to start isolated with an empty or `changeme` token. `sbt`/IDE without the sidecar stays in-process (loud warning). Set `USE_TAP_RUNNER=false` to force in-process.

---

## v1.19.4 — August 21, 2026

**Dependency security cleanup across the server and UI.**

- Updated server and UI dependencies to resolve every remaining published medium-severity advisory (and one low). One low-severity advisory remains open upstream with no fixed release available; it will be picked up as soon as a fix ships. No functional changes intended.
- The Activity tab's charts moved to the current major version of the charting library as part of the security updates — same charts, slightly refreshed default colors.

**Upgrading**

Standard upgrade: `docker compose pull && docker compose up -d`. Existing pipelines, taps, and schedules are unaffected.

---

## v1.19.3 — August 21, 2026

**Server security updates and a friendlier vector-store default in the installer.**

See the [full v1.19.3 notes](release-notes/v1.19.3.md) for details.

---

## v1.19.2 — August 20, 2026

**Security updates across the UI and server, and a pipeline-delete fix.**

See the [full v1.19.2 notes](release-notes/v1.19.2.md) for details.
