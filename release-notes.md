# Release Notes

## v1.29.1 — September 14, 2026

**Installs and upgrades work again.**

- **MinIO images now come from quay.io.** MinIO removed its images from Docker Hub, which made every fresh install and every `docker compose pull` fail with "pull access denied for minio/minio". The Compose files now pull the identical images from quay.io. Running deployments were never affected; only the download source changed. The installer, the standalone Compose file and a fresh clone all pick this up automatically.
- **Docs: the production trail.** A new Production section walks a deployment from first install to a governed, audited, agent-operated setup, and the Quick Start now ends on an approval and a provenance lookup rather than a landed file.

**Upgrading**

Run `datris doctor --pre-upgrade` first, then `docker compose pull && docker compose up -d --force-recreate`. Compose recreates the `minio` container on the new image name; your data volume re-attaches. No configuration changes required.
