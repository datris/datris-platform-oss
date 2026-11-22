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
        val tableList = NoSQLDbUtil.getPageOfItemsAsJSON(DatrisEnvironment.current.pipelineStatusSummaryTableName, page - 1, 20, "created_at")
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

    /** Rows per store page of the summary table ([[getPipelineStatusSummary]]). */
    val SummaryPageSize = 20

    /** Store pages a filled page may read beyond the page asked for
      * ([[filledSummaryPage]]): page 1 reads at most 10. */
    val SummaryOverReadPages = 9

    /** Page `page` (1-based, [[SummaryPageSize]] rows) of the summary rows
      * `keep` accepts, newest first, for a caller that may see only some
      * rows: store pages are read in order from `readPage(n)` (store page `n`,
      * 1-based, [[SummaryPageSize]] rows, newest first; a short or empty page
      * is the end of the store) until the kept rows reach the end of the page
      * asked for, the store ends, or `page` + [[SummaryOverReadPages]] store
      * pages have been read. Past that bound the page is short (possibly
      * empty), never an error. A caller that keeps every row gets exactly the
      * rows of store page `page`. A `page` below 1 reads `readPage(page)`
      * once, filtered. */
    def filledSummaryPage(
        page: Int,
        keep: PipelineStatusSummary => Boolean,
        readPage: Int => java.util.List[PipelineStatusSummary]
    ): java.util.List[PipelineStatusSummary] = {
        if (page < 1) {
            val one = Option(readPage(page)).map(_.asScala.toList).getOrElse(Nil)
            return new java.util.ArrayList[PipelineStatusSummary](one.filter(s => s != null && keep(s)).asJava)
        }
        val from = (page.toLong - 1L) * SummaryPageSize
        val until = from + SummaryPageSize
        val maxReads = page.toLong + SummaryOverReadPages
        val kept = scala.collection.mutable.ArrayBuffer[PipelineStatusSummary]()
        var n = 1
        var ended = false
        while (!ended && kept.size < until && n <= maxReads) {
            val rows = Option(readPage(n)).map(_.asScala.toList).getOrElse(Nil)
            rows.foreach(s => if (s != null && keep(s)) kept += s)
            if (rows.size < SummaryPageSize) ended = true
            n += 1
        }
        new java.util.ArrayList[PipelineStatusSummary](kept.slice(from.toInt, until.toInt).asJava)
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

    private[util] def classifyJob(events: List[PipelineStatus]): PipelineJobRollup = {
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

        // The AI fix-suggestion event (written after the error event) carries a
        // one-line headline agents/UI can show without replaying the stream.
        val aiSummary = sorted.reverse.find(_.aiSummary != null).map(_.aiSummary).orNull

        // The scratch-destination result (written by ScratchLoader after the
        // object landed) is copied onto the rollup so the caller reads the
        // pointer and first page of rows off the status it already polls.
        // Absent on every non-scratch job — the fields stay null/omitted.
        val scratch = sorted.reverse.find(_.scratchResult != null).map(_.scratchResult)

        val (status, lastErr) =
            if (errorEvent.isDefined)
                ("error", PipelineJobError(errorEvent.get.processName, errorEvent.get.description, aiSummary))
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
            lastError = lastErr,
            resultUri = scratch.map(_.resultUri).orNull,
            resultRowCount = scratch.map(r => java.lang.Long.valueOf(r.resultRowCount)).orNull,
            resultExpiresAt = scratch.map(_.resultExpiresAt).orNull,
            resultPreview = scratch.map(_.resultPreview).orNull,
            resultTruncated = scratch.map(r => java.lang.Boolean.valueOf(r.resultTruncated)).orNull
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

        PipelineStatusResponse(rollup = rollupOf(jobs), events = events)
    }

    /** The aggregate rollup of `jobs` (each already classified): all done
      * when every job is terminal; status error, warning, success or
      * processing as the jobs decide; no jobs reads not done, processing. */
    def rollupOf(jobs: List[PipelineJobRollup]): PipelineStatusRollup = {
        val terminalStates = Set("success", "warning", "error", "timed_out")
        val allDone = jobs.nonEmpty && jobs.forall(j => terminalStates.contains(j.status))

        val aggStatus =
            if (jobs.isEmpty) "processing"
            else if (jobs.exists(j => j.status == "error" || j.status == "timed_out")) "error"
            else if (jobs.exists(_.status == "warning")) "warning"
            else if (jobs.forall(_.status == "success")) "success"
            else "processing"

        PipelineStatusRollup(allDone = allDone, status = aggStatus, jobs = jobs.asJava)
    }

    def getPipelineStatusByPublisherWithRollup(publisherToken: String): PipelineStatusResponse =
        buildRollup(getPipelineStatusByPublisher(publisherToken))

    def getPipelineStatusWithRollup(pipelineToken: String): PipelineStatusResponse =
        buildRollup(getPipelineStatus(pipelineToken))
}
