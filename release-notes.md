# Release Notes

## v1.37.1 — September 24, 2026

**Installs and upgrades work again after MinIO closed its public image registry.**

- **MinIO images now come from Chainguard.** MinIO withdrew its images from Docker Hub earlier this month and, as of September 24, put its quay.io images behind a login, so every fresh install and every `docker compose pull` failed with "unauthorized: access to the requested resource is not authorized". The Compose files now pull a multi-arch MinIO build from Chainguard's public registry, pinned to a digest. Running deployments were never affected. The installer, the standalone Compose file and a fresh clone all pick this up automatically, and existing object-storage data is kept.

**Upgrading**

Run `docker compose pull && docker compose up -d --force-recreate`. Nothing else changes in this release; the server, UI, MCP server and tap runner images are rebuilt only so every image carries the same version.
