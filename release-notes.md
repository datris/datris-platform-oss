# Release Notes

## v1.5.7 — April 9, 2026

Trial-instance hardening and Configuration tab polish.

### Trial deployment hardening

Additional server-side hardening for trial deployments. No action required and no behavior change for self-hosted or dedicated deployments.

### Trial codegen now runs on Haiku 4.5

Trial provisioning previously seeded `{env}/codegen` with Claude Opus 4.6 — every tap-script generation, AI DQ rule, AI transformation, and NL→SQL on a free trial was burning Opus tokens against Datris's shared key. Trial codegen now defaults to `claude-haiku-4-5-20251001`, matching the chat model and dramatically reducing per-trial cost. The shared trial key behavior is unchanged for self-hosted deployments — Opus remains the recommended codegen default for customers running on their own keys.

### Configuration tab — trial banner refinements

- The "AI Configuration is locked on the trial." banner copy is tightened: dropped the redundant "During the free trial" preamble, broadened the "dedicated instance unlocks" pitch from "your own isolated Postgres database" to cover every supported destination category (relational, document, vector, object storage), and updated the model list to reflect Haiku for both chat and codegen.
- The "datris.ai dashboard" link in the banner now uses the page accent color (`#00b4ff`) with an underline so it's clearly clickable against the dim banner body, instead of inheriting the body color and disappearing.

### Upgrading from v1.5.6

No `application.yaml` or Vault changes required. Pull the new images and restart:

```sh
docker compose pull datris ui
docker compose up -d datris ui
```

Multi-tenant trial deployments will pick up the security guard automatically once the `datris` container restarts. There is no migration step.

### Version

- Server: 1.5.7
- MCP Server: 1.5.7
- CLI: 1.5.7

---

## v1.5.6 — April 8, 2026

See [v1.5.6 release notes](release-notes/v1.5.6.md).

## v1.5.5 — April 8, 2026

See [v1.5.5 release notes](release-notes/v1.5.5.md).

## v1.5.4 — April 7, 2026

See [v1.5.4 release notes](release-notes/v1.5.4.md).

## v1.5.3 — April 3, 2026

See [v1.5.3 release notes](release-notes/v1.5.3.md).

## v1.5.2 — April 3, 2026

See [v1.5.2 release notes](release-notes/v1.5.2.md).

## v1.5.1 — April 3, 2026

See [v1.5.1 release notes](release-notes/v1.5.1.md).

## v1.5.0 — April 2, 2026

See [v1.5.0 release notes](release-notes/v1.5.0.md).

## v1.4.4 — March 30, 2026

See [v1.4.4 release notes](release-notes/v1.4.4.md).

## v1.4.3 — March 29, 2026

See [v1.4.3 release notes](release-notes/v1.4.3.md).

## v1.4.2 — March 27, 2026

See [v1.4.2 release notes](release-notes/v1.4.2.md).
