# Release Notes

## vNEXT — unreleased

- Uploads dropped in the root of the raw bucket are now ingested
- One bad file no longer stops the other files uploaded at the same time

## v1.38.1 — September 25, 2026

**Installs no longer depend on a third-party registry staying public.**

- **MinIO ships from the same place as the rest of the platform.** The object store image is now published alongside the other Datris images, so a fresh install or `docker compose pull` no longer depends on a third-party registry staying public. Existing object-storage data is kept.

**Upgrading**

Run `docker compose pull && docker compose up -d --force-recreate`. The `minio` and `minio-init` containers are recreated because the image changed; buckets, event notifications and existing object-storage data are kept. The installer, the standalone Compose file and a fresh clone all pick this up automatically.
