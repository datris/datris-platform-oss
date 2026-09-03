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

/** The last CodeGen transformation script generated for a pipeline, kept in
  * `<env>-codegen-scripts` so column-lineage inference has real evidence
  * beyond the instruction text. One row per pipeline; overwritten each run. */
case class CodeGenScript(
    pipeline: String,
    kind: String,
    instruction: String,
    script: String,
    generatedAt: String = null
)
