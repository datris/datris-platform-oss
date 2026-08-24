# Release Notes

## v1.20.0 — August 24, 2026

**Tap isolation on by default for docker compose.**

- Compose (and prod compose) now default `USE_TAP_RUNNER=true`. Isolated taps cannot open direct DB / MinIO / Vault connections — they return records; this is existing behavior, called out because isolation is now the compose default.
- `install.sh` / `vault-init` mint `TAP_RUNNER_TOKEN` on first boot. The server refuses to start isolated with an empty or `changeme` token. `sbt`/IDE without the sidecar stays in-process (loud warning). Set `USE_TAP_RUNNER=false` to force in-process.

**Opt-in Postgres TLS enforcement.**

- Set `DATRIS_ENV=production` and the platform refuses to start when its Postgres connection points at an external host without TLS (`sslmode=require` or stricter in the connection URL). Opt out with `DATRIS_ALLOW_PLAINTEXT_DB=true`. The bundled in-network Postgres is exempt. Nothing changes unless you set the flag — existing deployments are unaffected; a plaintext connection to an external database now logs a startup warning either way.

**Observability: Prometheus metrics and structured logs.**

- New `/actuator/prometheus` endpoint exposes server metrics for Prometheus scraping. It is reachable only from inside the deployment's network — the bundled edge proxy does not expose it publicly.
- Production deployments now emit one JSON log line per event, ready for SIEM / log-aggregator ingestion. Development logs stay human-readable.

**Supply chain.**

- Container base images are now pinned by digest and kept current automatically.
- Every release publishes a software bill of materials (CycloneDX SBOM) for each of the four container images.

**Upgrading**

`docker compose pull && docker compose up -d --force-recreate`. **Pull all images together** — a new server with an old tap-runner image will fail tap runs until both are updated. Existing pipelines, taps, and schedules are unaffected. Taps that opened direct connections to the platform's internal databases must use the platform data API instead, or set `USE_TAP_RUNNER=false`.

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
