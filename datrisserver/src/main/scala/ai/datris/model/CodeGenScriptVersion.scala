package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** One immutable version of the CodeGen script (AI data-quality rule or AI
  * transformation) a pipeline runs. Lives in the append-only
  * `<env>-codegen-script-version` collection; the live record
  * ([[CodeGenScript]], `scriptVersion`) is always the latest version N.
  *
  * Stored flat so the collection can be queried by `entityName` and pruned or
  * deleted by `key`, the same idiom as [[EntityVersion]]. The script text is
  * held inline, so a pruned version needs no object cleanup.
  *
  * @param key        unique doc key, `pipeline|kind|version`
  * @param entityName `pipeline|kind`
  * @param version    monotonic per `pipeline|kind`, starts at 1
  * @param script     the script text itself
  * @param scriptCommitSha the commit for a repository-backed script, else null
  * @param origin     save | regenerate | run | restore-version | restore | repository
  * @param changeNote optional note ("(seeded from pre-versioning state)", ...)
  * @param createdBy  the key label (or user) that caused the change, null for a run
  */
case class CodeGenScriptVersion(
    key: String,
    entityName: String,
    pipeline: String,
    kind: String,
    version: Int,
    script: String,
    storage: String = null,
    scriptRepoPath: String = null,
    scriptCommitSha: String = null,
    fingerprint: String = null,
    model: String = null,
    origin: String = null,
    changeNote: String = null,
    createdAt: String = null,
    createdBy: String = null,
    generatedAt: String = null,
    contractVersion: Int = 0,
    generatedAgainst: java.util.List[String] = null
)
