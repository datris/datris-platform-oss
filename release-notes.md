# Release Notes

## v1.6.19 — May 5, 2026

**Choose your embedding provider independently of your chat provider.**

- **Mix-and-match AI providers.** The embedding slot is now configured separately from the chat and code-generation slots, so you can keep Claude for chat and code generation while pointing embeddings at OpenAI (or vice-versa). Useful when the bundled embedder is too heavy for your host, or when you want a different model family for vector quality vs chat quality. See [AI Configuration](https://docs.datris.ai/ai-configuration) for the full list of options.
- **Existing installs keep their current behavior.** If you don't set an embedding override, the embedding slot continues to follow your chat provider exactly as before — Claude installs keep using the bundled embedder, OpenAI installs keep using OpenAI embeddings. The override is purely opt-in.

**Upgrading**

- Existing installs: `docker compose pull && docker compose up -d --remove-orphans`. No data migration needed.
- If you switch embedding providers on an existing deployment, vector destinations built on the previous embedder will fail-fast with a dimension-mismatch message on the next run. Drop the affected destination tables or collections and re-ingest.

---

See [archived release notes](release-notes/) for prior versions.
