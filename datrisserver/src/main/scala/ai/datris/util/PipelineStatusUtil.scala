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
        val tableList = NoSQLDbUtil.getPageOfItemsAsJSON(DatrisEnvironment.current.pipelineStatusTableName + "-summary", page-1, 20, "created_at")
        val gson = new Gson
        tableList.map(json => {
            val pipelineStatusSummaryTable = gson.fromJson(json, classOf[PipelineStatusSummaryTable])

            // If summary says 'processing', the raw events are the ground truth — the summary
            // write path is racey and can leave a row stuck on 'processing' even after JobRunner
            // emits its end event.
            val pipelineStatusSummary = pipelineStatusSummaryTable.json
            if(pipelineStatusSummary.status.compareToIgnoreCase("processing") == 0) {
                deriveStatusFromEvents(pipelineStatusSummary.pipelineToken) match {
                    case Some((terminalStatus, latestEventMillis)) =>
                        val (elapsed, _) = ElapsedTimeUtil.getElapsedTime(latestEventMillis - pipelineStatusSummary.createdAt)
                        pipelineStatusSummary.copy(totalTime = elapsed, status = terminalStatus)
                    case None =>
                        val nowInMillis = new Timestamp(new Date().getTime).getTime
                        val (elapsed, timedout) = ElapsedTimeUtil.getElapsedTime(nowInMillis - pipelineStatusSummary.createdAt)
                        val totalTime = {
                            if(timedout)
                                "timed out"
                            else
                                elapsed
                        }
                        if(timedout)
                            pipelineStatusSummary.copy(totalTime = totalTime, status = "error")
                        else
                            pipelineStatusSummary.copy(totalTime = totalTime)
                }
            }
            else
                pipelineStatusSummary
        }).sortWith(_.createdAt > _.createdAt).asJava
    }

    private def deriveStatusFromEvents(pipelineToken: String): Option[(String, Long)] = {
        if(pipelineToken == null || pipelineToken.isEmpty)
            return None

        val tableList = NoSQLDbUtil.queryJSONItemsByKey(DatrisEnvironment.current.pipelineStatusTableName, "pipeline_token", pipelineToken)
        if(tableList == null || tableList.isEmpty)
            return None

        val gson = new Gson
        val events = tableList.map(gson.fromJson(_, classOf[PipelineStatusTable]))
        val parsed = events.map(_.json)

        val hasEnd = parsed.exists(e =>
            e.processName != null && e.processName.compareToIgnoreCase("JobRunner") == 0 &&
            e.state != null && e.state.compareToIgnoreCase("end") == 0)
        val hasError = parsed.exists(e => e.code != null && e.code.compareToIgnoreCase("error") == 0)
        val hasWarning = parsed.exists(e => e.code != null && e.code.compareToIgnoreCase("warning") == 0)

        val latestEventMillis = events.map(_.created_at).max

        if(hasError)
            Some(("error", latestEventMillis))
        else if(hasEnd && hasWarning)
            Some(("warning", latestEventMillis))
        else if(hasEnd)
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
}
