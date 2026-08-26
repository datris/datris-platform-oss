# Release Notes

## v1.20.2 — August 25, 2026

**Security refresh across all container images.**

- All four container images have been rebuilt on updated bases with refreshed OS packages and Python tooling, clearing every published high- and medium-severity vulnerability finding against the images.
- Every container now reports its own health status, so `docker ps` (and any orchestrator) shows healthy/unhealthy per service out of the box.

**Upgrading**

`docker compose pull && docker compose up -d --force-recreate`. No configuration changes required.

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
