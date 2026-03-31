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

            // If status is 'processing', calculate the actual time elapsed
            val pipelineStatusSummary = pipelineStatusSummaryTable.json
            if(pipelineStatusSummary.status.compareToIgnoreCase("processing") == 0) {
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
            else
                pipelineStatusSummary
        }).sortWith(_.createdAt > _.createdAt).asJava
    }

    def getPipelineStatus(pipelineToken: String): java.util.List[PipelineStatus] = {
        val tableList = NoSQLDbUtil.queryJSONItemsByKey(DatrisEnvironment.current.pipelineStatusTableName, "pipeline_token", pipelineToken)
        val gson = new Gson
        tableList.map(json => {
            gson.fromJson(json, classOf[PipelineStatusTable])
        }).sortWith(_.created_at.longValue() < _.created_at.longValue()).map(_.json).asJava
    }
}
