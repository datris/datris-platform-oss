# Release Notes

## vNEXT — unreleased

- **Updated object store image.** The bundled MinIO image moves to the current release with the latest security patches. Run `docker compose pull && docker compose up -d --force-recreate minio minio-init`; buckets and data are kept.

## v1.54.1 — October 29, 2026

**Security maintenance release.**

- **Security updates.** Refreshed the server's bundled libraries and the UI's build-time dependencies to patched versions, clearing the open vulnerability reports against the published images. No behaviour change.

**Upgrading:** `docker compose pull && docker compose up -d`; the server and UI images changed. No configuration changes are required. CLI users: `pip install -U datris-mcp-server` or `brew upgrade datris`.
