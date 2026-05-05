# Release Notes

## v1.6.17 — May 4, 2026

**AI-agent RAG ingestion no longer burns through the conversation context.**

- **Creating a vector-store pipeline no longer requires sample content.** Asking an agent to ingest a PDF into pgvector previously forced it to base64-encode the entire document just to register the pipeline — wasting tens of thousands of tokens before any work began. Vector pipelines now register from a name plus destination alone, freeing budget for the actual upload.
- **Agents are guided to send each document in a single upload.** The MCP server now makes explicit that vector destinations chunk server-side, preventing agents from needlessly splitting documents into many small uploads.
- **Clearer Claude setup docs.** The "Configuring Claude" guide shows the recommended SSE / mcp-remote setup first, and steers large-file ingestion to the CLI rather than dragging files directly into the chat — which can overflow the conversation context on sizable PDFs.
- **README accuracy pass.** Corrected tool counts, AI-provider model defaults, and license badge.

**Upgrading**

- Existing installs: `docker compose pull && docker compose up -d`. No data migration needed.

---

See [archived release notes](release-notes/) for prior versions.
