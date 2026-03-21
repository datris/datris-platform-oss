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

object DatasetStatusUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    def getDatasetStatusSummary(datasetName: String, page: Int): java.util.List[DatasetStatusSummary] = {
        val tableList = NoSQLDbUtil.getPageOfItemsAsJSON(DatrisEnvironment.values.datasetStatusTableName + "-summary", page-1, 20, "created_at")
        val gson = new Gson
        tableList.map(json => {
            val datasetStatusSummaryTable = gson.fromJson(json, classOf[DatasetStatusSummaryTable])

            // If status is 'processing', calculate the actual time elapsed
            val datasetStatusSummary = datasetStatusSummaryTable.json
            if(datasetStatusSummary.status.compareToIgnoreCase("processing") == 0) {
                val nowInMillis = new Timestamp(new Date().getTime).getTime
                val (elapsed, timedout) = ElapsedTimeUtil.getElapsedTime(nowInMillis - datasetStatusSummary.createdAt)
                val totalTime = {
                    if(timedout)
                        "timed out"
                    else
                        elapsed
                }
                if(timedout)
                    datasetStatusSummary.copy(totalTime = totalTime, status = "error")
                else
                    datasetStatusSummary.copy(totalTime = totalTime)
            }
            else
                datasetStatusSummary
        }).sortWith(_.createdAt > _.createdAt).asJava
    }

    def getDatasetStatus(pipelineToken: String): java.util.List[DatasetStatus] = {
        val tableList = NoSQLDbUtil.queryJSONItemsByKey(DatrisEnvironment.values.datasetStatusTableName, "pipeline_token", pipelineToken)
        val gson = new Gson
        tableList.map(json => {
            gson.fromJson(json, classOf[DatasetStatusTable])
        }).sortWith(_.created_at.longValue() < _.created_at.longValue()).map(_.json).asJava
    }
}
