package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.common.base.Throwables
import com.google.gson.Gson
import ai.datris.model.{PipelineConfig, DatrisEnvironment, DatrisException}
import ai.datris.util.{PipelineConfigIO, NoSQLDbUtil}
import ai.datris.util._
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import java.sql.DriverManager
import java.util.Properties
import scala.collection.JavaConverters._

@RestController
@RequestMapping(Array("/api/v1"))
@CrossOrigin(origins = Array("*"), methods = Array(RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE, RequestMethod.OPTIONS))
class PipelineAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[PipelineAPIController])

    @GetMapping(path = Array("/pipeline"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getPipeline(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                         @RequestParam pipeline: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /pipeline called with pipeline: " + pipeline)
            APIKeyValidator.validate(apiKey)

            val config = PipelineConfigIO.read(DatrisEnvironment.values.pipelineTableName, pipeline)
            if(config == null)
                throw new DatrisException("Pipeline: " + pipeline + " is not configured in the NoSQL database")
            val gson = new Gson
            val json = gson.toJson(config)
            new ResponseEntity[String](json, HttpStatus.OK)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @GetMapping(path = Array("/pipelines"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getPipelines(@RequestHeader(name = "x-api-key", required = false) apiKey: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /pipelines called")
            APIKeyValidator.validate(apiKey)

            val pipelineNames = NoSQLDbUtil.getItemsKeysByKeyName(DatrisEnvironment.values.pipelineTableName, "name")
            val pipelineConfigs = pipelineNames.map(name => {
                PipelineConfigIO.read(DatrisEnvironment.values.pipelineTableName, name)
            }).asJava

            val gson = new Gson
            val json = gson.toJson(pipelineConfigs)
            new ResponseEntity[String](json, HttpStatus.OK)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/pipeline"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def putPipeline(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                         @RequestBody config: PipelineConfig): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /pipeline with pipeline name: " + config.name)
            APIKeyValidator.validate(apiKey)

            PipelineValidatorUtil.validate(config)
            val modifiedConfig = PipelineValidatorUtil.modify(config)

            // Write to NoSQL pipeline table
            PipelineConfigIO.write(modifiedConfig)

            // If the source is a database, initialize the pipeline pull table
            if(modifiedConfig.source.databaseAttributes != null)
                PipelinePullTableUtil.initialize(modifiedConfig.name, modifiedConfig.source.databaseAttributes.cronExpression)

            new ResponseEntity[String](HttpStatus.OK)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @DeleteMapping(path = Array("/pipeline"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def deletePipeline(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                            @RequestParam pipeline: String,
                            @RequestParam(defaultValue = "true") deleteData: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint DELETE /pipeline with pipeline name: " + pipeline + ", deleteData: " + deleteData)
            APIKeyValidator.validate(apiKey)

            val config = PipelineConfigIO.read(DatrisEnvironment.values.pipelineTableName, pipeline)
            if(config == null)
                throw new DatrisException("Pipeline: " + pipeline + " is not configured in the NoSQL database")

            if(config.source.databaseAttributes != null)
                PipelinePullTableUtil.deleteEntryIfExists(config.name)

            // Clean up destination data
            if(deleteData.equalsIgnoreCase("true") && config.destination != null) {
                cleanupDestinationData(config)
            }

            // Delete the json configuration
            NoSQLDbUtil.deleteItemJSON(DatrisEnvironment.values.pipelineTableName, "name", pipeline)

            new ResponseEntity[String](HttpStatus.OK)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    private def cleanupDestinationData(config: PipelineConfig): Unit = {
        val dest = config.destination

        // PostgreSQL — DROP TABLE
        if (dest.database != null && dest.database.usePostgres) {
            try {
                val secrets = SecretsRetrieverUtil.postgresSecrets()
                Class.forName("org.postgresql.Driver")
                val properties = new Properties()
                properties.setProperty("user", secrets.username)
                properties.setProperty("password", secrets.password)
                val afterProtocol = secrets.jdbcUrl.replaceFirst("^jdbc:postgresql://", "")
                val jdbcUrl = if (afterProtocol.contains("/")) secrets.jdbcUrl else secrets.jdbcUrl + "/" + dest.database.dbName
                val conn = DriverManager.getConnection(jdbcUrl, properties)
                try {
                    val schema = if (dest.database.schema != null) dest.database.schema else "public"
                    val stmt = conn.createStatement()
                    stmt.execute("DROP TABLE IF EXISTS \"" + schema + "\".\"" + dest.database.table + "\" CASCADE")
                    stmt.close()
                    logger.info("Dropped PostgreSQL table: " + schema + "." + dest.database.table)
                } finally {
                    conn.close()
                }
            } catch {
                case e: Exception => logger.warn("Failed to drop PostgreSQL table: " + e.getMessage)
            }
        }

        // MongoDB — drop collection
        if (dest.database != null && dest.database.useMongoDB) {
            try {
                val secrets = SecretsRetrieverUtil.mongoDbSecrets()
                val connString = new com.mongodb.ConnectionString(secrets.connectionString)
                val settings = com.mongodb.MongoClientSettings.builder().applyConnectionString(connString).build()
                val client = com.mongodb.client.MongoClients.create(settings)
                try {
                    val dbName = if (dest.database.dbName != null) dest.database.dbName else "datris"
                    client.getDatabase(dbName).getCollection(dest.database.table).drop()
                    logger.info("Dropped MongoDB collection: " + dbName + "." + dest.database.table)
                } finally {
                    client.close()
                }
            } catch {
                case e: Exception => logger.warn("Failed to drop MongoDB collection: " + e.getMessage)
            }
        }

        // pgvector — DROP TABLE
        if (dest.pgvector != null) {
            try {
                val secretName = DatrisEnvironment.values.pgvectorSecretName
                val secret = SecretsUtil.getSecretMap(secretName)
                if (secret.isDefined) {
                    val secretMap = secret.get
                    val jdbcUrl = secretMap.get("jdbcUrl")
                    val username = secretMap.get("username")
                    val password = secretMap.get("password")
                    if (jdbcUrl != null) {
                        Class.forName("org.postgresql.Driver")
                        val properties = new Properties()
                        properties.setProperty("user", username)
                        properties.setProperty("password", password)
                        val conn = DriverManager.getConnection(jdbcUrl, properties)
                        try {
                            val schema = if (dest.pgvector.schemaName != null) dest.pgvector.schemaName else "public"
                            val stmt = conn.createStatement()
                            stmt.execute("DROP TABLE IF EXISTS \"" + schema + "\".\"" + dest.pgvector.tableName + "\" CASCADE")
                            stmt.close()
                            logger.info("Dropped pgvector table: " + schema + "." + dest.pgvector.tableName)
                        } finally {
                            conn.close()
                        }
                    }
                }
            } catch {
                case e: Exception => logger.warn("Failed to drop pgvector table: " + e.getMessage)
            }
        }

        // Qdrant — delete collection
        if (dest.qdrant != null) {
            try {
                val secretName = DatrisEnvironment.values.qdrantSecretName
                val secret = SecretsUtil.getSecretMap(secretName)
                if (secret.isDefined) {
                    val secretMap = secret.get
                    val host = Option(secretMap.get("host")).getOrElse("")
                    if (host.nonEmpty) {
                        val grpcPort = Option(secretMap.get("port")).map(_.toInt).getOrElse(6334)
                        val restPort = grpcPort - 1
                        val url = "http://" + host + ":" + restPort + "/collections/" + dest.qdrant.collectionName
                        val connection = new java.net.URL(url).openConnection().asInstanceOf[java.net.HttpURLConnection]
                        connection.setRequestMethod("DELETE")
                        connection.setConnectTimeout(5000)
                        connection.setReadTimeout(5000)
                        val apiKey = Option(secretMap.get("apiKey")).filter(_.nonEmpty)
                        apiKey.foreach(k => connection.setRequestProperty("api-key", k))
                        try {
                            connection.getResponseCode
                            logger.info("Deleted Qdrant collection: " + dest.qdrant.collectionName)
                        } finally {
                            connection.disconnect()
                        }
                    }
                }
            } catch {
                case e: Exception => logger.warn("Failed to delete Qdrant collection: " + e.getMessage)
            }
        }

        // Weaviate — delete class
        if (dest.weaviate != null) {
            try {
                val secretName = DatrisEnvironment.values.weaviateSecretName
                val secret = SecretsUtil.getSecretMap(secretName)
                if (secret.isDefined) {
                    val secretMap = secret.get
                    val host = Option(secretMap.get("host")).getOrElse("")
                    if (host.nonEmpty) {
                        val port = Option(secretMap.get("port")).getOrElse("8079")
                        val scheme = Option(secretMap.get("scheme")).getOrElse("http")
                        val url = scheme + "://" + host + ":" + port + "/v1/schema/" + dest.weaviate.className
                        val connection = new java.net.URL(url).openConnection().asInstanceOf[java.net.HttpURLConnection]
                        connection.setRequestMethod("DELETE")
                        connection.setConnectTimeout(5000)
                        connection.setReadTimeout(5000)
                        val apiKey = Option(secretMap.get("apiKey")).filter(_.nonEmpty)
                        apiKey.foreach(k => connection.setRequestProperty("Authorization", "Bearer " + k))
                        try {
                            connection.getResponseCode
                            logger.info("Deleted Weaviate class: " + dest.weaviate.className)
                        } finally {
                            connection.disconnect()
                        }
                    }
                }
            } catch {
                case e: Exception => logger.warn("Failed to delete Weaviate class: " + e.getMessage)
            }
        }

        // Milvus — drop collection
        if (dest.milvus != null) {
            try {
                val secretName = DatrisEnvironment.values.milvusSecretName
                val secret = SecretsUtil.getSecretMap(secretName)
                if (secret.isDefined) {
                    val secretMap = secret.get
                    val host = Option(secretMap.get("host")).getOrElse("")
                    if (host.nonEmpty) {
                        val port = Option(secretMap.get("port")).getOrElse("19530")
                        val url = "http://" + host + ":" + port + "/v2/vectordb/collections/drop"
                        val connection = new java.net.URL(url).openConnection().asInstanceOf[java.net.HttpURLConnection]
                        connection.setRequestMethod("POST")
                        connection.setDoOutput(true)
                        connection.setConnectTimeout(5000)
                        connection.setReadTimeout(5000)
                        connection.setRequestProperty("Content-Type", "application/json")
                        val apiKey = Option(secretMap.get("apiKey")).filter(_.nonEmpty)
                        apiKey.foreach(k => connection.setRequestProperty("Authorization", "Bearer " + k))
                        try {
                            val payload = "{\"collectionName\": \"" + dest.milvus.collectionName + "\"}"
                            val os = connection.getOutputStream
                            os.write(payload.getBytes)
                            os.close()
                            connection.getResponseCode
                            logger.info("Dropped Milvus collection: " + dest.milvus.collectionName)
                        } finally {
                            connection.disconnect()
                        }
                    }
                }
            } catch {
                case e: Exception => logger.warn("Failed to drop Milvus collection: " + e.getMessage)
            }
        }

        // Chroma — delete collection
        if (dest.chroma != null) {
            try {
                val secretName = DatrisEnvironment.values.chromaSecretName
                val secret = SecretsUtil.getSecretMap(secretName)
                if (secret.isDefined) {
                    val secretMap = secret.get
                    val host = Option(secretMap.get("host")).getOrElse("")
                    if (host.nonEmpty) {
                        val port = Option(secretMap.get("port")).getOrElse("8000")
                        val url = "http://" + host + ":" + port + "/api/v2/tenants/default_tenant/databases/default_database/collections/" + dest.chroma.collectionName
                        val connection = new java.net.URL(url).openConnection().asInstanceOf[java.net.HttpURLConnection]
                        connection.setRequestMethod("DELETE")
                        connection.setConnectTimeout(5000)
                        connection.setReadTimeout(5000)
                        try {
                            connection.getResponseCode
                            logger.info("Deleted Chroma collection: " + dest.chroma.collectionName)
                        } finally {
                            connection.disconnect()
                        }
                    }
                }
            } catch {
                case e: Exception => logger.warn("Failed to delete Chroma collection: " + e.getMessage)
            }
        }

        // Object Store — skipped (no bulk delete API available)
    }
}
