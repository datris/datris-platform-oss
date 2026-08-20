# Release Notes

## v1.19.2 — August 20, 2026

**Security updates across the UI and server, and a pipeline-delete fix.**

- Upgraded the web UI's underlying framework to the current long-term-support line, resolving all published critical- and high-severity advisories affecting the UI. No visual or functional changes intended.
- Updated bundled server dependencies to resolve the remaining published high-severity advisories against third-party libraries. No functional changes.
- **Deleting a pipeline now also removes its data from the built-in object store destination**, matching the behavior of every other destination type. Previously the written files were left behind, and recreating a pipeline with the same output prefix would silently pick them up. If two pipelines share an output prefix, the delete safely skips the shared data. Files orphaned by deletes made on earlier versions are not removed retroactively — clear those once by hand if needed.
- Hardened the UI container image configuration. No behavior change.

**Upgrading**

Standard upgrade: `docker compose pull && docker compose up -d`. Existing pipelines, taps, and schedules are unaffected.

---

## v1.19.1 — August 20, 2026

**Dependency security updates and an install fix.**

See the [full v1.19.1 notes](release-notes/v1.19.1.md) for details.
