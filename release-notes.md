# Release Notes

## v1.54.2 — October 30, 2026

**Security maintenance release.**

- **Updated object store image.** The bundled MinIO image moves to the current release with the latest security patches. No behaviour change.

**Upgrading:** `docker compose pull && docker compose up -d --force-recreate minio minio-init`; only the object store image changed, and buckets and data are kept. Installer users: re-run the install command to pick up the updated Compose file. No configuration changes are required. CLI users: `pip install -U datris-mcp-server` or `brew upgrade datris`.
