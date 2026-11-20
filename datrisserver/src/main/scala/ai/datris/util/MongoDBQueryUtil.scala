package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, DatrisException}
import com.mongodb.client.MongoClient
import org.bson.Document
import org.bson.json.{JsonMode, JsonWriterSettings}
import org.slf4j.{Logger, LoggerFactory}

import java.util.Locale
import scala.collection.JavaConverters._

object MongoDBQueryUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    // No MAX cap: callers (tap scripts) may need the full collection. Callers that
    // want a preview-sized result pass an explicit positive limit.
    private val DEFAULT_LIMIT = 20
    private val jsonSettings = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build()

    // Operators that allow arbitrary JavaScript execution
    private[datris] val BLOCKED_OPERATORS = Set("$where", "$function", "$accumulator")

    /** MongoDB's own databases: never data a pipeline wrote. */
    private[datris] val SystemDatabases: Seq[String] = Seq("admin", "local", "config")

    /** Body text of the 400 for a database the query route does not read. */
    val DatabaseNotAvailableMessage: String = "This database is not available for queries."

    /** A query naming a database the route does not read (the platform's
      * internal database or a MongoDB system database). */
    final class DatabaseNotAvailableException extends DatrisException(DatabaseNotAvailableMessage)

    /** Test seam: when set, it supplies the client instead of one built from
      * the MongoDB secret (and the secret is not read). */
    @volatile private[datris] var clientFactoryOverride: () => MongoClient = null

    /** The database a query runs against: the tenant's own database in a
      * multi-tenant install (the request's `database` is ignored), else the
      * request's `database` when given, else the user-facing default. */
    def resolveQueryDatabase(requested: String, env: DatrisEnvironment): String =
        if (env.multiTenant) env.environment
        else if (requested != null && requested.nonEmpty) requested
        else env.mongoDbConfig.database

    /** Whether the query route refuses `dbName`: the platform's internal
      * database (`internalDatabase`, the name the platform keeps its own state
      * under) or a MongoDB system database. Names are compared ignoring case
      * and surrounding whitespace, since some MongoDB platforms treat database
      * names case-insensitively; a name that only differs in case is refused
      * as well. */
    def databaseRefused(dbName: String, internalDatabase: String): Boolean = {
        if (dbName == null) false
        else {
            val reserved = SystemDatabases ++ Option(internalDatabase).map(_.trim).filter(_.nonEmpty).toSeq
            val name = dbName.trim
            reserved.exists(r =>
                name.equalsIgnoreCase(r) ||
                    name.toLowerCase(Locale.ROOT) == r.toLowerCase(Locale.ROOT) ||
                    name.toUpperCase(Locale.ROOT) == r.toUpperCase(Locale.ROOT)
            )
        }
    }

    /** The limit a find applies: None for the "unlimited" sentinel (`limit < 0`,
      * used by tap scripts), the preview default for 0, else `limit`. */
    def effectiveLimit(limit: Int): Option[Int] =
        if (limit < 0) None else if (limit > 0) Some(limit) else Some(DEFAULT_LIMIT)

    def query(
        collection: String,
        filter: java.util.Map[String, Any] = new java.util.HashMap[String, Any](),
        projection: java.util.Map[String, Any] = null,
        limit: Int = DEFAULT_LIMIT,
        database: String = null
    ): java.util.List[String] = {

        // The database decision comes first: nothing is read and no client is
        // created for a database the route does not serve.
        val env = DatrisEnvironment.current
        val dbName = resolveQueryDatabase(database, env)
        if (databaseRefused(dbName, env.mongoDbConfig.internalDatabase))
            throw new DatabaseNotAvailableException

        if (collection == null || collection.trim.isEmpty)
            throw new DatrisException("MongoDB collection name cannot be empty")

        val limitApplied = effectiveLimit(limit)

        // Validate filter and projection for operators that run server-side code
        if (filter != null) validateOperators(filter, "filter")
        if (projection != null) validateOperators(projection, "projection")

        logger.info("Querying MongoDB collection: " + collection + " with limit: " + limitApplied.map(_.toString).getOrElse("unlimited"))

        val client = newClient()

        try {
            val db = client.getDatabase(dbName)
            val coll = db.getCollection(collection)

            val filterDoc = if (filter != null && !filter.isEmpty) Document.parse(new com.google.gson.Gson().toJson(filter))
            else new Document()

            var cursor = coll.find(filterDoc)

            if (projection != null && !projection.isEmpty) {
                val projDoc = Document.parse(new com.google.gson.Gson().toJson(projection))
                cursor = cursor.projection(projDoc)
            }

            limitApplied.foreach(l => cursor = cursor.limit(l))

            val results = cursor
                .asScala
                .map(doc => doc.toJson(jsonSettings))
                .toList

            logger.info("Query returned " + results.size + " documents")
            results.asJava
        } finally {
            client.close()
        }
    }

    /** One client per query, closed by [[query]]. */
    private def newClient(): MongoClient = {
        val factory = clientFactoryOverride
        if (factory != null) factory()
        else {
            val secrets = SecretsRetrieverUtil.mongoDbSecrets()
            val settings = com.mongodb.MongoClientSettings.builder()
                .applyConnectionString(new com.mongodb.ConnectionString(secrets.connectionString))
                .build()
            com.mongodb.client.MongoClients.create(settings)
        }
    }

    /** Refuses a filter or projection (`what` names which) that carries one of
      * [[BLOCKED_OPERATORS]] as a key or a string value, at any depth: nested
      * documents and array elements included. The same rule for both, since a
      * find projection also accepts aggregation expressions. */
    private[datris] def validateOperators(doc: java.util.Map[String, Any], what: String): Unit = {
        def blocked(s: String): Option[String] = if (BLOCKED_OPERATORS.contains(s)) Some(s) else None
        def walk(v: Any): Option[String] = v match {
            case m: java.util.Map[_, _] =>
                m.asScala.iterator.map { case (k, value) =>
                    Option(k).map(_.toString).flatMap(blocked).orElse(walk(value))
                }.collectFirst { case Some(op) => op }
            case c: java.util.Collection[_] => c.asScala.iterator.map(walk).collectFirst { case Some(op) => op }
            case a: Array[_] => a.iterator.map(walk).collectFirst { case Some(op) => op }
            case s: String => blocked(s)
            case _ => None
        }
        walk(doc).foreach(op => throw new DatrisException("MongoDB " + what + " contains blocked operator: " + op))
    }
}
