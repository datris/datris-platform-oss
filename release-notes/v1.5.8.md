# Release Notes

## v1.5.8 — April 10, 2026

Ollama support for all AI configurations and hot-reload on save.

### Ollama as a provider for all 3 AI configs

The Configuration UI now offers **Ollama (local)** as a provider choice for AI Primary, CodeGen, and Embedding — enabling a fully local AI setup with no cloud API keys. When Ollama is selected, the model field switches to a free-text input so you can type any model you have available (e.g. `qwen3:14b`, `qwen2.5-coder:7b-instruct`).

For Embedding, two Ollama options are available:
- **Ollama (local, bundled)** — uses the bundled docker-compose sidecar at `ollama:11434`, pre-fills `bge-m3`
- **Ollama (local)** — for your own Ollama instance at `host.docker.internal:11434`, any embedding model

The backend already supported Ollama for all three slots since v1.5.6. This release exposes it in the UI and fixes `loadTenantAiConfig` to accept empty `apiKey` for Ollama configs.

### Hot-reload AI configuration on save

Saving AI configuration from the Configuration UI now takes effect immediately — **no server restart required**. Previously, `ai-primary` and `codegen` configs were cached at startup and required a container restart to pick up changes. The server now reloads these configs from Vault after each PUT to `/secrets/ai-primary` or `/secrets/codegen`.

### Provider switching remembers your model

When switching between providers in the Configuration UI, the previously entered model is stashed and restored when you switch back. Switching to Ollama clears the model field (since it uses free-text input); switching back to Anthropic/OpenAI restores the dropdown selection.

### Upgrading from v1.5.7

No `application.yaml` or Vault changes required. Pull the new images and restart:

```sh
docker compose pull datris ui
docker compose up -d datris ui
```

### Version

- Server: 1.5.8
- MCP Server: 1.5.8
- CLI: 1.5.8

---

## v1.5.7 — April 9, 2026

See [v1.5.7 release notes](release-notes/v1.5.7.md).

## v1.5.6 — April 8, 2026

See [v1.5.6 release notes](release-notes/v1.5.6.md).

## v1.5.5 — April 8, 2026

See [v1.5.5 release notes](release-notes/v1.5.5.md).

## v1.5.4 — April 7, 2026

See [v1.5.4 release notes](release-notes/v1.5.4.md).

## v1.5.3 — April 3, 2026

See [v1.5.3 release notes](release-notes/v1.5.3.md).

## v1.5.2 — April 3, 2026

See [v1.5.2 release notes](release-notes/v1.5.2.md).

## v1.5.1 — April 3, 2026

See [v1.5.1 release notes](release-notes/v1.5.1.md).

## v1.5.0 — April 2, 2026

See [v1.5.0 release notes](release-notes/v1.5.0.md).

## v1.4.4 — March 30, 2026

See [v1.4.4 release notes](release-notes/v1.4.4.md).

## v1.4.3 — March 29, 2026

See [v1.4.3 release notes](release-notes/v1.4.3.md).

## v1.4.2 — March 27, 2026

See [v1.4.2 release notes](release-notes/v1.4.2.md).
