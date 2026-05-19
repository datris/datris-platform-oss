# Release Notes

## v1.7.3 — May 19, 2026

**Scoped API keys with per-agent permissions, plus Assistant resilience and smarter onboarding.**

- **New API-Keys tab in Configuration.** Issue a dedicated key per agent, CLI, or integration with an explicit list of what it's allowed to do — read pipelines, run taps, upload documents, query data, and so on. Each key is its own identity in the request log and can be rotated or revoked independently. Five starting templates: read-only, rag-builder, reporting, ops, and full-access.
- **Keys actually constrain.** When an external agent (Claude Desktop, Cursor, the CLI) connects with a scoped key and tries something outside its bundle, the platform refuses the call and tells the agent why in plain JSON — so the agent doesn't keep retrying alternate paths. Agents stay productive within their lane; you don't have to trust them not to wander.
- **UI no longer asks for a key when user authentication is on.** With login enabled, your session cookie is the only thing the browser needs — paste-the-key flow goes away. The Assistant runs under your identity, audit logs show you as the actor, and your role determines what it can do (admin = full access, editor = data writes, viewer = read-only).
- **Assistant rides through Anthropic overload.** When Claude Opus is rate-limit-shedded, the Assistant retries with backoff and, if needed, transparently switches to Sonnet for the rest of the turn — with a small inline note so you know it happened. Conversations that previously errored out now keep moving.
- **Assistant checks the platform before suggesting external sources.** On any data-related ask ("I'm looking for X"), the agent now lists your existing pipelines and taps first, then either points you to what already exists or asks before adding more. Avoids the "let me enumerate seven public APIs" detour.
- **Assistant auto-runs newly created taps that have no schedule.** When you build a one-shot tap, the Assistant kicks off the first run so you see real data instead of an empty pipeline. For scheduled taps it asks first, since the cron will fire on its own.
- **Health and version endpoints are public.** Container orchestrators, status pages, and the UI's connection check no longer trip 500s when API-key auth is required.
- **Configuration → Taps sub-tab removed.** Prompt Fragments are unchanged and still apply to tap creation, brainstorm, auto-fix, and Discovery — they're now managed via the API instead of a dedicated UI page.
- **Tap wizard pipeline link is clickable.** The "Linked to: \<pipeline\>" pill in step 4 now navigates straight to the pipeline editor.

**Upgrading**

- Existing installs: `docker compose pull && docker compose up -d --force-recreate datris ui`. No data migration needed.
- Existing API keys keep working — they're treated as full-access until you replace them with scoped keys from the new tab.
- The `datris` CLI: `brew upgrade datris`.

---

See [archived release notes](release-notes/) for prior versions.
