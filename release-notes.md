# Release Notes

## v1.19.3 — August 21, 2026

**Server security updates and a friendlier vector-store default in the installer.**

- Upgraded the server's embedded web framework to its current supported release line, resolving all remaining published high-severity advisories against server dependencies (and a number of medium- and low-severity ones). No functional changes intended.
- **The installer now presents pgvector as the default vector store.** It ships inside the bundled Postgres, so vector search works out of the box with no extra container. Additional vector stores (Qdrant, Weaviate, Chroma, Milvus) remain available as opt-in additions at install time. Existing installs are unaffected — this changes prompts and the install summary only.

**Upgrading**

Standard upgrade: `docker compose pull && docker compose up -d`. Existing pipelines, taps, and schedules are unaffected.

---

## v1.19.2 — August 20, 2026

**Security updates across the UI and server, and a pipeline-delete fix.**

See the [full v1.19.2 notes](release-notes/v1.19.2.md) for details.
