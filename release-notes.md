# Release Notes

## v1.19.1 — August 20, 2026

**Dependency security updates and an install fix.**

- Updated bundled server dependencies to resolve all critical published advisories against third-party libraries, and removed an unused legacy dependency tree from the server entirely. No functional changes.
- Fresh installs now create the default object-store output bucket automatically. Previously, the first pipeline that wrote to the built-in object store with the default settings failed until the bucket was created by hand.
- Removed an unused legacy bucket from install-time provisioning. Existing installs that already have it are unaffected.

**Upgrading**

Standard upgrade: refresh your checkout (or re-download `docker-compose.standalone.yml`), then `docker compose pull && docker compose up -d`. The default output bucket is created automatically at startup. If you update images without refreshing the Compose files, create a bucket named `{environment}-data` (default: `oss-data`) once in the MinIO console. Existing pipelines, taps, and schedules are unaffected.

---

## v1.19.0 — August 19, 2026

**Security hardening.**

See the [full v1.19.0 notes](release-notes/v1.19.0.md) for details.
