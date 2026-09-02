# Release Notes

## v1.26.0 — September 2, 2026

**Provenance on your data: any row can now say which run, which configuration, which script, and which source produced it — and agents can find datasets by meaning.**

- Turn on **provenance stamping** for a pipeline and every row, document, message, and vector chunk it lands carries the run that produced it, when it landed, the pipeline definition version, the tap run that fed it, the exact script commit, and the declared source. Existing rows are never rewritten — provenance starts with the first run after you turn it on, and older rows simply read empty. On SQL destinations the new columns appear automatically on the first stamped run.
- Every stamp can be traced back to its origin: ask the platform about a run id and get the whole story — the run, the tap run that fed it, the script commit, the definition version with its change note, and the source. Agents get the same through a new **get_provenance** tool, and vector search results now carry provenance per chunk, so a RAG answer can cite the run and source behind each passage.
- A new **lineage view** shows the chain source → tap → pipeline → landed dataset → catalog, derived from your configuration alone — no AI, no guesswork. A pipeline's detail page now shows what feeds it, where it lands, and how fresh it is (last landing, record count, incremental bookmark), using the same staleness judgment as the Activity dashboard so the two never disagree.
- **Tags** on taps and pipelines: free-form labels you (or your agents) add in the wizards, shown in the catalog tables, and ranked by discovery.
- **find_data**: agents can now discover datasets by meaning. Describe the data you need and get back the best-matching pipelines your key can read — where each one lives, how fresh it is, its provenance handles, and a ready-to-run query hint naming the right tool with the arguments filled in. Discovery only, by design: the query itself stays the agent's own call, under its own permissions. AI answers can carry the provenance handles along, so an answer and its sources travel together.
- Deleting a tap or pipeline from the catalog now shows progress while the platform finishes cleaning up, instead of sitting silent.

**Upgrading**

`docker compose pull && docker compose up -d --force-recreate`. No configuration changes required. Provenance stamping is off by default — turn it on per pipeline in the pipeline editor's Destination step.

---

Older releases: see the [release-notes/](release-notes/) directory or the [changelog on the docs site](https://docs.datris.ai/changelog).
