# Release Notes

## v1.6.5 — April 17, 2026

Remote model catalog so new AI models show up in Configuration dropdowns without cutting a platform release.

### Release-free model catalog

The AI Provider, CodeGen Provider, and Embedding Provider dropdowns in the Configuration tab no longer read from hardcoded arrays. They now fetch a catalog from a backend proxy that forwards to `https://datris.ai/models.json`. Adding a new OpenAI or Anthropic model becomes an edit to the website's JSON file plus a push — no SBT build, no Docker image, no customer upgrade.

- New `GET /api/v1/ai/model-catalog` endpoint with a 5-minute server-side cache that serves stale on upstream failure.
- New `ModelCatalogService` on the UI side, fetched before Configuration secrets load; silent fallback to a baked-in default list when the proxy is unreachable.
- Trial provisioning (datris.ai website) now seeds Vault with the recommended model from the same JSON source; `TRIAL_AI_MODEL_*` env vars still override.
- Added Claude Opus 4.7 as the recommended Anthropic CodeGen model.

### Notes

The catalog lives in the datris.ai website repo (`src/data/models.json`) and is served by a short-TTL Astro route — chosen over the docs site after a CDN edge-cache stalled updates there.
