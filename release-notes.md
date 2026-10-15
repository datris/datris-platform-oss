# Release Notes

## Unreleased

- **Claude Opus 5.5 is the default chat model for Anthropic.** New installs on the Anthropic and Bedrock paths seed Claude Opus 5.5 for the Assistant and other chat features (it was already the code-generation default). Existing installs keep the model they have; change it any time on Configuration → AI Providers.
- **Busy hosts stay up.** Tap scripts and AI rule scripts now run a bounded number at a time, inside CPU, memory and process limits. When the runner is full, a run waits its turn instead of piling on; its run history shows how long it queued, and `datris doctor` warns when runs had to wait. All limits are adjustable in `.env`.

## v1.49.0 — October 14, 2026

**Bring a restored catalog back to life in one click, and let assistants read whole scripts.**

- **Enable a restored catalog in the right order.** Taps that read tables produced by other taps in the same catalog are now recognised from their scripts (or declared by you), and each catalog can show the order to enable its taps in, with what still blocks each one. Disabled taps on the Catalog tab show their level or which sources they need, a new **Run now** action lands a tested tap's data without enabling it, and the restore result says how many restored taps depend on others. Agents get the same enable order through a new read-only MCP tool.
- **Enable a whole catalog in order with one click.** A new **Enable in order** action on a catalog's card tests, enables and runs each tap in its enable order, waiting for each tap's data to land before starting the taps that read it. Taps whose sources failed are skipped with the reason, progress shows on the card, and the job can be cancelled. Agents can start the same job, and an approval-required agent policy asks for one approval for the whole catalog.
- **Updated object store image.** The bundled MinIO image moves to a newer build with current security patches. Run `docker compose pull && docker compose up -d --force-recreate minio minio-init`; buckets and data are kept.
- **In-app assistants read whole tap scripts.** In-app assistants can now read whole tap scripts and larger tool results, so they can review and fix a long script without asking you to paste it. A tap version's script now also shows for taps stored in a code repository.

**Upgrading**

- The server, UI and MCP server images changed, and the bundled object store image moved to a patched build: run `docker compose pull && docker compose up -d --force-recreate minio minio-init`. Buckets and data are kept.
- No configuration changes are required. The new endpoints, tools and settings are additive; scoped API keys see the new tools only with the matching capability.
