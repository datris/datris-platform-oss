# Release Notes

## v1.6.7 — April 19, 2026

- **Document tap improvements** — document taps now track which files have already been ingested, so re-running a tap against the same source won't re-embed the same documents. Taps are validated up front to catch incompatible pipeline shapes (e.g. pointing a document tap at a relational destination) before the run starts.
- **Faster embeddings** — the bundled Ollama sidecar now handles concurrent embedding requests in parallel and keeps the embedding model warm between pipeline runs, eliminating cold-start delays when a pipeline resumes after an idle period.
- **More Python libraries pre-installed for taps** — AWS S3, Google Cloud Storage, Azure Blob, Excel, YAML, and date/timezone helpers are now baked into the image. Common taps no longer need a per-run `pip install` — they just work.

---

See [archived release notes](release-notes/) for prior versions.
