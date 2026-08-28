# Release Notes

## v1.22.0 — August 27, 2026

**Set real column types on pipelines that landed as text.**

- Pipelines created through agents store every destination column as text. The Catalog now marks such pipelines (PostgreSQL, Snowflake, and Databricks destinations) with a quiet "text" badge — click it to review proposed column types and apply them. The same action is available from the pipeline view.
- Proposed types are inferred from the data already loaded, and every column shows real sample values so you can check the proposal at a glance. When a column stays text because of a stray value, the dialog shows the offending value and the type it blocked (for example: found "N/A", would otherwise be a number) so overriding is an informed choice.
- Applying is safe by design: every loaded value is validated first, the destination table is retyped, and the pipeline definition is updated as a new version. A value that won't convert fails the whole apply with the column named and nothing changed. From then on, incoming data is type-checked on every load.
- Agents get the same capability through two new MCP tools, `get_dest_types` and `apply_dest_types` — applying always requires the user's explicit approval.

**Upgrading**

`docker compose pull && docker compose up -d --force-recreate`. No configuration changes required.

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
