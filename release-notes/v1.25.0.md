# Release Notes

## v1.25.0 — September 1, 2026

**The recovery agent: Datris now notices broken data flows, diagnoses them, and — within limits you set — repairs and verifies them.**

- With the new opt-in recovery agent, the platform opens an **incident** when a scheduled tap keeps failing after its automatic retries, a pipeline load ends in error, a scheduled tap goes quiet past its cadence, or a pipeline's volume swings hard against its own baseline. Each incident is diagnosed with the same judgment as the Ops chat and closed with a step-by-step story of what was found and what was done.
- What the agent may do is yours to decide, per the [Agent Policy](https://docs.datris.ai/agent-policy)'s new **recovery mode**: `off` (today's behavior — a suggestion in Run History, nothing more), `propose` (every repair waits for your one-click approval under Activity → Approvals), or `autopilot` (repairs follow your per-action policy). A per-tap or per-pipeline override can put one critical flow on a stricter mode than the rest.
- Repairs are narrow and verified. The agent can only re-run a tap, fix its script (as a new version, with history kept), and test — never delete, never touch secrets, never migrate schemas. Every fix must prove itself with a real run that lands cleanly; if it can't, the platform **puts the script back the way it was** and tells you. Runtime, action, and AI-call budgets are enforced by the platform, not by a prompt.
- Everything is visible: a new **Incidents** panel on the Activity page shows each incident's state, classification, and timeline with links to any approvals it waits on; the Ops chat knows about open incidents and explains them instead of re-diagnosing; agents can read them through two new tools; and the audit log carries the complete ledger of every action, joined to its incident. An optional webhook reports incident milestones to your own systems.
- Volume-anomaly incidents are diagnosis-only by design: the agent explains what changed and why it thinks so — acting on a volume swing stays a human call.
- Also in this release: the MCP page's tool catalog now lists every available tool, including the destination-typing pair that had been missing from the page since v1.22.

**Upgrading**

`docker compose pull && docker compose up -d --force-recreate`. No configuration changes required. To turn the recovery agent on: `USE_AGENT_POLICY=true` and `RECOVERY_AGENT_ENABLED=true` in your `.env`, recreate the `datris` container, then pick a recovery mode under Configuration → Agent Policy — nothing changes until you do.

---

Older releases: see the [release-notes/](release-notes/) directory or the [changelog on the docs site](https://docs.datris.ai/changelog).
