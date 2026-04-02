# Release Notes

## v1.4.4 — March 30, 2026

### Default AI Provider Changed to Anthropic

The platform now defaults to Anthropic (Claude Sonnet 4.6) instead of Ollama. Updated in both `application.yaml` and `docker/config/application.yaml`:

```yaml
ai:
  provider: "anthropic"
  aiSecretName: "oss/anthropic"
```

Customers can still switch to OpenAI or Ollama by changing the provider and secret name.

### CodeGen Script Content Logging

Generated Python scripts for data quality and transformation are now logged in full to the server logs. This helps debug cases where a script executes successfully but produces unexpected output.

View with:
```bash
docker logs datris 2>&1 | grep -A 50 "script content"
```

### Simplified .env.example

Removed `OLLAMA_MODEL`, `EMBEDDING_PROVIDER`, and `EMBEDDING_MODEL` from `.env.example` — these are managed via Vault secrets, not environment variables. The `.env` file now only contains the two AI provider API keys used for initial Vault seeding.

### Docker Build Scripts Fixed

`scripts/docker-source.sh` and `scripts/docker-hub.sh` now work from any directory (resolve `docker-compose.yml` path relative to the script location).

### Pre-Commit Hook

Added a git pre-commit hook that blocks commits to `main` if `docker-compose.yml` has the `image: datrisai/` lines commented out — prevents accidentally pushing a build-from-source config that would break external users.

### Version

- Server: 1.4.4
- MCP Server + CLI: 1.4.4

---

## v1.4.3 — March 29, 2026

See [v1.4.3 release notes](release-notes/v1.4.3.md).

## v1.4.2 — March 27, 2026

See [v1.4.2 release notes](release-notes/v1.4.2.md).

## v1.4.1 — March 26, 2026

See [v1.4.1 release notes](release-notes/v1.4.1.md).

## v1.4.0 — March 26, 2026

See [v1.4.0 release notes](release-notes/v1.4.0.md).
