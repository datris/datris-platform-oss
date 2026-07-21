package ai.datris.util

import com.google.gson.Gson
import ai.datris.model.{DatrisEnvironment, DatrisException}
import ai.datris.model.PipelinePull
import org.quartz.CronExpression

import java.text.SimpleDateFormat
import java.util.Date

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

case class PipelinePullTable(
    pipeline: String,
    json: PipelinePull
)

object PipelinePullTableUtil {
    private val dateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")

    def initialize(pipeline: String, cronExpression: String): Unit = {
        val nextPullDate = generateNextPullDate(cronExpression)
        val nextPullDateAsString = dateFormatter.format(nextPullDate)

        val pipelinePull = PipelinePull(pipeline, nextPullDateAsString, null)
        val gson = new Gson()
        NoSQLDbUtil.putItemJSON(DatrisEnvironment.current.dataPullTableName, "pipeline", pipeline, "json", gson.toJson(pipelinePull))
    }

    def deleteEntryIfExists(pipeline: String): Unit = {
        // Make sure an entry already exists for the key
        val existing = NoSQLDbUtil.getItemJSON(DatrisEnvironment.current.dataPullTableName, "pipeline", pipeline, "json").orNull
        if (existing != null)
            NoSQLDbUtil.deleteItemJSON(DatrisEnvironment.current.dataPullTableName, "pipeline", pipeline)
    }

    def getAll: List[PipelinePull] = {
        val jsonItems = NoSQLDbUtil.getAllItemsAsJSON(DatrisEnvironment.current.dataPullTableName)
        val gson = new Gson()
        jsonItems.map(item => {
            val pipelinePullTable = gson.fromJson(item, classOf[PipelinePullTable])
            PipelinePull(pipelinePullTable.pipeline, pipelinePullTable.json.nextPullDate, pipelinePullTable.json.lastPullTimestampUsed)
        })
    }

    def update(pipeline: String, nextPullDate: Date, lastPullTimestampUsed: String): Unit = {
        val gson = new Gson()

        // Get the existing pull information
        val json = NoSQLDbUtil.getItemJSON(DatrisEnvironment.current.dataPullTableName, "pipeline", pipeline, "json").orNull
        if (json == null)
            throw new DatrisException(
                "The table: " + DatrisEnvironment.current.dataPullTableName + " does not contain an entry for the pipeline: " + pipeline + ", re-register the pipeline with the API"
            )
        val pipelinePull = gson.fromJson(json, classOf[PipelinePull])

        val newNextPullDate = {
            if (nextPullDate != null)
                dateFormatter.format(nextPullDate)
            else
                pipelinePull.nextPullDate
        }
        val newLastPullTimestampUsed = {
            if (lastPullTimestampUsed != null)
                lastPullTimestampUsed
            else
                pipelinePull.lastPullTimestampUsed
        }
        val newPipelinePull = PipelinePull(pipeline, newNextPullDate, newLastPullTimestampUsed)

        // Write the Dataset pull info NoSQL
        NoSQLDbUtil.putItemJSON(DatrisEnvironment.current.dataPullTableName, "pipeline", pipeline, "json", gson.toJson(newPipelinePull))
    }

    def getNextPullDate(pipeline: String): Date = {
        val json = NoSQLDbUtil.getItemJSON(DatrisEnvironment.current.dataPullTableName, "pipeline", pipeline, "json")
            .getOrElse(throw new DatrisException(
                "The table: " + DatrisEnvironment.current.dataPullTableName + " does not contain an entry for the pipeline: " + pipeline + ", re-register the pipeline with the API"
            ))
        val gson = new Gson()
        val pipelinePull = gson.fromJson(json, classOf[PipelinePull])
        dateFormatter.parse(pipelinePull.nextPullDate)
    }

    def generateNextPullDate(cronExpression: String): Date = {
        val expression = new CronExpression(cronExpression)
        expression.getNextValidTimeAfter(new Date())
    }
}
