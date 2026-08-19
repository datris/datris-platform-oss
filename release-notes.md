# Release Notes

## v1.19.0 — August 19, 2026

**Security hardening.**

A broad security pass across the platform — authentication and authorization, credential handling, and input validation — along with updated bundled dependencies to address published advisories. A standard upgrade needs no configuration changes; the notes below cover the few behavior changes worth knowing.

**Upgrade notes**

- **Initial admin login** (only when user login is enabled): the first admin password is now generated automatically and printed once to the server logs. Retrieve it with `docker compose logs datris`, sign in, and change it.
- **Taps that call internal addresses:** taps and outbound endpoints pointed at a loopback or private-network address are now blocked by default. If yours legitimately runs on an internal network, set `DATRIS_ALLOW_PRIVATE_EGRESS=true`.
- **Bundled Vault** now uses a unique per-install token. For manual `vault` commands, read it with `docker compose exec -T datris cat /vault-token/token`.

**Fix**

- The default Docker Compose configuration pulls the published images again (a recent change had it set to build from source).

**Upgrading**

Standard upgrade: `docker compose pull && docker compose up -d`. Existing pipelines, taps, and schedules are unaffected.

---

## v1.18.0 — August 18, 2026

**Build taps in any language — HTTP taps.**

See the [full v1.18.0 notes](release-notes/v1.18.0.md) for details.
