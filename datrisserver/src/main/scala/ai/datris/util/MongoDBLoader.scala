package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.{Gson, JsonParser}
import ai.datris.model.{Notification, DatrisEnvironment, DatrisException}
import ai.datris.model._
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._
import scala.util.Try

class MongoDBLoader(jobContext: JobContext) {
    private val logger: Logger = LoggerFactory.getLogger(classOf[MongoDBLoader])
    private val config = jobContext.config
    private val statusUtil = jobContext.statusUtil

    def process(): Unit = {
        statusUtil.overrideProcessName(this.getClass.getSimpleName)

        statusUtil.info("begin", "Loading data into MongoDB database: " +
            config.destination.database.dbName + ", collection: " + config.destination.database.table)

        val secrets = SecretsRetrieverUtil.mongoDbSecrets()
        val collectionName = config.destination.database.table

        validateJsonSchema()

        val everyRowContainsObject = getEveryRowContainsObject
        statusUtil.info("processing", "everyRowContainsObject: " + everyRowContainsObject)

        if (config.destination.database.useTransaction) {
            val (mongoClient, mongoUtil) = MongoDBUtilBuilder.buildWithClient(secrets.connectionString, config.destination.database.dbName)
            statusUtil.info("processing", "MongoDB connection acquired")
            val session = mongoClient.startSession()
            session.startTransaction()
            try {
                if (config.destination.database.truncateBeforeWrite) {
                    statusUtil.info("processing", "'truncateTableBeforeWrite' is set to true, deleting all documents from collection")
                    val deleted = mongoUtil.deleteAll(collectionName, session)
                    statusUtil.info("processing", "Collection truncated, documents deleted: " + deleted)
                }
                val documentsInserted = loadJsonDocuments(mongoUtil, collectionName, everyRowContainsObject, session)
                statusUtil.info("processing", "Documents inserted into collection: " + documentsInserted.toString)
                session.commitTransaction()
            } catch {
                case e: Exception =>
                    Try(session.abortTransaction())
                    throw e
            } finally {
                session.close()
            }
        } else {
            val dbUtil = MongoDBUtilBuilder.build(secrets.connectionString, config.destination.database.dbName)
            statusUtil.info("processing", "MongoDB connection acquired")
            try {
                if (config.destination.database.truncateBeforeWrite) {
                    statusUtil.info("processing", "'truncateTableBeforeWrite' is set to true, deleting all documents from collection")
                    val deleted = dbUtil.deleteAll(collectionName)
                    statusUtil.info("processing", "Collection truncated, documents deleted: " + deleted)
                }
                val documentsInserted = loadJsonDocuments(dbUtil, collectionName, everyRowContainsObject)
                statusUtil.info("processing", "Documents inserted into collection: " + documentsInserted.toString)
            } catch {
                case e: Exception => throw e
            }
        }

        sendNotification()
        statusUtil.info("end", "Process completed")
    }

    private def validateJsonSchema(): Unit = {
        // Follow the Pipeline pattern: check schemaProperties for _json field
        if (config.destination.schemaProperties == null
            || config.destination.schemaProperties.fields == null
            || !config.destination.schemaProperties.fields.asScala
            .exists(field => field.name.compareToIgnoreCase("_json") == 0)) {
            throw new DatrisException("Schema must contain a '_json' field for semi-structured data ingestion into MongoDB")
        }
    }

    private def getEveryRowContainsObject: Boolean = {
        if (config.source != null
            && config.source.fileAttributes != null
            && config.source.fileAttributes.jsonAttributes != null)
            config.source.fileAttributes.jsonAttributes.everyRowContainsObject
        else
            true // Default to true
    }

    private def loadJsonDocuments(dbUtil: MongoDBUtil, collectionName: String, everyRowContainsObject: Boolean, session: com.mongodb.client.ClientSession): Long = {
        val rawData = jobContext.data.rawData
        if (rawData == null || rawData.trim.isEmpty)
            throw new DatrisException("No raw JSON data found in the dataset")

        statusUtil.info("processing", "Processing raw JSON data, size: " + rawData.length + " bytes")

        val jsonBlobs: Seq[String] = {
            if (everyRowContainsObject) {
                rawData.split("\n").map(_.trim).filter(_.nonEmpty).toSeq
            } else {
                val parsed = JsonParser.parseString(rawData.trim)
                if (parsed.isJsonArray) {
                    val gson = new Gson()
                    parsed.getAsJsonArray.asScala
                        .map(element => gson.toJson(element))
                        .toSeq
                } else {
                    Seq(rawData.trim)
                }
            }
        }

        val hasConfiguredKeys = config.destination.database.keyFields != null && !config.destination.database.keyFields.isEmpty
        var count: Long = 0
        if (hasConfiguredKeys) {
            statusUtil.info("processing", "Using key fields for upsert: " +
                config.destination.database.keyFields.asScala.mkString(", "))
            jsonBlobs.foreach(json => {
                dbUtil.upsertJSON(collectionName, config.destination.database.keyFields, json, session)
                count += 1
            })
        } else {
            statusUtil.info("processing", "No key fields configured, inserting with auto-generated _id")
            jsonBlobs.foreach(json => {
                dbUtil.insertJSON(collectionName, json, session)
                count += 1
            })
        }
        count
    }

    private def loadJsonDocuments(dbUtil: NoSQLDbUtility, collectionName: String, everyRowContainsObject: Boolean): Long = {
        val rawData = jobContext.data.rawData
        if (rawData == null || rawData.trim.isEmpty)
            throw new DatrisException("No raw JSON data found in the dataset")

        statusUtil.info("processing", "Processing raw JSON data, size: " + rawData.length + " bytes")

        val jsonBlobs: Seq[String] = {
            if (everyRowContainsObject) {
                // Each line contains a complete JSON object (NDJSON)
                rawData.split("\n").map(_.trim).filter(_.nonEmpty).toSeq
            } else {
                val parsed = JsonParser.parseString(rawData.trim)

                if (parsed.isJsonArray) {
                    // Unwrap the array - each element becomes a separate document
                    val gson = new Gson()
                    parsed.getAsJsonArray.asScala
                        .map(element => gson.toJson(element))
                        .toSeq
                } else {
                    // Single JSON object
                    Seq(rawData.trim)
                }
            }
        }

        val hasConfiguredKeys = config.destination.database.keyFields != null && !config.destination.database.keyFields.isEmpty

        var count: Long = 0
        if (hasConfiguredKeys) {
            // Upsert using the configured key fields - supports compound keys
            statusUtil.info("processing", "Using key fields for upsert: " +
                config.destination.database.keyFields.asScala.mkString(", "))

            jsonBlobs.foreach(json => {
                dbUtil.upsertJSON(collectionName, config.destination.database.keyFields, json)
                count += 1
            })
        } else {
            // No key fields configured - let MongoDB auto-generate _id
            statusUtil.info("processing", "No key fields configured, inserting with auto-generated _id")

            jsonBlobs.foreach(json => {
                dbUtil.insertJSON(collectionName, json)
                count += 1
            })
        }

        count
    }

    private def sendNotification(): Unit = {
        val notification = Notification(
            config.name,
            jobContext.metadata.publisherToken,
            jobContext.pipelineToken,
            "mongodb",
            null,
            null,
            null,
            null,
            config.destination.database.dbName,
            config.destination.database.table,
            null
        )
        val gson = new Gson
        val jsonNotification = gson.toJson(notification)

        val attributes = new java.util.HashMap[String, String]
        attributes.put("pipeline", config.name)
        attributes.put("destination", "mongodb")
        attributes.put("database", config.destination.database.dbName)
        attributes.put("table", config.destination.database.table)

        NotificationUtil.add(DatrisEnvironment.values.pipelineTopic, jsonNotification, attributes.asScala.toMap)
        statusUtil.info("processing", "notification sent: " + jsonNotification)
    }
}
