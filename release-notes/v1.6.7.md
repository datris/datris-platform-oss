# Release Notes

## v1.6.7 — April 19, 2026

### Document Taps (new)

A new kind of tap purpose-built for feeding vector-store pipelines with unstructured files — PDFs, Word docs, HTML, plain text, and similar. Describe the source in plain English (e.g. *"ingest all PDFs from our SharePoint legal folder"* or *"pull every DOCX from the `legal-contracts/2026/` prefix in S3"*) and the platform generates a script that discovers the files and hands their raw bytes to the pipeline. Text extraction, chunking, embedding, and loading into the vector store are handled downstream — you don't configure any of that on the tap.

- **Tap type toggle in Create Tap.** Choose *Document Ingestion* or *Structured/Semi-Structured* on the first step of the wizard. The prompts, placeholders, and example instructions adapt to the choice.
- **Ingestion ledger.** Every discovered file is tracked by URI and content hash. Re-running the tap skips files that are already up to date — no re-embedding, no duplicates in the vector store. Changed files (new hash) flow through normally.
- **Pre-flight validation.** When a document tap is linked to a pipeline, the platform verifies the pipeline is shaped for document ingestion (unstructured source, vector-store destination) before the tap runs. Misconfigurations are caught at save time with an actionable error instead of failing mid-run with a cryptic exception.
- **Safe defaults for local paths.** Document taps refuse to silently walk arbitrary host directories if a requested path isn't mounted into the container — they fail loudly instead of poisoning the vector store with unintended files.

### Performance

- **Faster embeddings.** The bundled Ollama sidecar now handles concurrent embedding requests in parallel and keeps the embedding model warm between pipeline runs, eliminating the cold-start delay that used to appear when a pipeline resumed after an idle period.

### Quality of life

- **More Python libraries pre-installed for taps.** AWS S3, Google Cloud Storage, Azure Blob, Excel (`.xlsx`), YAML, and common date/timezone helpers are now baked into the image. Taps that fetch from these sources no longer need a per-run `pip install` — they just work on the first try.

---

See [archived release notes](release-notes/) for prior versions.
