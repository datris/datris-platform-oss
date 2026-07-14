package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.Gson
import ai.datris.model.{DatrisEnvironment, EntityVersion}
import org.slf4j.LoggerFactory

/** One-time, idempotent migration: seed version 1 in the `<env>-tap-version` /
  * `<env>-pipeline-version` collections for every existing tap/pipeline that has
  * no version records yet. Runs at startup so the version-history UI / MCP tools
  * show a real v1 for pre-versioning entities instead of an empty list — without
  * waiting for the lazy seed that only fires on the first edit.
  *
  * Idempotent: an entity that already has any version record is skipped, so this
  * is safe to run on every boot. See plans/tap-pipeline-versioning.md. */
object VersionBackfill {
    private val logger = LoggerFactory.getLogger(getClass)
    private val gson = new Gson

    def run(): Unit = {
        try {
            val env = DatrisEnvironment.current
            seedTaps(env)
            seedPipelines(env)
        } catch {
            case e: Exception => logger.warn("Version backfill failed: " + e.getMessage)
        }
    }

    private def now(env: DatrisEnvironment): String = {
        val sdf = new java.text.SimpleDateFormat(env.dateFormat)
        sdf.setTimeZone(java.util.TimeZone.getTimeZone(env.dateTimezone))
        sdf.format(new java.util.Date())
    }

    private def seedTaps(env: DatrisEnvironment): Unit = {
        if (env.tapTableName == null) return
        val table = env.tapVersionTableName
        var seeded = 0
        TapConfigIO.readAll(env.tapTableName).foreach { tap =>
            if (tap != null && EntityVersionIO.latestVersion(table, tap.name) == 0) {
                val v = if (tap.version > 0) tap.version else 1
                EntityVersionIO.append(table, EntityVersion(
                    key = EntityVersionIO.docKey(tap.name, v),
                    entityName = tap.name,
                    version = v,
                    config = gson.toJson(tap),
                    scriptPath = tap.scriptPath,
                    changeNote = "(seeded from pre-versioning state)",
                    createdAt = if (tap.updatedAt != null) tap.updatedAt else now(env),
                    createdBy = "system"
                ))
                seeded += 1
            }
        }
        if (seeded > 0) logger.info("Version backfill: seeded v1 for " + seeded + " existing tap(s)")
    }

    private def seedPipelines(env: DatrisEnvironment): Unit = {
        if (env.pipelineTableName == null) return
        val table = env.pipelineVersionTableName
        var seeded = 0
        PipelineConfigIO.readAll(env.pipelineTableName).foreach { pipeline =>
            if (pipeline != null && EntityVersionIO.latestVersion(table, pipeline.name) == 0) {
                val v = if (pipeline.version > 0) pipeline.version else 1
                EntityVersionIO.append(table, EntityVersion(
                    key = EntityVersionIO.docKey(pipeline.name, v),
                    entityName = pipeline.name,
                    version = v,
                    config = gson.toJson(pipeline),
                    scriptPath = null,
                    changeNote = "(seeded from pre-versioning state)",
                    createdAt = now(env),
                    createdBy = "system"
                ))
                seeded += 1
            }
        }
        if (seeded > 0) logger.info("Version backfill: seeded v1 for " + seeded + " existing pipeline(s)")
    }
}
