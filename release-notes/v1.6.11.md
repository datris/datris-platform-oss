# Release Notes

## v1.6.11 — April 22, 2026

**Agent-native tap observability, scheduler fix, and agent-owned tap secrets.**

- **Agents can watch a tap load to completion.** Running a tap now reports back whether the data was actually persisted — and names the reason when it wasn't (test mode, no target pipeline, no records, run error). Every persisted run returns a single *publisher token* covering the whole run, even for document taps that spawn many ingestion jobs. A new `get_pipeline_status` MCP tool lets an agent poll that one token until the entire load reaches its final state, so it can report "done" with real numbers instead of guessing from a response body.
- **Scheduled taps no longer need a manual kickoff.** Taps saved with a cron schedule now fire on their next scheduled time automatically. Previously, a newly saved scheduled tap would wait indefinitely until you ran it once by hand.
- **Self-diagnosing tap scripts.** If a tap's generated script goes missing from object storage, the Edit Tap page now shows an amber banner explaining the state and pointing you to Regenerate, instead of a cryptic mid-run "key does not exist" error. Test Tap surfaces the same state with actionable wording.
- **Agents can manage their own tap secrets.** Via MCP, agents can now create and delete the secrets their taps need (API keys, tokens). Scope is strictly tap-owned — agents cannot create, overwrite, or delete human-owned Platform secrets (DB creds, AI keys, vector-store creds).
- **Secrets page split into Platform and Taps sub-tabs.** Platform lists the built-in Datris secret slots; Taps lists agent-authored tap secrets. Creating a secret from the Taps sub-tab auto-tags it so it stays agent-editable, and Tap secrets are fully manageable on trial tenants.
- **Honest Test Tap banner.** The run-result banner on Test Tap now reflects what actually happened on the server — "sent to pipeline" only when the run was truly persisted, otherwise "not persisted" with the reason — rather than whatever the pre-request checkbox said.
- **BYO-code taps can declare pip dependencies.** If you paste your own fetch script into Create Tap, you can now list the Python packages it needs. Previously only AI-generated taps could declare dependencies.
- **Example agent refactored onto taps.** The bundled market-macro-agent example now drives ingestion through taps instead of ad-hoc fetch scripts, demonstrating the full agent-native tap flow (provisioning, secrets, publisher-token watching).

---

See [archived release notes](release-notes/) for prior versions.
