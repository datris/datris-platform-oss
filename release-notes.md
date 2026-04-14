# Release Notes

## v1.6.1 — April 14, 2026

Discovery wizard, Data Catalog, and trial BYO AI keys.

### Discovery: AI-powered data onboarding wizard

A new six-step wizard that turns a plain-English data request into running taps and pipelines. Pick or create a Data Catalog, chat with the AI to identify a source ("yfinance daily prices for the S&P 500"), select the datasets you want, and Discovery generates every tap script, builds the matching pipelines, and (optionally) schedules and runs them — all in one session.

- New REST endpoints: `POST /api/v1/discover`, `POST /api/v1/discover/build`
- New MCP tool: `discover_source` — gives external AI agents the same enumeration capability
- Built on the existing tap codegen + AI-fix loop, so quality of generated scripts scales with your CodeGen model

### Data Catalog: organize related taps and pipelines

Group related taps and pipelines into named catalogs for browsability. The catalog is a metadata field on each tap/pipeline — it doesn't change runtime behavior, just how the platform presents your work. Discovery sessions auto-assign their catalog; you can also assign manually in the Tap or Pipeline editors. A new **Data Catalog** tab in the UI shows every catalog with expandable contents, plus an Uncataloged group for everything else.

### Per-pipeline DQ + transformation editor

The pipeline editor now includes an inline data-quality and transformation editor accessible from the pipeline list. Add or revise AI rules and AI transformations for an existing pipeline without re-running the wizard.

### Trial: bring your own AI keys

Trial signups at `datris.ai/signup` now collect an Anthropic or OpenAI API key (or both) and seed them per-tenant into Vault. Embeddings always use the trial droplet's bundled Ollama `bge-m3` — no OpenAI key required for vector workflows.

- The trial Configuration tab is now unlocked: trial users can rotate keys, swap providers, and change models per service the same way dedicated instances do.
- Default models are env-configurable on the website: `TRIAL_AI_MODEL_ANTHROPIC`, `TRIAL_AI_MODEL_OPENAI`, `TRIAL_CODEGEN_ANTHROPIC`, `TRIAL_CODEGEN_OPENAI`.

### Documentation

New pages: [Discovery](https://docs.datris.ai/discovery), [Data Catalog](https://docs.datris.ai/data-catalog). Cross-references added from the Quick Start, Taps, and MCP Server pages.

### Upgrading from v1.6.0

No configuration changes required for the server. Pull the new images and restart:

```sh
docker compose pull datris ui mcp-server
docker compose up -d datris ui mcp-server
```

Trial deployments: add the new `TRIAL_AI_MODEL_*` and `TRIAL_CODEGEN_*` env vars to your website env if you want to override the defaults; remove the old `TRIAL_AI_API_KEY`, `TRIAL_AI_MODEL`, `TRIAL_AI_PROVIDER`, `TRIAL_EMBEDDING_API_KEY`, `TRIAL_EMBEDDING_MODEL`, `TRIAL_EMBEDDING_PROVIDER`, and `TRIAL_CODEGEN_MODEL` env vars — none are read anymore.

### Version

- Server: 1.6.1
- MCP Server: 1.6.1
- CLI: 1.6.1

---

## v1.6.0 — April 11, 2026

Dedicated instance support and hosted platform improvements.

### Configuration UI: hosted-aware

The Configuration tab adapts when running on a hosted instance:

- **AI Primary & CodeGen:** hides the Ollama option (not applicable on hosted — bundled Ollama handles embeddings only)
- **Embedding on hosted + Anthropic:** locked to "Ollama bge-m3 (bundled)" — no configuration needed
- **Embedding on hosted + OpenAI:** dropdown with OpenAI and bundled Ollama options
- **Advanced toggle:** hidden on hosted instances

### UI: improved multi-user session handling

The platform UI now handles user switching more reliably on shared instances, ensuring each user sees their own environment without manual intervention.

### Upgrading from v1.5.9

No configuration changes required. Pull the new images and restart:

```sh
docker compose pull datris ui mcp-server
docker compose up -d datris ui mcp-server
```

Self-hosted users: the `hosted` flag defaults to `false` — no action needed.

### Version

- Server: 1.6.0
- MCP Server: 1.6.0
- CLI: 1.6.0

---

## v1.5.9 — April 11, 2026

See [v1.5.9 release notes](release-notes/v1.5.9.md).

## v1.5.8 — April 10, 2026

See [v1.5.8 release notes](release-notes/v1.5.8.md).

## v1.5.7 — April 9, 2026

See [v1.5.7 release notes](release-notes/v1.5.7.md).

## v1.5.6 — April 8, 2026

See [v1.5.6 release notes](release-notes/v1.5.6.md).

## v1.5.5 — April 8, 2026

See [v1.5.5 release notes](release-notes/v1.5.5.md).

## v1.5.4 — April 7, 2026

See [v1.5.4 release notes](release-notes/v1.5.4.md).

## v1.5.3 — April 3, 2026

See [v1.5.3 release notes](release-notes/v1.5.3.md).

## v1.5.2 — April 3, 2026

See [v1.5.2 release notes](release-notes/v1.5.2.md).

## v1.5.1 — April 3, 2026

See [v1.5.1 release notes](release-notes/v1.5.1.md).

## v1.5.0 — April 2, 2026

See [v1.5.0 release notes](release-notes/v1.5.0.md).

## v1.4.4 — March 30, 2026

See [v1.4.4 release notes](release-notes/v1.4.4.md).

## v1.4.3 — March 29, 2026

See [v1.4.3 release notes](release-notes/v1.4.3.md).

## v1.4.2 — March 27, 2026

See [v1.4.2 release notes](release-notes/v1.4.2.md).
