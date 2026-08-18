# Release Notes

## v1.17.0 — August 17, 2026

**New AI provider: Grok (xAI).**

Grok joins Anthropic Claude and OpenAI as a third model family for chat and CodeGen — the Assistant, tap script generation, AI data quality rules, transformations, and natural-language → SQL can all run on Grok. One API key from console.x.ai covers both slots; pick **Grok (xAI)** in Configuration → AI Providers and choose a model (Grok 4.6 recommended, with Grok Code Fast 1 available for CodeGen). Mix and match freely — for example Claude for chat with Grok for CodeGen, or the reverse.

xAI has no embeddings API, so semantic-search embeddings stay on OpenAI, Azure, or the bundled local model — a Grok-only setup works out of the box with the bundled embedder.

Fresh installs can choose Grok at install time: the installer now prompts for an xAI key alongside Anthropic and OpenAI, and `.env` seeding supports Grok as a first-boot provider.

**Upgrading**

Standard upgrade: `docker compose pull && docker compose up -d`. Nothing to reconfigure — to try Grok, enter your xAI API key in Configuration → AI Providers and switch a section's provider to Grok (xAI).

---

## v1.16.3 — August 14, 2026

**Assistant fix: taps that read data already in Datris no longer ask for database credentials.**

See the [full v1.16.3 notes](release-notes/v1.16.3.md) for details.
