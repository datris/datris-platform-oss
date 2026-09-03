# Release Notes

## v1.27.0 — September 3, 2026

**Lineage now reflects what actually ran, not just what is configured — with an interactive graph and an agent tool to traverse it.**

- **Run-level lineage.** Every pipeline run is now recorded as it completes: what it read (the tap run or uploaded file), which destinations it wrote — each with its own success or failure — how many records, and the definition version it ran under. Recording is automatic for every pipeline, independent of provenance stamping, and never fails or delays a run.
- **History in the graph.** A dataset a pipeline used to land into under an earlier configuration no longer vanishes when the configuration changes: it stays in the lineage graph marked historical, with the runs that wrote it.
- **Lineage graph.** Catalog → Lineage opens an interactive view of the whole chain, Source → Tap → Pipeline → Dataset → Catalog, filterable by catalog, tag or name. Click any node for what feeds it, what depends on it, its freshness and its recent runs, with links straight to the tap or pipeline. A pipeline's detail page links into the graph with that pipeline in focus.
- **Recent runs per node.** The lineage neighborhood endpoint can now return the most recent recorded runs for a tap, pipeline, dataset or source, and can be limited to upstream only, downstream only, or a bounded number of hops.
- **New MCP tool: `get_lineage`.** Agents can ask "what is downstream of this tap?" before changing or deleting something, or see what a pipeline actually wrote lately, including a multi-destination run where one destination failed. Visible to any key that can read metadata.
- **Provenance resolution** for recorded runs now includes what the run read and wrote per destination, and resolves the definition version from the record when the row's stamp is unavailable.

**Upgrading**

`docker compose pull && docker compose up -d --force-recreate`. No configuration changes required. Runs are recorded from the first pipeline run after upgrading; earlier runs still resolve through provenance as before, without recorded outputs. CLI users on pip or Homebrew: upgrade to 1.27.0.
