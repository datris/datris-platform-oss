# Release Notes

## v1.8.4 — June 7, 2026

**A domain-neutral assistant — guidance that fits whatever data you work with.**

- **No more finance-flavored examples.** The in-product assistant, the tap generator, and the prompt suggestions used to lean on stock-market wording — tickers, symbols, financial filings — in their examples and defaults. They're now domain-neutral, so the guidance and sample values match the data you're actually working with, whatever the domain.
- **Generic search defaults.** Vector search now defaults to a `documents` collection (and a `Documents` class) instead of finance-specific names. If you already pass your own collection, table, or class name, nothing changes — only the placeholder defaults differ.
- **Refreshed starter prompt fragments.** The built-in tap prompt fragments now describe general patterns — rate-limited APIs and APIs that require a User-Agent header — rather than specific named data providers.

**Upgrading**

- Existing installs: `docker compose pull && docker compose up -d --force-recreate datris ui mcp-server`. No data migration needed.
- The CLI: `brew upgrade datris`.

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
