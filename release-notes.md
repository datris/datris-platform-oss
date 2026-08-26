# Release Notes

## v1.21.0 — August 26, 2026

**Live progress feedback across the Assistant and tap building.**

- The Assistant now shows what it's thinking about while it reasons — a live summary streams next to the progress indicator, with an elapsed timer, instead of silent dots. The full reasoning stays available in the expandable thinking block.
- AI tap-script generation shows live progress: the tap wizard and the Assistant's tool cards report what the generation is doing and how long it has been running, instead of a static "this may take a minute."
- The Assistant sets time expectations before long-running operations, and when AI script generation fails for a well-known API it now writes the script directly instead of retrying — turning a multi-minute failure into a quick recovery.
- The Assistant only reports an action as completed when it actually ran and observed the result — tightened guarantees against overstated progress reports.
- Dependency updates across the UI and build tooling.

**Upgrading**

`docker compose pull && docker compose up -d --force-recreate`. No configuration changes required.

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
