package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.common.base.Throwables
import com.google.gson.Gson
import ai.datris.model.DatrisEnvironment
import ai.datris.util.{APIKeyValidator, SecretsRetrieverUtil, SecretsUtil}
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import java.sql.{Connection, DriverManager}
import java.util.Properties
import scala.collection.JavaConverters._

@RestController
@RequestMapping(Array("/api/v1"))
class HealthCheckAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[HealthCheckAPIController])

    /**
     * Vector stores that are live and reachable. Drives the document-tap
     * pipeline wizard's store picker. We must actually probe the service here
     * — the dev docker-compose seeds placeholder Vault secrets for every
     * store even though only pgvector is running, so "secret is present" is
     * not a reliable signal of availability. Reuses the same checkVectorDB
     * logic as /health/services; returns only stores whose status is "up".
     */
    @GetMapping(path = Array("/vector-stores/available"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def listAvailableVectorStores(@RequestHeader(name = "x-api-key", required = false) apiKey: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /vector-stores/available called")
            APIKeyValidator.validate(apiKey)

            val env = DatrisEnvironment.current
            val candidates = Seq(
                ("qdrant",   env.qdrantSecretName),
                ("weaviate", env.weaviateSecretName),
                ("pgvector", env.pgvectorSecretName),
                ("milvus",   env.milvusSecretName),
                ("chroma",   env.chromaSecretName)
            )
            val available = new java.util.ArrayList[String]()
            candidates.foreach { case (name, secretName) =>
                val status = checkVectorDB(name, secretName)
                if ("up" == status.get("status")) available.add(name)
            }

            val gson = new Gson
            new ResponseEntity[String](gson.toJson(available), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @GetMapping(path = Array("/health/services"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def checkServiceHealth(@RequestHeader(name = "x-api-key", required = false) apiKey: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /health/services called")
            // Public infrastructure endpoint — health probes, container
            // orchestrators, and the UI's status indicators all need to
            // reach this without auth. The `apiKey` parameter is kept for
            // forward compat but ignored.

            val env = DatrisEnvironment.current
            val results = new java.util.LinkedHashMap[String, Any]()

            // Core infrastructure — always checked
            results.put("postgres", checkPostgres(env.postgresSecretName))
            results.put("mongodb", checkMongoDB())
            results.put("minio", checkMinIO())
            results.put("activemq", checkActiveMQ())
            results.put("kafka", checkKafka(env.kafkaProducerSecretName))

            // Vector databases — conditionally checked
            results.put("qdrant", checkVectorDB("qdrant", env.qdrantSecretName))
            results.put("weaviate", checkVectorDB("weaviate", env.weaviateSecretName))
            results.put("milvus", checkVectorDB("milvus", env.milvusSecretName))
            results.put("chroma", checkVectorDB("chroma", env.chromaSecretName))
            results.put("pgvector", checkVectorDB("pgvector", env.pgvectorSecretName))

            val gson = new Gson
            new ResponseEntity[String](gson.toJson(results), HttpStatus.OK)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    private def statusUp(message: String = "Connected"): java.util.Map[String, String] = {
        val m = new java.util.LinkedHashMap[String, String]()
        m.put("status", "up")
        m.put("message", message)
        m
    }

    private def statusDown(message: String): java.util.Map[String, String] = {
        val m = new java.util.LinkedHashMap[String, String]()
        m.put("status", "down")
        m.put("message", message)
        m
    }

    private def statusNotConfigured(): java.util.Map[String, String] = {
        val m = new java.util.LinkedHashMap[String, String]()
        m.put("status", "not_configured")
        m
    }

    private def checkPostgres(secretName: String): java.util.Map[String, String] = {
        try {
            if (secretName == null || secretName.isEmpty) return statusNotConfigured()
            val secrets = SecretsRetrieverUtil.postgresSecrets()
            Class.forName("org.postgresql.Driver")

            val properties = new Properties()
            properties.setProperty("user", secrets.username)
            properties.setProperty("password", secrets.password)
            properties.setProperty("loginTimeout", "2")

            val afterProtocol = secrets.jdbcUrl.replaceFirst("^jdbc:postgresql://", "")
            val jdbcUrl = if (afterProtocol.contains("/")) secrets.jdbcUrl else secrets.jdbcUrl + "/datris"

            var conn: Connection = null
            try {
                conn = DriverManager.getConnection(jdbcUrl, properties)
                conn.setReadOnly(true)
                val stmt = conn.createStatement()
                stmt.setQueryTimeout(5)
                val rs = stmt.executeQuery("SELECT 1")
                rs.close()
                stmt.close()
                statusUp()
            } finally {
                if (conn != null) conn.close()
            }
        } catch {
            case e: Exception => statusDown(e.getMessage)
        }
    }

    private def checkMongoDB(): java.util.Map[String, String] = {
        try {
            val secrets = SecretsRetrieverUtil.mongoDbSecrets()
            val connString = new com.mongodb.ConnectionString(secrets.connectionString)
            val settings = com.mongodb.MongoClientSettings.builder()
                .applyConnectionString(connString)
                .applyToClusterSettings(b => b.serverSelectionTimeout(2, java.util.concurrent.TimeUnit.SECONDS))
                .build()
            val client = com.mongodb.client.MongoClients.create(settings)
            try {
                client.listDatabaseNames().first()
                statusUp()
            } finally {
                client.close()
            }
        } catch {
            case e: Exception => statusDown(e.getMessage)
        }
    }

    private def checkMinIO(): java.util.Map[String, String] = {
        try {
            val config = DatrisEnvironment.current.minIOConfig
            if (config == null || config.endpoint == null) return statusNotConfigured()
            val client = io.minio.MinioClient.builder()
                .endpoint(config.endpoint)
                .credentials(config.accessKey, config.secretKey)
                .build()
            client.listBuckets()
            statusUp()
        } catch {
            case e: Exception => statusDown(e.getMessage)
        }
    }

    private def checkActiveMQ(): java.util.Map[String, String] = {
        try {
            val config = DatrisEnvironment.current.activeMQConfig
            if (config == null || config.server == null) return statusNotConfigured()
            val factory = new org.apache.activemq.ActiveMQConnectionFactory()
            factory.setBrokerURL(config.server)
            factory.setUserName(config.username)
            factory.setPassword(config.password)
            factory.setSendTimeout(2000)
            val connection = factory.createConnection()
            try {
                connection.start()
                statusUp()
            } finally {
                connection.close()
            }
        } catch {
            case e: Exception => statusDown(e.getMessage)
        }
    }

    private def checkKafka(secretName: String): java.util.Map[String, String] = {
        try {
            if (secretName == null || secretName.isEmpty) return statusNotConfigured()
            val secret = SecretsUtil.getSecretMap(secretName)
            if (secret.isEmpty) return statusNotConfigured()
            val secretMap = secret.get
            val bootstrapServers = secretMap.get("bootstrapServers")
            if (bootstrapServers == null || bootstrapServers.isEmpty) return statusNotConfigured()

            val props = new Properties()
            props.put("bootstrap.servers", bootstrapServers)
            props.put("request.timeout.ms", "2000")
            props.put("default.api.timeout.ms", "2000")
            props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
            props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")

            val username = Option(secretMap.get("username")).filter(_.nonEmpty)
            val password = Option(secretMap.get("password")).filter(_.nonEmpty)
            if (username.isDefined && password.isDefined) {
                props.put("security.protocol", "SASL_PLAINTEXT")
                props.put("sasl.mechanism", "PLAIN")
                props.put("sasl.jaas.config",
                    s"""org.apache.kafka.common.security.plain.PlainLoginModule required username="${username.get}" password="${password.get}";""")
            }

            val producer = new org.apache.kafka.clients.producer.KafkaProducer[String, String](props)
            try {
                producer.partitionsFor("__consumer_offsets")
                statusUp()
            } finally {
                producer.close(java.time.Duration.ofSeconds(1))
            }
        } catch {
            case e: Exception => statusDown(e.getMessage)
        }
    }

    private def checkVectorDB(name: String, secretName: String): java.util.Map[String, String] = {
        try {
            if (secretName == null || secretName.isEmpty) return statusNotConfigured()
            val secret = SecretsUtil.getSecretMap(secretName)
            if (secret.isEmpty) return statusNotConfigured()
            val secretMap = secret.get

            // pgvector uses jdbcUrl instead of host
            if (name == "pgvector") {
                val jdbcUrl = Option(secretMap.get("jdbcUrl")).getOrElse("")
                val username = Option(secretMap.get("username")).getOrElse("")
                val password = Option(secretMap.get("password")).getOrElse("")
                if (jdbcUrl.isEmpty) return statusNotConfigured()

                Class.forName("org.postgresql.Driver")
                val properties = new Properties()
                properties.setProperty("user", username)
                properties.setProperty("password", password)
                properties.setProperty("loginTimeout", "2")

                var conn: Connection = null
                try {
                    conn = DriverManager.getConnection(jdbcUrl, properties)
                    conn.setReadOnly(true)
                    val stmt = conn.createStatement()
                    stmt.setQueryTimeout(5)
                    val rs = stmt.executeQuery("SELECT 1")
                    rs.close()
                    stmt.close()
                    return statusUp()
                } finally {
                    if (conn != null) conn.close()
                }
            }

            val host = Option(secretMap.get("host")).getOrElse("")
            if (host.isEmpty) return statusNotConfigured()

            name match {
                case "qdrant" =>
                    val grpcPort = Option(secretMap.get("port")).map(_.toInt).getOrElse(6334)
                    val restPort = grpcPort - 1 // Qdrant REST port is typically gRPC port - 1 (6333)
                    val url = "http://" + host + ":" + restPort + "/collections"
                    val connection = new java.net.URL(url)
                        .openConnection().asInstanceOf[java.net.HttpURLConnection]
                    connection.setConnectTimeout(2000)
                    connection.setReadTimeout(2000)
                    val apiKey = Option(secretMap.get("apiKey")).filter(_.nonEmpty)
                    apiKey.foreach(k => connection.setRequestProperty("api-key", k))
                    try {
                        if (connection.getResponseCode == 200) statusUp()
                        else statusDown("HTTP " + connection.getResponseCode)
                    } finally {
                        connection.disconnect()
                    }

                case "weaviate" =>
                    val port = Option(secretMap.get("port")).getOrElse("8079")
                    val scheme = Option(secretMap.get("scheme")).getOrElse("http")
                    val url = scheme + "://" + host + ":" + port
                    val connection = new java.net.URL(url + "/v1/.well-known/ready")
                        .openConnection().asInstanceOf[java.net.HttpURLConnection]
                    connection.setConnectTimeout(2000)
                    connection.setReadTimeout(2000)
                    try {
                        if (connection.getResponseCode == 200) statusUp()
                        else statusDown("HTTP " + connection.getResponseCode)
                    } finally {
                        connection.disconnect()
                    }

                case "milvus" =>
                    val port = Option(secretMap.get("port")).getOrElse("19530")
                    val url = "http://" + host + ":" + port + "/v2/vectordb/collections/list"
                    val connection = new java.net.URL(url)
                        .openConnection().asInstanceOf[java.net.HttpURLConnection]
                    connection.setConnectTimeout(2000)
                    connection.setReadTimeout(2000)
                    connection.setRequestMethod("POST")
                    connection.setDoOutput(true)
                    connection.setRequestProperty("Content-Type", "application/json")
                    val apiKey = Option(secretMap.get("apiKey")).filter(_.nonEmpty)
                    apiKey.foreach(k => connection.setRequestProperty("Authorization", "Bearer " + k))
                    try {
                        val os = connection.getOutputStream
                        os.write("{}".getBytes)
                        os.close()
                        if (connection.getResponseCode == 200) statusUp()
                        else statusDown("HTTP " + connection.getResponseCode)
                    } finally {
                        connection.disconnect()
                    }

                case "chroma" =>
                    val port = Option(secretMap.get("port")).getOrElse("8000")
                    val url = "http://" + host + ":" + port + "/api/v1/heartbeat"
                    val connection = new java.net.URL(url)
                        .openConnection().asInstanceOf[java.net.HttpURLConnection]
                    connection.setConnectTimeout(2000)
                    connection.setReadTimeout(2000)
                    try {
                        if (connection.getResponseCode == 200) statusUp()
                        else statusDown("HTTP " + connection.getResponseCode)
                    } finally {
                        connection.disconnect()
                    }

                case _ => statusNotConfigured()
            }
        } catch {
            case e: Exception => statusDown(e.getMessage)
        }
    }
}
