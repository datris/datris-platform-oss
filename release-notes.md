# Release Notes

## v1.30.0 — Unreleased

**The doctor now runs itself.**

- **Upgrades check themselves first.** Re-running the installer on an existing install now runs the pre-upgrade self-check before it pulls anything, when the `datris` CLI is on the machine. A warning is shown and the upgrade continues; an error stops the upgrade with the fix on screen, and can be bypassed deliberately. Without the CLI the installer says so and continues as before.
- **Continuous checks.** Set an interval and the server re-runs its self-checks on that cadence. When a check flips to an error, or recovers, the incident webhook receives a message naming the check, what it found, and the fix, so a Vault token about to expire or a disk filling up reaches you before a run fails on it. Off by default; AI probes never run on the timer.
- **The recovery agent can consult the doctor.** When an incident looks like the platform rather than the tap (a missing secret, a model or embedding service not answering, a full disk) the agent runs the quick self-check and hands the incident to a person with the doctor's fix, instead of retrying something that will fail the same way.

**Upgrading**

Run `datris doctor --pre-upgrade` first, then `docker compose pull && docker compose up -d --force-recreate`. No configuration changes required.

**CLI users: upgrade the CLI too.** The installer's new pre-upgrade check needs a CLI of 1.29 or later on the machine running Docker. An older CLI keeps working, but every future installer run will skip the check and remind you until you upgrade: `pip install -U datris-mcp-server` or `brew upgrade datris`.
