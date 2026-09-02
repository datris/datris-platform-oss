package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.{Gson, JsonObject}
import ai.datris.model._
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._

/** Resolves a stamped `_datris_run_id` back to its origin: run → job status →
  * tap run log → script commit → pipeline config version → declared source.
  * Every hop is an existing lookup; this walks them in order and returns one
  * document. Read-only.
  */
object ProvenanceResolver {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    // How far back the tap-log fallback scan looks when the archived metadata
    // predates the tap-identity fields (pre-v1.26 runs).
    private val fallbackScanDays = 90
    private val fallbackScanMax = 5000

    def resolve(pipeline: String, runId: String, tapRunKey: String, configVersion: Integer): JsonObject = {
        val env = DatrisEnvironment.current
        val gson = new Gson
        val out = new JsonObject()
        out.addProperty("runId", runId)

        // 1. Archived metadata for the run (pipeline name + tap identity).
        val metadata: PipelineMetadata =
            NoSQLDbUtil.getItemJSON(env.archivedMetadataTableName, "pipeline_token", runId, "metadata")
                .map(gson.fromJson(_, classOf[PipelineMetadata]))
                .orNull

        val pipelineName =
            if (pipeline != null && pipeline.nonEmpty) pipeline
            else if (metadata != null) metadata.pipeline
            else null
        if (pipelineName != null) out.addProperty("pipeline", pipelineName)

        // 2. Job status rollup for the run.
        val statusResponse = PipelineStatusUtil.getPipelineStatusWithRollup(runId)
        val job = new JsonObject()
        val jobRollup = statusResponse.rollup.jobs.asScala.find(_.pipelineToken == runId)
        jobRollup.foreach { j =>
            job.addProperty("status", j.status)
            job.addProperty("startedAt", j.startedAt)
            job.addProperty("lastEventAt", j.lastEventAt)
            if (j.filename != null) job.addProperty("filename", j.filename)
        }
        summaryFor(runId).foreach { s =>
            job.addProperty("recordCount", s.recordCount)
            if (s.dataType != null) job.addProperty("dataType", s.dataType)
        }
        if (job.size() > 0) out.add("job", job)

        // 3. Tap run log: direct key when known (metadata or the stamped
        //    `_datris_tap_run` value passed in), else a windowed scan matching
        //    the run's publisherToken (pre-v1.26 metadata).
        val publisherToken = {
            val fromEvents = statusResponse.events.asScala.find(e => e.publisherToken != null).map(_.publisherToken)
            if (metadata != null && metadata.publisherToken != null) metadata.publisherToken
            else fromEvents.orNull
        }
        val directKey =
            if (tapRunKey != null && tapRunKey.nonEmpty) tapRunKey
            else if (metadata != null && metadata.tapName != null && metadata.tapRunTime != null)
                metadata.tapName + "|" + metadata.tapRunTime
            else null

        val tapRunLog: TapRunLog = {
            val direct =
                if (directKey != null)
                    NoSQLDbUtil.getItemJSON(env.tapLogTableName, "key", directKey, "value")
                        .map(gson.fromJson(_, classOf[TapRunLog]))
                        .orNull
                else null
            if (direct != null) direct
            else if (publisherToken != null && publisherToken != runId) findTapRunByPublisher(publisherToken)
            else null
        }

        if (tapRunLog != null) {
            val t = new JsonObject()
            t.addProperty("tapName", tapRunLog.tapName)
            t.addProperty("runTime", tapRunLog.runTime)
            t.addProperty("status", tapRunLog.status)
            t.addProperty("recordCount", tapRunLog.recordCount)
            t.addProperty("durationMs", tapRunLog.durationMs)
            if (tapRunLog.mode != null) t.addProperty("mode", tapRunLog.mode)
            if (tapRunLog.publisherToken != null) t.addProperty("publisherToken", tapRunLog.publisherToken)
            if (tapRunLog.scriptCommitSha != null) t.addProperty("scriptCommitSha", tapRunLog.scriptCommitSha)
            out.add("tapRun", t)
        }

        // 4. Tap definition + script origin + declared source.
        val tapName =
            if (tapRunLog != null) tapRunLog.tapName
            else if (metadata != null) metadata.tapName
            else null
        if (tapName != null) {
            val tapConfig = TapConfigIO.read(env.tapTableName, tapName)
            if (tapConfig != null) {
                val tap = new JsonObject()
                tap.addProperty("name", tapConfig.name)
                if (tapConfig.description != null) tap.addProperty("description", tapConfig.description)
                if (tapConfig.catalog != null) tap.addProperty("catalog", tapConfig.catalog)
                tap.addProperty("version", if (tapConfig.version > 0) tapConfig.version else 1)
                val script = new JsonObject()
                if (tapConfig.scriptStorage != null) script.addProperty("storage", tapConfig.scriptStorage)
                if (tapConfig.scriptRepoPath != null) script.addProperty("repoPath", tapConfig.scriptRepoPath)
                val sha =
                    if (tapRunLog != null && tapRunLog.scriptCommitSha != null) tapRunLog.scriptCommitSha
                    else tapConfig.scriptCommitSha
                if (sha != null) script.addProperty("commitSha", sha)
                if (script.size() > 0) tap.add("script", script)
                out.add("tap", tap)
                out.addProperty("source", TapRunner.declaredSource(tapConfig))
            }
        }
        if (!out.has("source") && metadata != null && metadata.tapSource != null)
            out.addProperty("source", metadata.tapSource)

        // 5. Pipeline config version snapshot. The version at run time comes from
        //    the stamped `_datris_config_version` (configVersion param); without
        //    it the current version is reported.
        if (pipelineName != null) {
            val current = PipelineConfigIO.read(env.pipelineTableName, pipelineName)
            val version: Int =
                if (configVersion != null) configVersion.intValue()
                else if (current != null && current.version > 0) current.version
                else 1
            val cfg = new JsonObject()
            cfg.addProperty("version", version)
            cfg.addProperty("versionSource", if (configVersion != null) "stamped" else "current")
            EntityVersionIO.get(env.pipelineVersionTableName, pipelineName, version).foreach { snapshot =>
                if (snapshot.createdAt != null) cfg.addProperty("createdAt", snapshot.createdAt)
                if (snapshot.createdBy != null) cfg.addProperty("createdBy", snapshot.createdBy)
                if (snapshot.changeNote != null) cfg.addProperty("changeNote", snapshot.changeNote)
            }
            out.add("configVersion", cfg)
        }

        out
    }

    private def summaryFor(runId: String): Option[PipelineStatusSummary] = {
        try {
            val gson = new Gson
            NoSQLDbUtil
                .queryJSONItemsByKey(DatrisEnvironment.current.pipelineStatusTableName + "-summary", "pipeline_token", runId)
                .headOption
                .map(gson.fromJson(_, classOf[PipelineStatusSummaryTable]).json)
        } catch {
            case e: Exception =>
                logger.debug("ProvenanceResolver: summary lookup failed for " + runId + ": " + e.getMessage)
                None
        }
    }

    /** Fallback for runs whose archived metadata predates the tap-identity
      * fields: windowed scan over the tap log matching publisherToken. */
    private def findTapRunByPublisher(publisherToken: String): TapRunLog = {
        try {
            val gson = new Gson
            val since = System.currentTimeMillis() - fallbackScanDays.toLong * 24 * 60 * 60 * 1000
            NoSQLDbUtil
                .getItemsSinceAsJSON(DatrisEnvironment.current.tapLogTableName, "created_at", since, fallbackScanMax)
                .flatMap { json =>
                    // Rows have shape {"key":..., "value": {...TapRunLog...}, "created_at":...}
                    try {
                        val el = com.google.gson.JsonParser.parseString(json)
                        if (el.isJsonObject && el.getAsJsonObject.has("value"))
                            Option(gson.fromJson(el.getAsJsonObject.get("value"), classOf[TapRunLog]))
                        else None
                    } catch { case _: Exception => None }
                }
                .find(log => log != null && log.publisherToken == publisherToken)
                .orNull
        } catch {
            case e: Exception =>
                logger.debug("ProvenanceResolver: tap-log scan failed: " + e.getMessage)
                null
        }
    }
}
