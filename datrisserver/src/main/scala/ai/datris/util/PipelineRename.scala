package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{CodeGenScriptVersion, EntityVersion, INITIALIZED, JobState, PROCESSING, PipelineConfig, TapConfig}
import com.google.gson.JsonParser

/** Pure helpers behind `POST /api/v1/pipelines/{name}/rename` (no I/O).
  *
  * A rename changes the pipeline's name and nothing else: the destination,
  * catalog, owner label and authority fields are kept, definition versions and
  * CodeGen script versions are re-keyed under the new name, and taps that feed
  * the pipeline are re-pointed. The handler runs these on the loaded data. */
object PipelineRename {

    /** Same limit as `PipelineValidatorUtil` for a pipeline name. */
    val MaxNameLength = 80

    /** Why `newName` cannot be the new name of `old`, or None. Judged on the
      * trimmed new name: blank, unchanged, or longer than [[MaxNameLength]]. */
    def nameProblem(old: String, newName: String): Option[String] = {
        val n = if (newName == null) "" else newName.trim
        if (n.isEmpty) Some("Request body must include a non-empty \"newName\"")
        else if (n == old) Some("New name is the same as the current name")
        else if (n.length > MaxNameLength) Some("pipeline 'name' cannot be greater than " + MaxNameLength + " characters")
        else None
    }

    /** The config under its new name; every other field is kept. */
    def renamedConfig(c: PipelineConfig, newName: String): PipelineConfig = c.copy(name = newName)

    /** A definition version under the new name: `key` and `entityName`, and
      * the `name` inside the snapshot (a restored snapshot is saved under its
      * own name, so an unrewritten one would bring the old pipeline back).
      * Version, note, time and actor are kept. */
    def rekeyVersion(v: EntityVersion, newName: String): EntityVersion =
        v.copy(
            key = EntityVersionIO.docKey(newName, v.version),
            entityName = newName,
            config = renameInSnapshot(v.config, newName)
        )

    /** A CodeGen script version under the new name. The script text is never
      * rewritten. */
    def rekeyScriptVersion(v: CodeGenScriptVersion, newName: String): CodeGenScriptVersion =
        v.copy(
            key = CodeGenScriptVersionIO.docKey(newName, v.kind, v.version),
            entityName = CodeGenScriptVersionIO.entityName(newName, v.kind),
            pipeline = newName
        )

    /** The taps whose `targetPipeline` is exactly `old`, visible to the caller or not. */
    def tapsToRepoint(taps: Seq[TapConfig], old: String): Seq[TapConfig] =
        taps.filter(t => t != null && t.targetPipeline != null && t.targetPipeline == old)

    def repointed(tap: TapConfig, newName: String): TapConfig = tap.copy(targetPipeline = newName)

    /** True when `existingNew` is `old` under another name (and any version):
      * a rename that stopped half way, which a repeated rename continues. */
    def resumable(old: PipelineConfig, existingNew: PipelineConfig): Boolean =
        old != null && existingNew != null &&
            existingNew.copy(name = old.name, version = old.version) == old

    /** Refusal text when the pipeline syncs to Unity Catalog, whose table is
      * named after the pipeline. */
    def unityCatalogBlock(enabled: Boolean, hasState: Boolean): Option[String] =
        if (enabled || hasState)
            Some(
                "This pipeline syncs to Unity Catalog, whose table is named after the pipeline, so it cannot be renamed. " +
                    "Create the pipeline under the new name instead."
            )
        else None

    /** True while a job of pipeline `old` is initialized or processing. */
    def jobBlock(jobs: Seq[(String, JobState)], old: String): Boolean =
        jobs.exists { case (name, state) => name == old && (state == INITIALIZED || state == PROCESSING) }

    /** The definition version note for the renamed config. */
    def changeNote(old: String, newName: String, actor: String): String =
        "renamed " + old + " -> " + newName + Option(actor).filter(_.nonEmpty).map(" by " + _).getOrElse("")

    private def renameInSnapshot(config: String, newName: String): String =
        if (config == null) null
        else
            try {
                val el = JsonParser.parseString(config)
                if (!el.isJsonObject) config
                else {
                    val o = el.getAsJsonObject
                    o.addProperty("name", newName)
                    o.toString
                }
            } catch { case _: Exception => config }
}
