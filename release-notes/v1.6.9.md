# Release Notes

## v1.6.9 — April 20, 2026

- **OpenAI Codex models now work for code generation.** Previously, selecting a codex-family model for CodeGen (e.g., the recommended GPT-5.3-Codex) caused tap-script generation and AI data quality / transformation to fail immediately with a 404. Datris now routes codex models to the right OpenAI endpoint automatically.
- **Vector-store dimension changes now fail fast with a clear message.** If you switch embedding providers (for example, Ollama bge-m3 → OpenAI text-embedding-3-small) on a pipeline whose destination table or collection already has vectors of the old dimension, the job stops up front and tells you exactly what to do instead of blowing up mid-ingest with a cryptic database error. Applies to pgvector, Qdrant, Weaviate, Milvus, and Chroma destinations.
- **Configuration save is honest about missing API keys.** Changing the AI Provider to Anthropic or OpenAI without entering that provider's API key no longer silently skips the save while reporting success — the Configuration page now flags the missing key and tells you which one to add.
- **Create Tap brainstorm wraps up sooner.** For document taps, the AI assistant no longer drills for optional date filters once you've supplied the source, auth, and a broad scope — the tap ledger already dedupes by content, so those extra questions were just noise.

---

See [archived release notes](release-notes/) for prior versions.
