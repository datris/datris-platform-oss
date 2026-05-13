# Release Notes

## v1.7.1 — May 13, 2026

**Polish for the Assistant and AI Configuration tabs.**

- **Assistant: keep typing without clicking.** After you send a question, the cursor stays in the composer — so as soon as the agent finishes (or even while it's still working), you can type your next prompt without reaching for the mouse.
- **AI Configuration: provider switches no longer wipe your overrides.** Switching the primary AI, codegen, or embedding provider used to clear the saved provider/model/endpoint on the next page load, so you had to re-pick them every time. Your selections now persist correctly through a provider switch (you still re-enter the API key when changing providers — that's intentional).

**Upgrading**

- Existing installs: `docker compose pull && docker compose up -d`. No data migration needed.
- The `datris` CLI: `brew upgrade datris`.

---

See [archived release notes](release-notes/) for prior versions.
