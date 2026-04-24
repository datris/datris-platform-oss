# Release Notes

## v1.6.12 — April 24, 2026

**Tap wizard reliability, iteration history, and cleaner vector-search errors.**

- **Tap wizards learn from their own retries.** When the AI fixes, optimizes, or reviews a tap script, it now carries forward up to the last three attempts — what was tried, what went wrong, and what changed — into the next call. The wizard stops cycling through the same failed approaches.
- **Saved tap scripts always match the tap.** Saving a tap now pushes the in-memory script to object storage before writing the tap config, and the create/update call verifies the stored script is actually there. No more "missing script" banners from an interrupted save, and auto-revert no longer strands a tap with a deleted script.
- **Run Tap stays on the page when nothing was ingested.** If a manual run finishes without persisting records, the wizard keeps you on the run step and shows an inline reason (test mode, no records, run error) instead of navigating away and hiding the diagnostic.
- **Tap logs now carry the publisher token.** Every tap run that submitted records records its publisher token in the log. Agents reading `get_tap_logs` can pivot directly to `get_pipeline_status` to confirm a scheduled run actually landed in the destination — not just that the script ran.
- **Vector search fails cleanly when the embedding dimension doesn't match the collection.** If you change embedding providers on a pipeline whose vector collection already has vectors of a different dimension, search queries now return a clear 400 with a user-actionable message instead of leaking a JVM stack trace.
- **Sturdier local-dev startup.** Kafka and Zookeeper now use named volumes (no more corruption races on rebuild), Kafka waits for Zookeeper's request processor to actually be ready (not just its listener bound), and Vault init picks up an explicit AI-provider override so a stray shell env var can't silently flip providers.

---

See [archived release notes](release-notes/) for prior versions.
