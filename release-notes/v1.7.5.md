# Release Notes

## v1.7.5 — May 22, 2026

**Embedding pipelines stop failing on oversized chunks, the Assistant carries long runs to completion, and the Catalog gets the missing delete buttons and run-history detail.**

- **Embedding pipelines no longer fail when a single chunk is too big.** Every embedding call is now guarded by a token-aware safety net that splits any chunk over the model's input cap before sending — so a 10-Q ingest with one dense table can't take down the whole batch. Works the same for OpenAI, Cohere, Voyage, BGE-M3, Nomic, Mistral, or anything else, with built-in caps for the common models and a conservative default for the rest. OpenAI families get exact token counts; everyone else gets a heuristic.
- **Token-aware chunking.** A new `maxChunkTokens` option on the chunking config tells the chunker to stop merging segments before they cross a token estimate — the safety net above becomes a true last resort. Recommended for any new vector pipeline.
- **The Assistant carries pipeline runs to completion.** When the agent kicks off a tap or upload, it now polls the run to a terminal outcome with exponential backoff instead of summarizing "still running — check back later." You'll see a one-line progress update each cycle and the final outcome reported in chat, including any per-document failures.
- **Run History now shows per-document outcomes.** Expanding a tap run that fed a vector pipeline lists every document the pipeline processed with its own status, elapsed time, and — for failures — the specific stage and error. A run that fetched 28 documents but failed on 1 now shows the failure inline instead of a misleading green "success."
- **Catalog: delete buttons work on individual taps and pipelines.** The trash icon now shows an inline confirm right in the row — Delete / × for taps, Config & Data / Data Only / × for pipelines (Data Only wipes rows but keeps the config so the next ingest fills it again).
- **Catalog: last-run timestamp is back above the status badge.**
- **Agent Monitor fits the viewport.** Both Connections and Activity Log panes now size to the visible area on first load and reflow when you resize the window, not just in the pop-out.

**Upgrading**

- Existing installs: `docker compose pull && docker compose up -d --force-recreate datris ui mcp-server`. No data migration needed.
- The `datris` CLI: `brew upgrade datris`.

---

See [archived release notes](release-notes/) for prior versions.
