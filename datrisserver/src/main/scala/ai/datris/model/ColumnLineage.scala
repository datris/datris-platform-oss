package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** One column-level edge. `from` lists the input fields that feed `to`
  * (empty for `system` columns the platform adds). `op` is passthrough |
  * rename | derive | drop | system; `confidence` is exact (schema-derived),
  * inferred (AI-extracted from the transformation), or system. */
case class ColumnEdge(
    from: java.util.List[String],
    to: String,
    op: String,
    confidence: String,
    evidence: String = null
)

/** Cached AI-inferred mappings for one pipeline definition version, in
  * `<env>-column-lineage` keyed `pipeline|version`. Immutable per version:
  * recomputed only when the definition changes. */
case class InferredColumnLineage(
    pipeline: String,
    version: Int,
    edges: java.util.List[ColumnEdge],
    model: String = null,
    computedAt: String = null,
    /** Non-null when inference ran but produced nothing usable (kept so the UI can say so). */
    note: String = null
)

/** The CodeGen script (AI data-quality rule or AI transformation) a pipeline
  * runs, indexed in `<env>-codegen-scripts` keyed `pipeline|kind`. The script
  * text lives in the script store (`storage` + `scriptPath`, or the repo
  * fields); `script` is only set on rows written before scripts were stored
  * (kept so lineage can still read them). Written at pipeline save, at a run
  * that had no usable script, or on a forced regenerate (`origin`).
  *
  * `fingerprint` hashes the instruction and the source schema signature;
  * `generatedAgainst` is the delimited header the script was written for;
  * `status` is `ready` or `pending` (no script yet, `pendingReason` says
  * why); `contractVersion` is the evaluator's script contract at generation.
  *
  * `conflictFingerprint` / `conflictReason` mark an unresolved repository
  * conflict: a new script for that fingerprint (instruction + schema) was not
  * committed because the file was edited in the repository since
  * `scriptCommitSha`. While the pipeline still has that fingerprint, runs
  * execute the recorded commit with no model call. Cleared by pull, by
  * regenerate with overwrite, or by any successful store. */
case class CodeGenScript(
    pipeline: String,
    kind: String,
    instruction: String,
    script: String,
    generatedAt: String = null,
    storage: String = null,
    scriptPath: String = null,
    scriptRepoPath: String = null,
    scriptCommitSha: String = null,
    fingerprint: String = null,
    generatedAgainst: java.util.List[String] = null,
    model: String = null,
    status: String = null,
    pendingReason: String = null,
    origin: String = null,
    contractVersion: Int = 0,
    conflictFingerprint: String = null,
    conflictReason: String = null
)
