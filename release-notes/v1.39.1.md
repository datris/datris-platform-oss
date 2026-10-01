# Release Notes

## v1.39.1 — September 29, 2026

**Security maintenance release.**

- **Security update.** The JSON processing library used by the server is updated to close three recently disclosed advisories, one rated high. No functional changes.

**Upgrading**

Run `docker compose pull && docker compose up -d --force-recreate`. Only the server image changed. No configuration changes required.
