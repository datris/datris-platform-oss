# Release Notes

## v1.24.0 — August 31, 2026

**Agent Policy: decide what agents may do on their own, what waits for your approval, and what is refused.**

- A new opt-in Agent Policy lets an administrator set, per action — run a tap, delete a pipeline, migrate destination column types, write a secret, and every other change an agent can make — whether agents do it unattended, queue it for a person to approve, or are refused. People using the UI are never gated; they are the approvers. Nothing changes until you set a rule, and the policy applies to every agent: the built-in Assistant and any connected MCP client alike.
- Queued actions appear under Activity → Approvals with who asked, the reason they gave, exactly what would run, and one-click Approve / Reject. Approving performs the original request on your behalf and records the whole chain — request, decision, and result — in the audit log. Approvals go stale if the pipeline or tap changed in the meantime, expire on their own if nobody decides, and can never be approved by an agent, whatever permissions its key holds.
- Agents are told the truth: a gated action comes back "waiting for approval" (never silently done), a refused one says so plainly, and the chat shows matching status cards. Agents can read the policy up front, list what they have queued, and poll for your decision; every change-making agent tool now also accepts a short reason that shows up on the approval card and in the audit log.
- Configuration → Agent Policy manages it all, with a recommended starting point that pauses deletes, job kills, and destination-type migrations and refuses secret and connection changes. Per-pipeline and per-tap overrides can tighten the rules further for sensitive data.
- The Configuration → Environment tab has been removed; its information lives in the server log and the version endpoint.

**Upgrading**

`docker compose pull && docker compose up -d --force-recreate`. No configuration changes required. To turn on the agent policy, add `USE_AGENT_POLICY=true` to your `.env`, recreate the `datris` container, and set your rules under Configuration → Agent Policy.

---

## v1.23.0 — August 28, 2026

**Audit log: who did what, by login or API key.**

See the [full v1.23.0 notes](release-notes/v1.23.0.md) for details.

---

## v1.22.0 — August 27, 2026

**Set real column types on pipelines that landed as text.**

See the [full v1.22.0 notes](release-notes/v1.22.0.md) for details.

---

## v1.21.0 — August 26, 2026

**Live progress feedback across the Assistant and tap building.**

See the [full v1.21.0 notes](release-notes/v1.21.0.md) for details.

---

## v1.20.2 — August 25, 2026

**Security refresh across all container images.**

See the [full v1.20.2 notes](release-notes/v1.20.2.md) for details.

---

## v1.20.1 — August 25, 2026

**MCP tool catalog now matches API-key permissions.**

See the [full v1.20.1 notes](release-notes/v1.20.1.md) for details.

---

## v1.20.0 — August 24, 2026

**Tap isolation on by default, opt-in Postgres TLS, Prometheus metrics + JSON logs, digest-pinned images + SBOMs.**

See the [full v1.20.0 notes](release-notes/v1.20.0.md) for details.

---

## v1.19.4 — August 21, 2026

**Dependency security cleanup across the server and UI.**

See the [full v1.19.4 notes](release-notes/v1.19.4.md) for details.

---

## v1.19.3 — August 21, 2026

**Server security updates and a friendlier vector-store default in the installer.**

See the [full v1.19.3 notes](release-notes/v1.19.3.md) for details.

---

## v1.19.2 — August 20, 2026

**Security updates across the UI and server, and a pipeline-delete fix.**

See the [full v1.19.2 notes](release-notes/v1.19.2.md) for details.
