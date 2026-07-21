package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.Gson
import ai.datris.model._
import org.slf4j.{Logger, LoggerFactory}

import java.sql.Timestamp
import java.util.Date
import scala.collection.JavaConverters._

object PipelineStatusUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    def getPipelineStatusSummary(pipelineName: String, page: Int): java.util.List[PipelineStatusSummary] = {
        val tableList = NoSQLDbUtil.getPageOfItemsAsJSON(DatrisEnvironment.current.pipelineStatusTableName + "-summary", page - 1, 20, "created_at")
        val gson = new Gson
        tableList.map(json => {
            val pipelineStatusSummaryTable = gson.fromJson(json, classOf[PipelineStatusSummaryTable])

            // If summary says 'processing', the raw events are the ground truth — the summary
            // write path is racey and can leave a row stuck on 'processing' even after JobRunner
            // emits its end event.
            val pipelineStatusSummary = pipelineStatusSummaryTable.json
            if (pipelineStatusSummary.status.compareToIgnoreCase("processing") == 0) {
                deriveStatusFromEvents(pipelineStatusSummary.pipelineToken) match {
                    case Some((terminalStatus, latestEventMillis)) =>
                        val (elapsed, _) = ElapsedTimeUtil.getElapsedTime(latestEventMillis - pipelineStatusSummary.createdAt)
                        pipelineStatusSummary.copy(totalTime = elapsed, status = terminalStatus)
                    case None =>
                        val nowInMillis = new Timestamp(new Date().getTime).getTime
                        val (elapsed, timedout) = ElapsedTimeUtil.getElapsedTime(nowInMillis - pipelineStatusSummary.createdAt)
                        val totalTime = {
                            if (timedout)
                                "timed out"
                            else
                                elapsed
                        }
                        if (timedout)
                            pipelineStatusSummary.copy(totalTime = totalTime, status = "error")
                        else
                            pipelineStatusSummary.copy(totalTime = totalTime)
                }
            } else
                pipelineStatusSummary
        }).sortWith(_.createdAt > _.createdAt).asJava
    }

    private def deriveStatusFromEvents(pipelineToken: String): Option[(String, Long)] = {
        if (pipelineToken == null || pipelineToken.isEmpty)
            return None

        val tableList = NoSQLDbUtil.queryJSONItemsByKey(DatrisEnvironment.current.pipelineStatusTableName, "pipeline_token", pipelineToken)
        if (tableList == null || tableList.isEmpty)
            return None

        val gson = new Gson
        val events = tableList.map(gson.fromJson(_, classOf[PipelineStatusTable]))
        val parsed = events.map(_.json)

        val hasEnd = parsed.exists(e =>
            e.processName != null && e.processName.compareToIgnoreCase("JobRunner") == 0 &&
                e.state != null && e.state.compareToIgnoreCase("end") == 0
        )
        val hasError = parsed.exists(e => e.code != null && e.code.compareToIgnoreCase("error") == 0)
        val hasWarning = parsed.exists(e => e.code != null && e.code.compareToIgnoreCase("warning") == 0)

        val latestEventMillis = events.map(_.created_at).max

        if (hasError)
            Some(("error", latestEventMillis))
        else if (hasEnd && hasWarning)
            Some(("warning", latestEventMillis))
        else if (hasEnd)
            Some(("success", latestEventMillis))
        else
            None
    }

    def getPipelineStatus(pipelineToken: String): java.util.List[PipelineStatus] = {
        val tableList = NoSQLDbUtil.queryJSONItemsByKey(DatrisEnvironment.current.pipelineStatusTableName, "pipeline_token", pipelineToken)
        val gson = new Gson
        tableList.map(json => {
            gson.fromJson(json, classOf[PipelineStatusTable])
        }).sortWith(_.created_at.longValue() < _.created_at.longValue()).map(_.json).asJava
    }

    // Query every status row whose publisher token matches — used to watch all jobs
    // a single tap run submitted (document taps fan out to many pipelineTokens but share
    // one publisherToken). Prefers the top-level indexed `publisher_token` field that
    // StatusUtil.writeToNoSQLDb populates alongside `pipeline_token`. Falls back to the
    // legacy nested `json.publisherToken` path so rows written before the top-level field
    // existed remain readable — no migration step required, mixed-vintage rows just work.
    def getPipelineStatusByPublisher(publisherToken: String): java.util.List[PipelineStatus] = {
        val tableName = DatrisEnvironment.current.pipelineStatusTableName
        val gson = new Gson

        val primary = NoSQLDbUtil.queryJSONItemsByKey(tableName, "publisher_token", publisherToken)
        val rows = {
            if (primary.nonEmpty) primary
            else NoSQLDbUtil.queryJSONItemsByKey(tableName, "json.publisherToken", publisherToken)
        }

        rows.map(json => {
            gson.fromJson(json, classOf[PipelineStatusTable])
        }).sortWith(_.created_at.longValue() < _.created_at.longValue()).map(_.json).asJava
    }

    // ---- Rollup: per-token classification + aggregate response. Used by callers
    // (the MCP `get_pipeline_status` tool, primarily) that want a single boolean to
    // poll on instead of having to replay the begin/info/end/error rules themselves.

    private def classifyJob(events: List[PipelineStatus]): PipelineJobRollup = {
        val sorted = events.sortBy(_.epoch)
        val first = sorted.head
        val last = sorted.last

        val errorEvent = sorted.find(e => e.code != null && e.code.compareToIgnoreCase("error") == 0)
        val hasJobRunnerEnd = sorted.exists(e =>
            e.processName != null && e.processName.compareToIgnoreCase("JobRunner") == 0 &&
                e.state != null && e.state.compareToIgnoreCase("end") == 0 &&
                e.code != null && e.code.compareToIgnoreCase("info") == 0
        )
        val hasWarning = sorted.exists(e => e.code != null && e.code.compareToIgnoreCase("warning") == 0)

        val nowMillis = new Timestamp(new Date().getTime).getTime
        val (elapsedStr, timedOut) = ElapsedTimeUtil.getElapsedTime(nowMillis - first.epoch)

        val (status, lastErr) =
            if (errorEvent.isDefined)
                ("error", PipelineJobError(errorEvent.get.processName, errorEvent.get.description))
            else if (hasJobRunnerEnd && hasWarning)
                ("warning", null)
            else if (hasJobRunnerEnd)
                ("success", null)
            else if (timedOut)
                ("timed_out", null)
            else
                ("processing", null)

        // For terminal states, elapsed = last - first; for processing, elapsed = now - first
        val finalElapsed = status match {
            case "success" | "warning" | "error" =>
                ElapsedTimeUtil.getElapsedTime(last.epoch - first.epoch)._1
            case _ => elapsedStr
        }

        PipelineJobRollup(
            pipelineToken = first.pipelineToken,
            pipeline = first.pipeline,
            filename = first.filename,
            status = status,
            startedAt = first.dateTime,
            lastEventAt = last.dateTime,
            elapsed = finalElapsed,
            lastError = lastErr
        )
    }

    private def buildRollup(events: java.util.List[PipelineStatus]): PipelineStatusResponse = {
        val scalaEvents = events.asScala.toList

        val jobs: List[PipelineJobRollup] =
            if (scalaEvents.isEmpty) Nil
            else scalaEvents
                .filter(_.pipelineToken != null)
                .groupBy(_.pipelineToken)
                .toList
                .map { case (_, rows) => classifyJob(rows) }
                .sortBy(_.startedAt)

        val terminalStates = Set("success", "warning", "error", "timed_out")
        val allDone = jobs.nonEmpty && jobs.forall(j => terminalStates.contains(j.status))

        val aggStatus =
            if (jobs.isEmpty) "processing"
            else if (jobs.exists(j => j.status == "error" || j.status == "timed_out")) "error"
            else if (jobs.exists(_.status == "warning")) "warning"
            else if (jobs.forall(_.status == "success")) "success"
            else "processing"

        PipelineStatusResponse(
            rollup = PipelineStatusRollup(allDone = allDone, status = aggStatus, jobs = jobs.asJava),
            events = events
        )
    }

    def getPipelineStatusByPublisherWithRollup(publisherToken: String): PipelineStatusResponse =
        buildRollup(getPipelineStatusByPublisher(publisherToken))

    def getPipelineStatusWithRollup(pipelineToken: String): PipelineStatusResponse =
        buildRollup(getPipelineStatus(pipelineToken))
}
