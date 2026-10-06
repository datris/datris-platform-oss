# Release Notes

## v1.43.0 — October 5, 2026

**A HIPAA Safe Harbor preset, a switch that keeps row values away from AI helpers, clearer errors, and an installer agents can run.**

- **HIPAA Safe Harbor preset for field protection.** Pick the preset and Datris recognises the Safe Harbor identifier classes from your field names, proposes a protection method for each, and lists what it could not decide so a person can look. Recognition uses a fixed, documented table with no AI call, so the answer is the same every time. With the preset enforced, a pipeline cannot be saved while a recognised identifier is unprotected and not explicitly exempted, and a new column that appears later is protected before it reaches any destination. The preset is in the pipeline wizard, on the pipeline page and available to agents. It is an aid to applying Safe Harbor, not a certification of compliance. See [Field Protection](/transformation/field-protection).
- **One switch so helpers send no row values to a model.** Set `DATRIS_AI_SAMPLE_VALUES=false` and schema generation, data profiling, sample-based schema helpers, Assistant file attachments and AI rule and transformation generation work from structure only: names, types, counts and lengths. Results say when values were withheld. The default is unchanged. `datris doctor` reports the setting and warns when a pipeline protects fields while values are still sampled.
- **A new mask option: keep the first three characters.** Useful for postal codes.
- **Schema generation and profiling no longer fail when the model declines.** You get a usable all-text schema, or statistics without the AI summary, with a note saying what happened. Real provider errors are still reported as errors.
- **An invalid pipeline is reported as an invalid request.** Saving a pipeline the validator refuses now returns a 400 with the same message as before, and the wizard and agents show that message as plain text.
- **Failed runs show the error, not a Java stack trace.** A failed run's status reads as the message and its causes. The stack trace is still there for troubleshooting, behind a toggle in the Ops activity view and in the server log.
- **Fixed: a column the destination already declares is no longer added twice.** A pipeline whose destination schema already listed a column failed its load when that column later arrived as a new column in the source. It now loads, and a pipeline left in that state by an earlier failed run repairs itself on the next run.
- **Fixed: editing a pipeline in the wizard keeps its protection settings.** A pipeline set to keep its source file after a protected run could lose that setting when edited.
- **Install Datris from an agent.** A new page describes a headless install an AI agent can run end to end, and the agent skill now covers what to do when Datris is not installed. See [Install for Agents](/install-for-agents).
- **Installer improvements.** A fresh install with no AI provider stops with a clear message before downloading anything, a failed first run cleans up after itself so a retry works, and `AI_PROVIDER` selects any supported provider.

**Upgrading**

Run `docker compose pull && docker compose up -d --force-recreate`. The server, UI and MCP server images changed.

- **Pipeline save errors.** A refused pipeline save now returns HTTP 400 where it returned 500. The message is unchanged. Scripts that treated only 500 as "invalid pipeline" should accept 400.
- **Run status.** The stack trace of a failed run moved out of the status description into a separate detail field. Anything that read the trace from the description should read the new field. Runs recorded before the upgrade are unchanged.
- **`DATRIS_AI_SAMPLE_VALUES`.** To use the switch on an existing install, add the variable to your `.env`; if you maintain your own compose file, pass it to the server as the current `docker-compose.yml` does.
- No other configuration changes are required. Existing pipelines behave as before unless you choose the preset or set the switch.
