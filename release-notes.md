# Release Notes

## v1.8.5 — June 11, 2026

**Claude Fable 5 — Anthropic's most capable model, now selectable.**

- **New model option.** Claude Fable 5 now appears in the model picker for both the in-product assistant and code generation, alongside the existing Claude and OpenAI choices. Pick it from Configuration when you want Anthropic's most capable model for demanding reasoning and long-horizon work.
- **A note on data retention.** Fable requires standard (30-day) data retention on the Anthropic account and isn't available on zero-data-retention organizations. If you bring your own Anthropic key on a zero-data-retention plan, choose a different model — you'll now see a clear message explaining why, instead of a generic error.
- **Clearer model errors.** When a model declines a request — wrong account settings, a rejected key, or unsupported options — the assistant now surfaces a plain-language explanation naming the model, rather than a raw provider error.

**Upgrading**

- Existing installs: `docker compose pull && docker compose up -d --force-recreate datris ui mcp-server`. No data migration needed.
- The CLI: `brew upgrade datris`.

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
