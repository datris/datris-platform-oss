package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.DatrisEnvironment
import org.slf4j.{Logger, LoggerFactory}

/** Resolves which structured destinations this deployment can actually load
 *  into, for baking into AI prompt text (the in-UI Assistant's system prompt).
 *
 *  INJECTION, NOT INSTRUCTION: never tell the model "check availability before
 *  offering a destination" — a conditional offer flickers, because at
 *  question-time the model hasn't checked yet and options silently vanish.
 *  Instead the harness resolves availability HERE and bakes the concrete
 *  destination list into the prompt text; the model unconditionally offers
 *  everything its prompt names — the prompt just contains the right set. Any
 *  literal EXAMPLE that enumerates destinations must be rendered from this
 *  same list: the model mimics an example's enumeration over the prose rule.
 *
 *  FAIL-OPEN: if availability cannot be determined (Vault scan fails, the
 *  whole computation throws, or every check comes back empty because the
 *  environment itself is broken), return the FULL five-destination set. A
 *  transient blip in the machinery that answers "what is installed?" must
 *  never hide destinations from the user. A clean probe result saying a
 *  self-hosted service is down/not-configured IS the availability signal
 *  (install-time selection can disable bundled services) and does exclude
 *  that destination.
 *
 *  Availability semantics match GET /destinations/available
 *  (HealthCheckAPIController): the self-hosted destinations (mongodb,
 *  postgres, objectstore) are live-probed — the compose stack seeds their
 *  Vault secrets even when a service is disabled, so "secret is present" is
 *  not a reliable signal; the external SaaS destinations (snowflake,
 *  databricks) have no local service to probe, so presence of complete
 *  credentials in some Platform secret is the signal.
 */
object DestinationAvailabilityUtil {
    private val logger: Logger = LoggerFactory.getLogger(DestinationAvailabilityUtil.getClass)

    /** Canonical order of the full structured-destination set; also the
     *  fail-open fallback. */
    val AllStructuredDestinations: Seq[String] = Seq("mongodb", "postgres", "objectstore", "snowflake", "databricks")

    // Probes hit Mongo/Postgres/MinIO plus a Vault scan, so a fresh compute
    // costs a few seconds. Prompt assembly runs per chat request — cache the
    // result briefly so only the first chat in a window pays for the probes.
    private val CacheTtlMillis = 60000L
    @volatile private var cached: (Long, Seq[String]) = (0L, null)

    /** Structured destinations available in this deployment, in canonical
     *  order. Never empty and never throws — fails open to the full set. */
    def availableStructuredDestinations(): Seq[String] = {
        val snapshot = cached
        if (snapshot._2 != null && (System.currentTimeMillis() - snapshot._1) < CacheTtlMillis) return snapshot._2
        val names = compute()
        cached = (System.currentTimeMillis(), names)
        names
    }

    private def compute(): Seq[String] = {
        try {
            val builder = Seq.newBuilder[String]
            if (mongoUp()) builder += "mongodb"
            if (postgresUp()) builder += "postgres"
            if (minioUp()) builder += "objectstore"

            val (snowflake, databricks) = externalSaasAvailability()
            if (snowflake) builder += "snowflake"
            if (databricks) builder += "databricks"

            val names = builder.result()
            if (names.isEmpty) {
                // Nothing at all "available" means the environment machinery is
                // broken (e.g. Vault unreachable), not a deployment with zero
                // destinations — fail open rather than render an empty offer.
                logger.warn("Destination availability came back empty — failing open to the full set")
                AllStructuredDestinations
            } else names
        } catch {
            case e: Exception =>
                logger.warn("Destination availability could not be determined — failing open to the full set: " + e.getMessage)
                AllStructuredDestinations
        }
    }

    /** Live-probe MongoDB, mirroring HealthCheckAPIController.checkMongoDB. */
    private def mongoUp(): Boolean = {
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
                true
            } finally {
                client.close()
            }
        } catch {
            case _: Exception => false
        }
    }

    /** Live-probe Postgres via the same reachability test the health endpoint
     *  and create-pipeline pre-validation use. */
    private def postgresUp(): Boolean = {
        try {
            val secretName = DatrisEnvironment.current.postgresSecretName
            if (secretName == null || secretName.isEmpty) return false
            PostgresQueryUtil.probeError().isEmpty
        } catch {
            case _: Exception => false
        }
    }

    /** Live-probe MinIO, mirroring HealthCheckAPIController.checkMinIO but
     *  with short client timeouts so prompt assembly can't hang on it. */
    private def minioUp(): Boolean = {
        try {
            val config = DatrisEnvironment.current.minIOConfig
            if (config == null || config.endpoint == null) return false
            val client = io.minio.MinioClient.builder()
                .endpoint(config.endpoint)
                .credentials(config.accessKey, config.secretKey)
                .build()
            client.setTimeout(2000, 2000, 2000)
            client.listBuckets()
            true
        } catch {
            case _: Exception => false
        }
    }

    /** (snowflakeAvailable, databricksAvailable) via the Platform-secret
     *  credential scan. The scan failing is an "availability unknown" error,
     *  not a "nothing configured" answer — fail open for both. */
    private def externalSaasAvailability(): (Boolean, Boolean) = {
        try {
            val secrets = SecretsRetrieverUtil.platformSecrets()
            (secrets.exists { case (_, fields) => CredentialResolver.hasSnowflakeCredentials(fields) },
                secrets.exists { case (_, fields) => CredentialResolver.hasDatabricksCredentials(fields) })
        } catch {
            case e: Exception =>
                logger.warn("Platform-secret scan failed — failing open for snowflake/databricks: " + e.getMessage)
                (true, true)
        }
    }
}
