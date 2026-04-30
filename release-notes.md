# Release Notes

## v1.6.15 — April 30, 2026

**Smaller, faster default install. Lighter download, opt-in Kafka, vector ingestion fixes.**

- **~58% smaller `docker compose pull`.** The bundled platform now downloads roughly 11 GB less out of the box. Fresh installs come up dramatically faster.
- **Bundled embeddings are faster on the same `bge-m3` model.** No configuration changes needed; existing vector collections built with the previous bundled embedder continue to work without re-embedding.
- **Kafka is now opt-in.** Most local installs don't need it, so Kafka, Zookeeper, and the Kafka UI ship commented out in `docker-compose.yml`. Uncomment the bundled blocks (and the related volumes at the top of the file) to enable them. Pipelines that point at external Kafka brokers are unaffected.
- **Vector ingestion no longer fails with a "duplicate key" error when many documents land at once.** Concurrent document loaders previously raced on creating the `pgvector` extension; the race is now serialized.
- **Vector ingestion no longer fails on embedding providers that limit batch size.** The chunk batch size is now configurable per embedding secret (`batchSize`), with a cross-provider-safe default. OpenAI users who want to maximize throughput can set this higher.
- **Configuration tab clarifies optional providers.** The bundled embedding option is labeled `bge-m3 (bundled)`. The AI Provider, CodeGen Provider, and Embedding Provider dropdowns each indicate that local Ollama is opt-in.
- **Service Health no longer shows "Down" for optional services that were never enabled.** Kafka and the optional vector databases (Qdrant, Weaviate, Milvus, Chroma) now correctly report "Not Configured" until you turn them on. Existing installs may still show "Down" until their stale Vault secrets are removed.

**Upgrading**

- New installs: nothing to do.
- Existing installs: pull the new images and re-run `docker compose up -d`. Vault is auto-seeded on first start.
- If you were relying on the bundled local Kafka broker for a pipeline, uncomment the Kafka services in your `docker-compose.yml` after upgrading.

---

See [archived release notes](release-notes/) for prior versions.
