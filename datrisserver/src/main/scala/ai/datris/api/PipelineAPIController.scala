package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.common.base.Throwables
import com.google.gson.Gson
import ai.datris.auth.{CapabilityCheck, ResolvedKeyAccess, VersionActor}
import ai.datris.model.{PipelineConfig, DatrisEnvironment, DatrisException, EntityVersion}
import ai.datris.util.{PipelineConfigIO, NoSQLDbUtil}
import ai.datris.util._
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import java.sql.DriverManager
import java.util.Properties
import scala.collection.JavaConverters._

@RestController
@RequestMapping(Array("/api/v1"))
class PipelineAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[PipelineAPIController])

    @GetMapping(path = Array("/pipeline"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getPipeline(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                         @RequestParam pipeline: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /pipeline called with pipeline: " + pipeline)
            APIKeyValidator.validate(apiKey)

            val config = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, pipeline)
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
    def getPipelines(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                     request: HttpServletRequest): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /pipelines called")
            APIKeyValidator.validate(apiKey)

            val pipelineNames = NoSQLDbUtil.getItemsKeysByKeyName(DatrisEnvironment.current.pipelineTableName, "name")
            val allConfigs = pipelineNames.map(name => {
                PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, name)
            })

            // Scope-aware filter for keys whose only `pipeline:read` grant is
            // `pipeline:read:owner=self` — return ONLY pipelines this key
            // created. A key with an unscoped `pipeline:read` or `*:*` skips
            // the filter entirely. Server-side filtering keeps the result
            // shape consistent with single-resource reads and prevents
            // leaking metadata (names, destinations) about pipelines the
            // caller has no business seeing.
            val filteredConfigs =
                if (CapabilityCheck.hasOnlyOwnerSelfScope(request, "pipeline", "read")) {
                    val ownerLabel = ResolvedKeyAccess.keyLabel(request).orNull
                    allConfigs.filter(c => c != null && c.createdByKeyLabel != null && c.createdByKeyLabel == ownerLabel)
                } else {
                    allConfigs
                }

            val gson = new Gson
            val json = gson.toJson(filteredConfigs.asJava)
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
                         @RequestParam(name = "changeNote", required = false) changeNote: String,
                         @RequestBody config: PipelineConfig,
                         request: HttpServletRequest): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /pipeline with pipeline name: " + config.name)
            APIKeyValidator.validate(apiKey)

            val withDefaults = PipelineValidatorUtil.applyDefaults(config)
            PipelineValidatorUtil.validate(withDefaults)
            val modifiedConfig = PipelineValidatorUtil.modify(withDefaults)

            // Stamp the issuing key's label so `owner=self` capabilities can
            // match resources this key created. Null when no key is present
            // (auth disabled or public endpoint) — that resource will not
            // match `owner=self` for anyone, which is intended.
            val tagged = ResolvedKeyAccess.keyLabel(request) match {
                case Some(label) => modifiedConfig.copy(createdByKeyLabel = label)
                case None        => modifiedConfig
            }

            // Definition-edit write → mints a new immutable version snapshot.
            val existing = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, tagged.name)
            val note = if (changeNote != null && changeNote.nonEmpty) changeNote
                       else if (existing != null) "updated" else "created"
            PipelineConfigIO.writeVersioned(tagged, note, VersionActor.resolve(request))

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
                            @RequestParam(defaultValue = "true") deleteData: String,
                            @RequestParam(defaultValue = "true") deleteConfig: String,
                            request: HttpServletRequest): ResponseEntity[String] = {
        try {
            logger.info("API endpoint DELETE /pipeline with pipeline name: " + pipeline + ", deleteData: " + deleteData + ", deleteConfig: " + deleteConfig)
            APIKeyValidator.validate(apiKey)

            val config = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, pipeline)
            if(config == null)
                throw new DatrisException("Pipeline: " + pipeline + " is not configured in the NoSQL database")

            // Scope check: a key with `pipeline:delete:owner=self` may only
            // delete pipelines it created. Loaded resource provides the
            // `createdByKeyLabel` we compare against the caller's label.
            CapabilityCheck.assertOwnerScope(request, "pipeline", "delete", config.createdByKeyLabel)

            // Deleting the config without also deleting the data is disallowed.
            // Leaving orphaned rows/collections/tables behind with no pipeline to
            // own them creates hard-to-debug ghost state; any caller asking for
            // config-only gets data-delete folded in implicitly.
            val deleteConfigBool = deleteConfig.equalsIgnoreCase("true")
            val deleteDataBool = deleteData.equalsIgnoreCase("true") || deleteConfigBool

            if(deleteConfigBool && config.source.databaseAttributes != null)
                PipelinePullTableUtil.deleteEntryIfExists(config.name)

            // Clean up destination data
            if(deleteDataBool && config.destination != null) {
                cleanupDestinationData(config)
                // Wipe document-tap ledgers/staged files for any tap targeting this
                // pipeline. The ledger records "already-processed URIs"; leaving it
                // intact after the destination is emptied would cause the next tap
                // run to skip every doc and land nothing in the now-empty pipeline.
                cleanupDocumentTapLedgers(pipeline)
            }

            // Delete the json configuration
            if(deleteConfigBool) {
                NoSQLDbUtil.deleteItemJSON(DatrisEnvironment.current.pipelineTableName, "name", pipeline)
                // Hard-delete all definition-version snapshots for this pipeline
                // (pipelines pin no scripts, so nothing to GC in object storage).
                try {
                    EntityVersionIO.deleteAllForEntity(DatrisEnvironment.current.pipelineVersionTableName, pipeline)
                } catch {
                    case ex: Exception => logger.warn("Pipeline version cleanup failed for " + pipeline + ": " + ex.getMessage)
                }
            }

            new ResponseEntity[String](HttpStatus.OK)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    /**
     * Clear tap-ledger entries and MinIO-staged files for every document tap
     * targeting this pipeline. Called when the pipeline's destination data is
     * wiped so the next tap run re-ingests every document from source.
     * Swallows individual MinIO delete errors — the ledger row is authoritative,
     * so a missing staged file shouldn't block the rest of the cleanup.
     */
    private def cleanupDocumentTapLedgers(pipelineName: String): Unit = {
        try {
            val env = DatrisEnvironment.current
            val ledgerTable = env.tapLedgerTableName
            val bucket = env.environment + "-config"
            val taps = TapConfigIO.readAll(env.tapTableName)
            val affected = taps.filter(t =>
                t != null && "document" == t.tapType &&
                t.targetPipeline != null && t.targetPipeline == pipelineName
            )
            if (affected.isEmpty) return
            affected.foreach { tap =>
                val entries = TapDocumentLedgerIO.readByTap(ledgerTable, tap.name)
                entries.foreach { e =>
                    if (e.stagedPath != null && e.stagedPath.nonEmpty) {
                        try { ObjectStoreUtil.deleteBucketObject(bucket, e.stagedPath) }
                        catch { case ex: Exception => logger.warn("Failed to delete staged doc " + e.stagedPath + ": " + ex.getMessage) }
                    }
                }
                TapDocumentLedgerIO.deleteByTap(ledgerTable, tap.name)
                logger.info("Cleared document tap ledger for tap '" + tap.name + "' (" + entries.size + " entries) after pipeline data delete: " + pipelineName)
            }
        } catch {
            case e: Exception =>
                logger.warn("Failed to clean up document tap ledgers for pipeline '" + pipelineName + "': " + e.getMessage)
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
                val pgDbName = if (DatrisEnvironment.current.multiTenant) DatrisEnvironment.current.environment else dest.database.dbName
                val afterProtocol = secrets.jdbcUrl.replaceFirst("^jdbc:postgresql://", "")
                val jdbcUrl = if (afterProtocol.contains("/")) secrets.jdbcUrl else secrets.jdbcUrl + "/" + pgDbName
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
                    val dbName = if (DatrisEnvironment.current.multiTenant) DatrisEnvironment.current.environment
                        else if (dest.database.dbName != null) dest.database.dbName else "datris"
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
                val secretName = DatrisEnvironment.current.pgvectorSecretName
                val secret = SecretsUtil.getSecretMap(secretName)
                if (secret.isDefined) {
                    val secretMap = secret.get
                    val jdbcUrl = secretMap.get("jdbcUrl")
                    val username = secretMap.get("username")
                    val password = secretMap.get("password")
                    if (jdbcUrl != null) {
                        val pgvJdbcUrl = if (DatrisEnvironment.current.multiTenant) {
                            jdbcUrl.replaceFirst("/[^/]*$", "/" + DatrisEnvironment.current.environment)
                        } else jdbcUrl
                        Class.forName("org.postgresql.Driver")
                        val properties = new Properties()
                        properties.setProperty("user", username)
                        properties.setProperty("password", password)
                        val conn = DriverManager.getConnection(pgvJdbcUrl, properties)
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
                val secretName = DatrisEnvironment.current.qdrantSecretName
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
                val secretName = DatrisEnvironment.current.weaviateSecretName
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
                val secretName = DatrisEnvironment.current.milvusSecretName
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
                val secretName = DatrisEnvironment.current.chromaSecretName
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
