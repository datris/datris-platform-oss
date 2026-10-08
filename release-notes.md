# Release Notes

## v1.45.0 — October 8, 2026

**AI rule and transformation scripts are generated once, stored with your pipeline, and run in an isolated container.**

- **One script per instruction, written when you save.** Saving a pipeline with an AI data-quality rule or an AI transformation generates its Python script right then and stores it. Every run executes that stored script, so an unchanged instruction gives the same behaviour on every run, runs make no model call, and a model outage no longer stops ingestion. A later save regenerates only when the instruction or the source schema changed. Pipelines created before the upgrade generate and store on their first run. See [AI rules](/data-quality/ai-rules#when-the-script-is-generated) and [AI transformation](/transformation/ai-transformation#when-the-script-is-generated).
- **Read, review and replace the script.** The save response and a new API endpoint show each pipeline's scripts, when they were generated and by which model. The Lineage panel shows the same for the transformation. A script you do not like can be regenerated on demand; a script that fails is never silently replaced, and the run error says when it was generated and how to replace it.
- **Scripts in your code repository.** With a code repository configured, pipeline scripts are committed next to your tap scripts, under `pipelines/<pipeline>/`, and runs execute the exact commit that was recorded. Edit the file in the repository and adopt the edit deliberately with one call; a save that would overwrite a hand edit is refused with a warning, and runs keep the recorded commit until you resolve it. Installs without a repository see no change. See [Pipeline scripts](/tap-github-storage#pipeline-scripts).
- **Generated scripts run outside the server.** On a compose install, AI rule and transformation scripts now execute in a new hardened container, `datris-codegen-runner`, with no platform secrets, no view of the server's filesystem, no route to the database, object store or secrets store, no outbound internet, and no access to the Datris API. Scratch space is a dedicated volume emptied after every run. Pipelines behave the same. `datris doctor` reports which mode is in effect. See [Execution isolation](/tap-execution-isolation#generated-data-quality-and-transformation-scripts).
- **Blank AI instructions are ignored.** An empty or whitespace-only instruction no longer calls the model; the stage is skipped with a status line saying so.
- **Doctor covers the new container.** The environment-drift check includes `datris-codegen-runner`, and a new `codegen.isolation` row reports whether scripts are isolated.

**Upgrading**

Refresh `docker-compose.yml` (`git pull`, or re-download `docker-compose.standalone.yml`), then run `docker compose pull && docker compose up -d`. All four images changed. One new container, one new internal network (`codegen-net`) and one new named volume (`codegen-scratch`) appear. No `.env` change is needed.

- **Refresh the compose file and pull images together.** An install that pulls images but keeps its old compose file keeps running scripts inside the server, with a startup warning and a `codegen.isolation` doctor warning until the file is refreshed. An install that refreshes the compose file but keeps old images fails loudly on every AI rule and transformation until it pulls; scripts never fall back to running inside the server.
- **Scripts are written from column names and types, not sample rows.** For delimited pipelines saved after the upgrade, an instruction that depends on how values look must state the format.
- **A wrong or failing script stays until you act.** Change the instruction, or regenerate it. Changing the CodeGen model no longer changes a pipeline's script; regenerate to adopt the new model.
- **Saving a pipeline with AI instructions can wait for up to two model calls.**
- **Scripts that reached the network or server files now fail.** That is the point of the isolation. Set `USE_CODEGEN_RUNNER=false` in `.env` to run them inside the server as before.
- **Keep `codegen-net` internal.** The compose file marks it `internal: true`; removing that would make the server reject traffic from your own host. If you must, also set `CODEGEN_RUNNER_API_BLOCK=false`.
- No other configuration changes are required.
