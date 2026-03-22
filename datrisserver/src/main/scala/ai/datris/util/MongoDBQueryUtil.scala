package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.{DatrisEnvironment, DatrisException}
import org.bson.Document
import org.bson.json.{JsonMode, JsonWriterSettings}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._

object MongoDBQueryUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    private val MAX_LIMIT = 1000
    private val DEFAULT_LIMIT = 20
    private val jsonSettings = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build()

    // Operators that allow arbitrary JavaScript execution
    private val BLOCKED_OPERATORS = Set("$where", "$function", "$accumulator")

    def query(collection: String,
              filter: java.util.Map[String, Any] = new java.util.HashMap[String, Any](),
              projection: java.util.Map[String, Any] = null,
              limit: Int = DEFAULT_LIMIT,
              database: String = null): java.util.List[String] = {

        if (collection == null || collection.trim.isEmpty)
            throw new DatrisException("MongoDB collection name cannot be empty")

        val effectiveLimit = math.min(if (limit > 0) limit else DEFAULT_LIMIT, MAX_LIMIT)

        // Validate filter for dangerous operators
        if (filter != null) validateFilter(filter)

        logger.info("Querying MongoDB collection: " + collection + " with limit: " + effectiveLimit)

        val secrets = SecretsRetrieverUtil.mongoDbSecrets()
        val dbUtil = MongoDBUtilBuilder.build(secrets.connectionString, DatrisEnvironment.values.mongoDbConfig.database)

        // Use the underlying MongoDBUtil's database to run a find query
        val connString = new com.mongodb.ConnectionString(secrets.connectionString)
        val settings = com.mongodb.MongoClientSettings.builder()
            .applyConnectionString(connString)
            .build()
        val client = com.mongodb.client.MongoClients.create(settings)

        try {
            val dbName = if (database != null && database.nonEmpty) database else DatrisEnvironment.values.mongoDbConfig.database
            val db = client.getDatabase(dbName)
            val coll = db.getCollection(collection)

            val filterDoc = if (filter != null && !filter.isEmpty) Document.parse(new com.google.gson.Gson().toJson(filter))
                else new Document()

            var cursor = coll.find(filterDoc)

            if (projection != null && !projection.isEmpty) {
                val projDoc = Document.parse(new com.google.gson.Gson().toJson(projection))
                cursor = cursor.projection(projDoc)
            }

            val results = cursor.limit(effectiveLimit)
                .asScala
                .map(doc => doc.toJson(jsonSettings))
                .toList

            logger.info("Query returned " + results.size + " documents")
            results.asJava
        } finally {
            client.close()
        }
    }

    private def validateFilter(filter: java.util.Map[String, Any]): Unit = {
        val filterJson = new com.google.gson.Gson().toJson(filter)
        BLOCKED_OPERATORS.foreach { op =>
            if (filterJson.contains("\"" + op + "\""))
                throw new DatrisException("MongoDB filter contains blocked operator: " + op)
        }
    }
}
