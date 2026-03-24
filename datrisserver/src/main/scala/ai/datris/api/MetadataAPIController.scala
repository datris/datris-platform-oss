package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.common.base.Throwables
import com.google.gson.Gson
import ai.datris.model.{DatrisEnvironment, DatrisException}
import ai.datris.util.{APIKeyValidator, SecretsRetrieverUtil, SecretsUtil}
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import java.sql.{Connection, DriverManager}
import java.util.Properties
import scala.collection.JavaConverters._

@RestController
@RequestMapping(Array("/api/v1"))
@CrossOrigin(origins = Array("*"), methods = Array(RequestMethod.GET, RequestMethod.OPTIONS))
class MetadataAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[MetadataAPIController])

    @GetMapping(path = Array("/metadata/postgres/databases"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getPostgresDatabases(@RequestHeader(name = "x-api-key", required = false) apiKey: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /metadata/postgres/databases called")
            APIKeyValidator.validate(apiKey)

            val results = queryPostgres("postgres",
                "SELECT datname FROM pg_database WHERE datistemplate = false AND datname NOT IN ('postgres') ORDER BY datname"
            )
            val databases = results.map(_.get("datname").toString)
            val gson = new Gson
            new ResponseEntity[String](gson.toJson(databases.asJava), HttpStatus.OK)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @GetMapping(path = Array("/metadata/postgres/schemas"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getPostgresSchemas(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                           @RequestParam(defaultValue = "datris") database: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /metadata/postgres/schemas called, database: " + database)
            APIKeyValidator.validate(apiKey)

            val results = queryPostgres(database,
                "SELECT schema_name FROM information_schema.schemata " +
                "WHERE schema_name NOT IN ('information_schema', 'pg_catalog', 'pg_toast') " +
                "ORDER BY schema_name"
            )
            val schemas = results.map(_.get("schema_name").toString)
            val gson = new Gson
            new ResponseEntity[String](gson.toJson(schemas.asJava), HttpStatus.OK)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @GetMapping(path = Array("/metadata/postgres/tables"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getPostgresTables(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                          @RequestParam(defaultValue = "datris") database: String,
                          @RequestParam(defaultValue = "public") schema: String,
                          @RequestParam(defaultValue = "false") vectorOnly: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /metadata/postgres/tables called, database: " + database + ", schema: " + schema + ", vectorOnly: " + vectorOnly)
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

            val results = queryPostgres(database, sql)
            val tables = results.map(_.get("table_name").toString)
            val gson = new Gson
            new ResponseEntity[String](gson.toJson(tables.asJava), HttpStatus.OK)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @GetMapping(path = Array("/metadata/postgres/columns"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getPostgresColumns(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                           @RequestParam(defaultValue = "datris") database: String,
                           @RequestParam(defaultValue = "public") schema: String,
                           @RequestParam table: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /metadata/postgres/columns called, database: " + database + ", schema: " + schema + ", table: " + table)
            APIKeyValidator.validate(apiKey)

            val results = queryPostgres(database,
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
        }
        catch {
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
                val env = DatrisEnvironment.values.environment
                val databases = client.listDatabaseNames().asScala
                    .filter(db => !Set("admin", "config", "local").contains(db))
                    .filter(db => db != env)
                    .toList.sorted
                val gson = new Gson
                new ResponseEntity[String](gson.toJson(databases.asJava), HttpStatus.OK)
            } finally {
                client.close()
            }
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @GetMapping(path = Array("/metadata/mongodb/collections"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getMongoCollections(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                            @RequestParam(required = false) database: String): ResponseEntity[String] = {
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
                // List collections from the specified database, or all non-system databases
                val env = DatrisEnvironment.values.environment
                val collections = if (database != null && database.nonEmpty) {
                    client.getDatabase(database).listCollectionNames().asScala
                        .filter(name => !name.startsWith(env + "-"))
                        .toList.sorted
                } else {
                    // List from all databases
                    val allDbs = client.listDatabaseNames().asScala
                        .filter(db => !Set("admin", "config", "local").contains(db))
                        .toList
                    allDbs.flatMap { dbName =>
                        client.getDatabase(dbName).listCollectionNames().asScala
                            .filter(name => !name.startsWith(env + "-"))
                            .map(col => dbName + "." + col)
                    }.sorted
                }
                val gson = new Gson
                new ResponseEntity[String](gson.toJson(collections.asJava), HttpStatus.OK)
            } finally {
                client.close()
            }
        }
        catch {
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

            val secretName = DatrisEnvironment.values.qdrantSecretName
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
                val collections = client.listCollectionsAsync().get(5, java.util.concurrent.TimeUnit.SECONDS)
                val names = collections.asScala.map(_.toString).toList.sorted
                val gson = new Gson
                new ResponseEntity[String](gson.toJson(names.asJava), HttpStatus.OK)
            } finally {
                client.close()
            }
        }
        catch {
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

            val secretName = DatrisEnvironment.values.weaviateSecretName
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
                    val names = if (classesArray != null)
                        classesArray.asScala.map(_.getAsJsonObject.get("class").getAsString).toList.sorted
                    else
                        List.empty[String]
                    val gson = new Gson
                    new ResponseEntity[String](gson.toJson(names.asJava), HttpStatus.OK)
                } finally {
                    response.close()
                }
            } finally {
                httpClient.close()
            }
        }
        catch {
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

            val secretName = DatrisEnvironment.values.milvusSecretName
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
                val names = resp.getCollectionNames.asScala.toList.sorted
                val gson = new Gson
                new ResponseEntity[String](gson.toJson(names.asJava), HttpStatus.OK)
            } finally {
                client.close()
            }
        }
        catch {
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

            val secretName = DatrisEnvironment.values.chromaSecretName
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
                    val names = json.asScala
                        .map(_.getAsJsonObject.get("name").getAsString)
                        .toList.sorted
                    val gson = new Gson
                    new ResponseEntity[String](gson.toJson(names.asJava), HttpStatus.OK)
                } finally {
                    response.close()
                }
            } finally {
                httpClient.close()
            }
        }
        catch {
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
