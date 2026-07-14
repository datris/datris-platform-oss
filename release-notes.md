# Release Notes

## v1.11.0 — July 13, 2026

**Choose your databases at install — run less, connect what you already have.**

- **Pick and choose at install.** The installer now asks which databases and stores you want instead of installing everything. Each can run bundled, connect to a service you already operate, or be skipped — and pressing Enter at every prompt still gives the standard full install.
- **Bring your own infrastructure.** Point Datris at an existing Postgres, Kafka, or managed vector store (Qdrant, Weaviate, Chroma, Milvus) during install, and optionally store Snowflake or Databricks destination credentials up front so pipelines can use them on day one.
- **Lighter installs.** Skipping the bundled semantic-search server avoids a multi-gigabyte model download — the installer recommends OpenAI embeddings, which need no local container and cost pennies. Vector stores and a local Kafka test broker are now a one-line opt-in instead of commented-out YAML.
- **Both AI providers, each at its best.** The installer asks for an Anthropic key (chat, code generation, AI data quality) and an OpenAI key (embeddings) — both optional, with sensible behavior when only one is present.
- **Everything knows what's installed.** The pipeline wizard greys out destinations that aren't available, the AI assistant offers only the destinations your deployment actually has, and creating a pipeline against a missing database returns a clear explanation instead of a timeout.
- **Install-time validation.** After first boot the installer checks every store you configured — bundled or external — and reports each by name, so a mistyped hostname or bad credential surfaces immediately, not mid-pipeline next week.
- **Change your mind anytime.** Enable or disable any store after install with a one-line edit and a restart. Disabling keeps the data; re-enabling brings it back.
- **Safer upgrades.** Upgrading an existing install never changes which services run — everything you had keeps running, no action required. Data services now use durable named storage, so containers can be removed and recreated without losing data.

**Upgrading**

- Existing installs: re-run the installer, or `docker compose pull && docker compose up -d --remove-orphans` after refreshing the compose file. Your current services and data are preserved automatically.

---

## v1.10.0 — July 9, 2026

**Databricks destination — load, upsert, and query your Databricks workspace.**

See [archived v1.10.0 release notes](release-notes/v1.10.0.md).

---

## v1.9.0 — July 8, 2026

**Snowflake destination — load, upsert, and query your Snowflake account.**

See [archived v1.9.0 release notes](release-notes/v1.9.0.md).

---

## v1.8.12 — July 1, 2026

**Claude Sonnet 5 support.**

See [archived v1.8.12 release notes](release-notes/v1.8.12.md).

---

## v1.8.11 — June 29, 2026

**Switch AI providers freely, and an assistant that finishes the job.**

See [archived v1.8.11 release notes](release-notes/v1.8.11.md).

---

## v1.8.10 — June 24, 2026

**Your secrets and configuration now persist across restarts and rebuilds.**

See [archived v1.8.10 release notes](release-notes/v1.8.10.md).

---

## v1.8.9 — June 22, 2026

**Version history for taps and pipelines, plus a faster assistant.**

See [archived v1.8.9 release notes](release-notes/v1.8.9.md).

---

## v1.8.8 — June 18, 2026

**Stronger isolation for tap scripts, plus a catalog readability fix.**

See [archived v1.8.8 release notes](release-notes/v1.8.8.md).

---

## v1.8.7 — June 14, 2026

**Drop a file into the Assistant and it builds the pipeline for you.**

See [archived v1.8.7 release notes](release-notes/v1.8.7.md).

---

## v1.8.6 — June 12, 2026

**A more decisive assistant, and taps that tell you when a credential is missing.**

See [archived v1.8.6 release notes](release-notes/v1.8.6.md).

---

## v1.8.5 — June 11, 2026

**Claude Fable 5 — Anthropic's most capable model, now selectable.**

See [archived v1.8.5 release notes](release-notes/v1.8.5.md).

---

## v1.8.4 — June 7, 2026

**A domain-neutral assistant — guidance that fits whatever data you work with.**

See [archived v1.8.4 release notes](release-notes/v1.8.4.md).

---

## v1.8.3 — June 4, 2026

**Organize your catalog by chatting with it.**

See [archived v1.8.3 release notes](release-notes/v1.8.3.md).

---

## v1.8.2 — June 3, 2026

**Ask your data a question — conversational search comes to the Search tab.**

See [archived v1.8.2 release notes](release-notes/v1.8.2.md).

---

## v1.8.1 — June 2, 2026

**Orchestrate Datris taps from Apache Airflow.**

See [archived v1.8.1 release notes](release-notes/v1.8.1.md).

---

See [archived release notes](release-notes/) for prior versions.
