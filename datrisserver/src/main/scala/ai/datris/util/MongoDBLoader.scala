package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.Gson
import ai.datris.model.{Notification, DatrisEnvironment, DatrisException}
import ai.datris.model._
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.util.Try

object MongoDBLoader {

    /** Records read from the staged NDJSON file per batch. Fixed: loader-side
      * memory is BatchSize × record width, whatever the payload size. */
    val BatchSize: Int = 1000
}

class MongoDBLoader(jobContext: JobContext) {
    private val logger: Logger = LoggerFactory.getLogger(classOf[MongoDBLoader])
    private val config = jobContext.config
    private val statusUtil = jobContext.statusUtil

    def process(): Unit = {
        statusUtil.overrideProcessName(this.getClass.getSimpleName)

        statusUtil.info(
            "begin",
            "Loading data into MongoDB database: " +
                config.destination.database.dbName + ", collection: " + config.destination.database.table
        )

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
        if (
            config.destination.schemaProperties == null
            || config.destination.schemaProperties.fields == null
            || !config.destination.schemaProperties.fields.asScala
                .exists(field => field.name.compareToIgnoreCase("_json") == 0)
        ) {
            throw new DatrisException("Schema must contain a '_json' field for semi-structured data ingestion into MongoDB")
        }
    }

    private def getEveryRowContainsObject: Boolean = {
        if (
            config.source != null
            && config.source.fileAttributes != null
            && config.source.fileAttributes.jsonAttributes != null
        )
            config.source.fileAttributes.jsonAttributes.everyRowContainsObject
        else
            true // Default to true
    }

    private def loadJsonDocuments(
        dbUtil: MongoDBUtil,
        collectionName: String,
        everyRowContainsObject: Boolean,
        session: com.mongodb.client.ClientSession
    ): Long = {
        val hasConfiguredKeys = config.destination.database.keyFields != null && !config.destination.database.keyFields.isEmpty
        if (hasConfiguredKeys)
            loadInBatches(everyRowContainsObject, json => dbUtil.upsertJSON(collectionName, config.destination.database.keyFields, json, session))
        else
            loadInBatches(everyRowContainsObject, json => dbUtil.insertJSON(collectionName, json, session))
    }

    private[util] def loadJsonDocuments(dbUtil: NoSQLDbUtility, collectionName: String, everyRowContainsObject: Boolean): Long = {
        val hasConfiguredKeys = config.destination.database.keyFields != null && !config.destination.database.keyFields.isEmpty
        if (hasConfiguredKeys)
            loadInBatches(everyRowContainsObject, json => dbUtil.upsertJSON(collectionName, config.destination.database.keyFields, json))
        else
            loadInBatches(everyRowContainsObject, json => dbUtil.insertJSON(collectionName, json))
    }

    /** Stream the staged NDJSON records in batches of [[MongoDBLoader.BatchSize]]
      * and write each with `write`. The Phase 1 stager already exploded a JSON
      * array into one line per element and kept NDJSON line-per-record, so
      * both `everyRowContainsObject` shapes arrive here as one record per line;
      * the flag is reported for the status trail as before. Returns the number
      * of documents written — the same count the pre-streaming code returned. */
    private def loadInBatches(everyRowContainsObject: Boolean, write: String => Unit): Long = {
        val staged = jobContext.data.staged
        if (staged == null || staged.isEmpty || staged.rowCount == 0L)
            throw new DatrisException("No raw JSON data found in the dataset")
        staged.format match {
            case StagedFormat.NdJson => ()
            case other => throw new DatrisException("No raw JSON data found in the dataset (staged payload is " + other + ")")
        }

        statusUtil.info("processing", "Processing raw JSON data, size: " + staged.bytes + " bytes")
        val hasConfiguredKeys = config.destination.database.keyFields != null && !config.destination.database.keyFields.isEmpty
        if (hasConfiguredKeys)
            statusUtil.info(
                "processing",
                "Using key fields for upsert: " +
                    config.destination.database.keyFields.asScala.mkString(", ")
            )
        else
            statusUtil.info("processing", "No key fields configured, inserting with auto-generated _id")

        var count: Long = 0
        val records = jobContext.data.recordIterator()
        try {
            val batch = new ArrayBuffer[String](MongoDBLoader.BatchSize)
            while (records.hasNext) {
                batch.clear()
                while (batch.size < MongoDBLoader.BatchSize && records.hasNext) {
                    val json = records.next().trim
                    if (json.nonEmpty) batch += json
                }
                batch.foreach { json =>
                    write(json)
                    count += 1
                }
            }
        } finally records.close()
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

        NotificationUtil.add(DatrisEnvironment.current.pipelineTopic, jsonNotification, attributes.asScala.toMap)
        statusUtil.info("processing", "notification sent: " + jsonNotification)
    }
}
