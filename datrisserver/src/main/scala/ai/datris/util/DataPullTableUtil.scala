package ai.datris.util

import com.google.gson.Gson
import ai.datris.model.{DatrisEnvironment, DatrisException}
import ai.datris.model.DatasetPull
import org.quartz.CronExpression

import java.text.SimpleDateFormat
import java.util.Date

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

case class DatasetPullTable(
                               dataset: String,
                               json: DatasetPull
                           )

object DataPullTableUtil {
    private val dateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")

    def initialize(dataset: String, cronExpression: String): Unit = {
        val nextPullDate = generateNextPullDate(cronExpression)
        val nextPullDateAsString = dateFormatter.format(nextPullDate)

        val datasetPull = DatasetPull(dataset, nextPullDateAsString, null)
        val gson = new Gson()
        NoSQLDbUtil.putItemJSON(DatrisEnvironment.values.dataPullTableName, "dataset", dataset, "json", gson.toJson(datasetPull))
    }

    def deleteEntryIfExists(dataset: String): Unit = {
        // Make sure an entry already exists for the key
        val existing = NoSQLDbUtil.getItemJSON(DatrisEnvironment.values.dataPullTableName, "dataset", dataset, "json").orNull
        if(existing != null)
            NoSQLDbUtil.deleteItemJSON(DatrisEnvironment.values.dataPullTableName, "dataset", dataset)
    }

    def getAll: List[DatasetPull] = {
        val jsonItems = NoSQLDbUtil.getAllItemsAsJSON(DatrisEnvironment.values.dataPullTableName)
        val gson = new Gson()
        jsonItems.map(item => {
            val datasetPullTable = gson.fromJson(item, classOf[DatasetPullTable])
            DatasetPull(datasetPullTable.dataset, datasetPullTable.json.nextPullDate, datasetPullTable.json.lastPullTimestampUsed)
        })
    }

    def update(dataset: String, nextPullDate: Date, lastPullTimestampUsed: String): Unit = {
        val gson = new Gson()

        // Get the existing pull information
        val json = NoSQLDbUtil.getItemJSON(DatrisEnvironment.values.dataPullTableName, "dataset", dataset, "json").orNull
        if(json == null)
            throw new DatrisException("The table: " + DatrisEnvironment.values.dataPullTableName + " does not contain an entry for the dataset: " + dataset + ", re-register the dataset with the API")
        val datasetPull = gson.fromJson(json, classOf[DatasetPull])

        val newNextPullDate = {
            if(nextPullDate != null)
                dateFormatter.format(nextPullDate)
            else
                datasetPull.nextPullDate
        }
        val newLastPullTimestampUsed = {
            if(lastPullTimestampUsed != null)
                lastPullTimestampUsed
            else
                datasetPull.lastPullTimestampUsed
        }
        val newDatasetPull = DatasetPull(dataset, newNextPullDate, newLastPullTimestampUsed)

        // Write the Dataset pull info NoSQL
        NoSQLDbUtil.putItemJSON(DatrisEnvironment.values.dataPullTableName, "dataset", dataset, "json", gson.toJson(newDatasetPull))
    }

    def getNextPullDate(dataset: String): Date = {
        val json = NoSQLDbUtil.getItemJSON(DatrisEnvironment.values.dataPullTableName, "dataset", dataset, "json")
            .getOrElse(throw new DatrisException("The table: " + DatrisEnvironment.values.dataPullTableName + " does not contain an entry for the dataset: " + dataset + ", re-register the dataset with the API"))
        val gson = new Gson()
        val datasetPull = gson.fromJson(json, classOf[DatasetPull])
        dateFormatter.parse(datasetPull.nextPullDate)
    }

    def generateNextPullDate(cronExpression: String): Date = {
        val expression = new CronExpression(cronExpression)
        expression.getNextValidTimeAfter(new Date())
    }
}
