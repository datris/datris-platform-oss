package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.Gson
import ai.datris.model.{DatrisEnvironment, DatrisException}
import ai.datris.util.{APIKeyValidator, PipelineConfigIO, SecretsRetrieverUtil, SecretsUtil}
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import java.sql.{Connection, DriverManager}
import java.util.Properties
import scala.collection.JavaConverters._

@RestController
@RequestMapping(Array("/api/v1"))
class MetadataAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[MetadataAPIController])

    /** In multi-tenant mode, return the tenant's isolated postgres database name and ensure it exists. */
    private def tenantPostgresDb(): String = {
        val env = DatrisEnvironment.current.environment
        // Ensure tenant database exists
        try {
            val secrets = SecretsRetrieverUtil.postgresSecrets()
            val properties = new Properties()
            properties.setProperty("user", secrets.username)
            properties.setProperty("password", secrets.password)
            val conn = DriverManager.getConnection(secrets.jdbcUrl + "/postgres", properties)
            try {
                val rs = conn.createStatement().executeQuery(
                    "SELECT 1 FROM pg_database WHERE datname = '" + env.replace("'", "''") + "'"
                )
                if (!rs.next()) {
                    conn.createStatement().execute("CREATE DATABASE \"" + env.replace("\"", "\"\"") + "\"")
                    logger.info("Created tenant database: " + env)
                }
                rs.close()
            } finally { conn.close() }
        } catch {
            case e: Exception => logger.warn("Could not ensure tenant database: " + e.getMessage)
        }
        env
    }

    private def getTenantMongoCollections(): Set[String] = {
        try {
            val pipelines = PipelineConfigIO.readAll(DatrisEnvironment.current.pipelineTableName)
            pipelines.flatMap { p =>
                if (p.destination == null) Nil
                else Option(p.destination.database).filter(_.useMongoDB).map(_.table).toList
            }.filter(_ != null).toSet
        } catch {
            case e: Exception =>
                logger.warn("Failed to read pipeline configs for tenant Mongo collection list; returning empty set", e)
                Set.empty[String]
        }
    }

    private def getTenantVectorCollections(destType: String): Set[String] = {
        try {
            val pipelines = PipelineConfigIO.readAll(DatrisEnvironment.current.pipelineTableName)
            pipelines.flatMap { p =>
                if (p.destination == null) Nil
                else destType match {
                    case "qdrant" => Option(p.destination.qdrant).map(_.collectionName).toList
                    case "weaviate" => Option(p.destination.weaviate).map(_.className).toList
                    case "milvus" => Option(p.destination.milvus).map(_.collectionName).toList
                    case "chroma" => Option(p.destination.chroma).map(_.collectionName).toList
                    case _ => Nil
                }
            }.filter(_ != null).toSet
        } catch {
            case e: Exception =>
                logger.warn("Failed to read pipeline configs for tenant " + destType + " collection list; returning empty set", e)
                Set.empty[String]
        }
    }

    @GetMapping(path = Array("/metadata/postgres/databases"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getPostgresDatabases(@RequestHeader(name = "x-api-key", required = false) apiKey: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /metadata/postgres/databases called")
            APIKeyValidator.validate(apiKey)

            val databases = if (DatrisEnvironment.current.multiTenant) {
                // Multi-tenant: only show the tenant's isolated database
                List(tenantPostgresDb())
            } else {
                val results =
                    queryPostgres("postgres", "SELECT datname FROM pg_database WHERE datistemplate = false AND datname NOT IN ('postgres') ORDER BY datname")
                results.map(_.get("datname").toString)
            }
            val gson = new Gson
            new ResponseEntity[String](gson.toJson(databases.asJava), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @GetMapping(path = Array("/metadata/postgres/schemas"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getPostgresSchemas(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam(defaultValue = "") database: String
    ): ResponseEntity[String] = {
        try {
            val dbName = if (DatrisEnvironment.current.multiTenant) tenantPostgresDb()
            else if (database != null && database.nonEmpty) database
            else DatrisEnvironment.current.postgresDatabase
            logger.info("API endpoint GET /metadata/postgres/schemas called, database: " + dbName)
            APIKeyValidator.validate(apiKey)

            val results = queryPostgres(
                dbName,
                "SELECT schema_name FROM information_schema.schemata " +
                    "WHERE schema_name NOT IN ('information_schema', 'pg_catalog', 'pg_toast') " +
                    "ORDER BY schema_name"
            )
            val schemas = results.map(_.get("schema_name").toString)
            val gson = new Gson
            new ResponseEntity[String](gson.toJson(schemas.asJava), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @GetMapping(path = Array("/metadata/postgres/tables"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getPostgresTables(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam(defaultValue = "") database: String,
        @RequestParam(defaultValue = "public") schema: String,
        @RequestParam(defaultValue = "false") vectorOnly: String
    ): ResponseEntity[String] = {
        try {
            val dbName = if (DatrisEnvironment.current.multiTenant) tenantPostgresDb()
            else if (database != null && database.nonEmpty) database
            else DatrisEnvironment.current.postgresDatabase
            logger.info("API endpoint GET /metadata/postgres/tables called, database: " + dbName + ", schema: " + schema + ", vectorOnly: " + vectorOnly)
            APIKeyValidator.validate(apiKey)

            val sql = if (vectorOnly.equalsIgnoreCase("true")) {
                // Only return tables that have an 'embedding' column (pgvector tables)
                "SELECT DISTINCT t.table_name FROM information_schema.tables t " +
                    "JOIN information_schema.columns c ON t.table_name = c.table_name AND t.table_schema = c.table_schema " +
                    "WHERE t.table_schema = '" + schema.replace("'", "''") + "' " +
                    "AND t.table_type = 'BASE TABLE' " +
                    "AND c.column_name = 'embedding' " +
                    "ORDER BY t.table_name"
            } else {
                // Exclude tables that have an 'embedding' column (pgvector tables)
                "SELECT table_name FROM information_schema.tables " +
                    "WHERE table_schema = '" + schema.replace("'", "''") + "' " +
                    "AND table_type = 'BASE TABLE' " +
                    "AND table_name NOT IN (" +
                    "SELECT DISTINCT table_name FROM information_schema.columns " +
                    "WHERE table_schema = '" + schema.replace("'", "''") + "' " +
                    "AND column_name = 'embedding'" +
                    ") " +
                    "ORDER BY table_name"
            }

            val results = queryPostgres(dbName, sql)
            val tables = results.map(_.get("table_name").toString)
            val gson = new Gson
            new ResponseEntity[String](gson.toJson(tables.asJava), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @GetMapping(path = Array("/metadata/postgres/columns"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getPostgresColumns(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam(defaultValue = "") database: String,
        @RequestParam(defaultValue = "public") schema: String,
        @RequestParam table: String
    ): ResponseEntity[String] = {
        try {
            val dbName = if (DatrisEnvironment.current.multiTenant) tenantPostgresDb()
            else if (database != null && database.nonEmpty) database
            else DatrisEnvironment.current.postgresDatabase
            logger.info("API endpoint GET /metadata/postgres/columns called, database: " + dbName + ", schema: " + schema + ", table: " + table)
            APIKeyValidator.validate(apiKey)

            val results = queryPostgres(
                dbName,
                "SELECT column_name, data_type FROM information_schema.columns " +
                    "WHERE table_schema = '" + schema.replace("'", "''") + "' " +
                    "AND table_name = '" + table.replace("'", "''") + "' " +
                    "ORDER BY ordinal_position"
            )
            val columns = results.map { row =>
                val col = new java.util.LinkedHashMap[String, String]()
                col.put("name", row.get("column_name").toString)
                col.put("type", row.get("data_type").toString)
                col
            }
            val gson = new Gson
            new ResponseEntity[String](gson.toJson(columns.asJava), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @GetMapping(path = Array("/metadata/mongodb/databases"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getMongoDatabases(@RequestHeader(name = "x-api-key", required = false) apiKey: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /metadata/mongodb/databases called")
            APIKeyValidator.validate(apiKey)

            val secrets = SecretsRetrieverUtil.mongoDbSecrets()
            val connString = new com.mongodb.ConnectionString(secrets.connectionString)
            val settings = com.mongodb.MongoClientSettings.builder()
                .applyConnectionString(connString)
                .build()
            val client = com.mongodb.client.MongoClients.create(settings)

            try {
                val env = DatrisEnvironment.current.environment
                val databases = if (DatrisEnvironment.current.multiTenant) {
                    // Multi-tenant: only show the tenant's database
                    List(env)
                } else {
                    client.listDatabaseNames().asScala
                        .filter(db => !Set("admin", "config", "local").contains(db))
                        .filter(db => db != env)
                        .toList.sorted
                }
                val gson = new Gson
                new ResponseEntity[String](gson.toJson(databases.asJava), HttpStatus.OK)
            } finally {
                client.close()
            }
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @GetMapping(path = Array("/metadata/mongodb/collections"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getMongoCollections(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam(required = false) database: String
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /metadata/mongodb/collections called, database: " + database)
            APIKeyValidator.validate(apiKey)

            val secrets = SecretsRetrieverUtil.mongoDbSecrets()
            val connString = new com.mongodb.ConnectionString(secrets.connectionString)
            val settings = com.mongodb.MongoClientSettings.builder()
                .applyConnectionString(connString)
                .build()
            val client = com.mongodb.client.MongoClients.create(settings)

            try {
                val env = DatrisEnvironment.current.environment
                val dbName = if (DatrisEnvironment.current.multiTenant) {
                    // Multi-tenant: always use the tenant's database
                    env
                } else if (database != null && database.nonEmpty) {
                    database
                } else {
                    env
                }

                val collections = if (DatrisEnvironment.current.multiTenant) {
                    // Multi-tenant: only show collections that belong to tenant's pipelines
                    val tenantCollections = getTenantMongoCollections()
                    client.getDatabase(dbName).listCollectionNames().asScala
                        .filter(tenantCollections.contains)
                        .toList.sorted
                } else if (database != null && database.nonEmpty) {
                    client.getDatabase(database).listCollectionNames().asScala
                        .filter(name => !name.startsWith(env + "-"))
                        .toList.sorted
                } else {
                    // List from all databases
                    val allDbs = client.listDatabaseNames().asScala
                        .filter(db => !Set("admin", "config", "local").contains(db))
                        .toList
                    allDbs.flatMap { db =>
                        client.getDatabase(db).listCollectionNames().asScala
                            .filter(name => !name.startsWith(env + "-"))
                            .map(col => db + "." + col)
                    }.sorted
                }
                val gson = new Gson
                new ResponseEntity[String](gson.toJson(collections.asJava), HttpStatus.OK)
            } finally {
                client.close()
            }
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    // --- Vector Store Metadata ---

    @GetMapping(path = Array("/metadata/qdrant/collections"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getQdrantCollections(@RequestHeader(name = "x-api-key", required = false) apiKey: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /metadata/qdrant/collections called")
            APIKeyValidator.validate(apiKey)

            val secretName = DatrisEnvironment.current.qdrantSecretName
            if (secretName == null || secretName.isEmpty)
                return new ResponseEntity[String]("[]", HttpStatus.OK)

            val secret = SecretsUtil.getSecretMap(secretName)
            if (secret.isEmpty)
                return new ResponseEntity[String]("[]", HttpStatus.OK)

            val secretMap = secret.get
            val host = Option(secretMap.get("host")).getOrElse("")
            if (host.isEmpty)
                return new ResponseEntity[String]("[]", HttpStatus.OK)

            val port = Option(secretMap.get("port")).map(_.toInt).getOrElse(6334)
            val qdrantApiKey = Option(secretMap.get("apiKey")).filter(_.nonEmpty)
            val builder = io.qdrant.client.QdrantGrpcClient.newBuilder(host, port, false)
            qdrantApiKey.foreach(k => builder.withApiKey(k))
            val client = new io.qdrant.client.QdrantClient(builder.build())

            try {
                val allCollections = client.listCollectionsAsync().get(5, java.util.concurrent.TimeUnit.SECONDS)
                val names = if (DatrisEnvironment.current.multiTenant) {
                    val tenantNames = getTenantVectorCollections("qdrant")
                    allCollections.asScala.map(_.toString).filter(tenantNames.contains).toList.sorted
                } else allCollections.asScala.map(_.toString).toList.sorted
                val gson = new Gson
                new ResponseEntity[String](gson.toJson(names.asJava), HttpStatus.OK)
            } finally {
                client.close()
            }
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @GetMapping(path = Array("/metadata/weaviate/classes"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getWeaviateClasses(@RequestHeader(name = "x-api-key", required = false) apiKey: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /metadata/weaviate/classes called")
            APIKeyValidator.validate(apiKey)

            val secretName = DatrisEnvironment.current.weaviateSecretName
            if (secretName == null || secretName.isEmpty)
                return new ResponseEntity[String]("[]", HttpStatus.OK)

            val secret = SecretsUtil.getSecretMap(secretName)
            if (secret.isEmpty)
                return new ResponseEntity[String]("[]", HttpStatus.OK)

            val secretMap = secret.get
            val host = Option(secretMap.get("host")).getOrElse("")
            if (host.isEmpty)
                return new ResponseEntity[String]("[]", HttpStatus.OK)

            val port = Option(secretMap.get("port")).getOrElse("8079")
            val scheme = Option(secretMap.get("scheme")).getOrElse("http")
            val url = scheme + "://" + host + ":" + port + "/v1/schema"

            val httpClient = org.apache.http.impl.client.HttpClients.createDefault()
            try {
                val get = new org.apache.http.client.methods.HttpGet(url)
                val weaviateApiKey = Option(secretMap.get("apiKey")).filter(_.nonEmpty)
                weaviateApiKey.foreach(k => get.setHeader("Authorization", "Bearer " + k))
                val response = httpClient.execute(get)
                try {
                    val statusCode = response.getStatusLine.getStatusCode
                    val body = org.apache.http.util.EntityUtils.toString(response.getEntity)
                    if (statusCode != 200)
                        throw new DatrisException("Weaviate schema request failed (HTTP " + statusCode + ")")

                    val json = com.google.gson.JsonParser.parseString(body).getAsJsonObject
                    val classesArray = json.getAsJsonArray("classes")
                    val allNames = if (classesArray != null)
                        classesArray.asScala.map(_.getAsJsonObject.get("class").getAsString).toList.sorted
                    else
                        List.empty[String]
                    val names = if (DatrisEnvironment.current.multiTenant) {
                        val tenantNames = getTenantVectorCollections("weaviate")
                        allNames.filter(tenantNames.contains)
                    } else allNames
                    val gson = new Gson
                    new ResponseEntity[String](gson.toJson(names.asJava), HttpStatus.OK)
                } finally {
                    response.close()
                }
            } finally {
                httpClient.close()
            }
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @GetMapping(path = Array("/metadata/milvus/collections"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getMilvusCollections(@RequestHeader(name = "x-api-key", required = false) apiKey: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /metadata/milvus/collections called")
            APIKeyValidator.validate(apiKey)

            val secretName = DatrisEnvironment.current.milvusSecretName
            if (secretName == null || secretName.isEmpty)
                return new ResponseEntity[String]("[]", HttpStatus.OK)

            val secret = SecretsUtil.getSecretMap(secretName)
            if (secret.isEmpty)
                return new ResponseEntity[String]("[]", HttpStatus.OK)

            val secretMap = secret.get
            val host = Option(secretMap.get("host")).getOrElse("")
            if (host.isEmpty)
                return new ResponseEntity[String]("[]", HttpStatus.OK)

            val port = Option(secretMap.get("port")).getOrElse("19530")
            val milvusApiKey = Option(secretMap.get("apiKey")).filter(_.nonEmpty)
            val connectBuilder = io.milvus.v2.client.ConnectConfig.builder()
                .uri("http://" + host + ":" + port)
            milvusApiKey.foreach(k => connectBuilder.token(k))
            val client = new io.milvus.v2.client.MilvusClientV2(connectBuilder.build())

            try {
                val resp = client.listCollections()
                val allNames = resp.getCollectionNames.asScala.toList.sorted
                val names = if (DatrisEnvironment.current.multiTenant) {
                    val tenantNames = getTenantVectorCollections("milvus")
                    allNames.filter(tenantNames.contains)
                } else allNames
                val gson = new Gson
                new ResponseEntity[String](gson.toJson(names.asJava), HttpStatus.OK)
            } finally {
                client.close()
            }
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @GetMapping(path = Array("/metadata/chroma/collections"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getChromaCollections(@RequestHeader(name = "x-api-key", required = false) apiKey: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /metadata/chroma/collections called")
            APIKeyValidator.validate(apiKey)

            val secretName = DatrisEnvironment.current.chromaSecretName
            if (secretName == null || secretName.isEmpty)
                return new ResponseEntity[String]("[]", HttpStatus.OK)

            val secret = SecretsUtil.getSecretMap(secretName)
            if (secret.isEmpty)
                return new ResponseEntity[String]("[]", HttpStatus.OK)

            val secretMap = secret.get
            val host = Option(secretMap.get("host")).getOrElse("")
            if (host.isEmpty)
                return new ResponseEntity[String]("[]", HttpStatus.OK)

            val port = Option(secretMap.get("port")).getOrElse("8000")
            val baseUrl = "http://" + host + ":" + port
            val collectionsUrl = baseUrl + "/api/v2/tenants/default_tenant/databases/default_database/collections"

            val httpClient = org.apache.http.impl.client.HttpClients.createDefault()
            try {
                val get = new org.apache.http.client.methods.HttpGet(collectionsUrl)
                val response = httpClient.execute(get)
                try {
                    val statusCode = response.getStatusLine.getStatusCode
                    val body = org.apache.http.util.EntityUtils.toString(response.getEntity)
                    if (statusCode != 200)
                        throw new DatrisException("Chroma list collections failed (HTTP " + statusCode + ")")

                    val json = com.google.gson.JsonParser.parseString(body).getAsJsonArray
                    val allNames = json.asScala
                        .map(_.getAsJsonObject.get("name").getAsString)
                        .toList.sorted
                    val names = if (DatrisEnvironment.current.multiTenant) {
                        val tenantNames = getTenantVectorCollections("chroma")
                        allNames.filter(tenantNames.contains)
                    } else allNames
                    val gson = new Gson
                    new ResponseEntity[String](gson.toJson(names.asJava), HttpStatus.OK)
                } finally {
                    response.close()
                }
            } finally {
                httpClient.close()
            }
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    private def queryPostgres(database: String, sql: String): List[java.util.Map[String, Any]] = {
        val secrets = SecretsRetrieverUtil.postgresSecrets()
        Class.forName("org.postgresql.Driver")

        val properties = new Properties()
        properties.setProperty("user", secrets.username)
        properties.setProperty("password", secrets.password)

        val afterProtocol = secrets.jdbcUrl.replaceFirst("^jdbc:postgresql://", "")
        val hasDatabase = afterProtocol.contains("/")
        val jdbcUrl = if (hasDatabase) secrets.jdbcUrl else secrets.jdbcUrl + "/" + database

        var conn: Connection = null
        try {
            conn = DriverManager.getConnection(jdbcUrl, properties)
            conn.setReadOnly(true)

            val stmt = conn.createStatement()
            val rs = stmt.executeQuery(sql)
            val metaData = rs.getMetaData
            val columnCount = metaData.getColumnCount

            val results = new java.util.ArrayList[java.util.Map[String, Any]]()
            while (rs.next()) {
                val row = new java.util.LinkedHashMap[String, Any]()
                for (i <- 1 to columnCount) {
                    row.put(metaData.getColumnLabel(i), rs.getObject(i))
                }
                results.add(row)
            }
            rs.close()
            stmt.close()
            results.asScala.toList
        } finally {
            if (conn != null) conn.close()
        }
    }
}
