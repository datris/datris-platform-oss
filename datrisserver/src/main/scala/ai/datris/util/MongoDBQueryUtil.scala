package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, DatrisException}
import com.mongodb.{MongoExecutionTimeoutException, MongoSocketReadTimeoutException}
import com.mongodb.client.MongoClient
import org.bson.Document
import org.bson.json.{JsonMode, JsonWriterSettings}
import org.slf4j.{Logger, LoggerFactory}

import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._
import scala.util.Try

object MongoDBQueryUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    // No document-count cap: callers (tap scripts) may need the full collection.
    // Callers that want a preview-sized result pass an explicit positive limit.
    // Every query still has a time limit ([[timeoutSeconds]]) and a result-size
    // ceiling ([[maxResultBytes]]).
    private val DEFAULT_LIMIT = 20

    /** Time limit (seconds) for a bounded query (`limit >= 0`). The same value
      * as [[PostgresQueryUtil.QUERY_TIMEOUT_SECONDS]]. */
    private[util] val QUERY_TIMEOUT_SECONDS = 30

    /** Time limit (seconds) for an unlimited read (`limit < 0`). The same
      * value as [[PostgresQueryUtil.UNLIMITED_QUERY_TIMEOUT_SECONDS]]. */
    private[util] val UNLIMITED_QUERY_TIMEOUT_SECONDS = 300

    /** Added to a query's time limit to give the client's socket read
      * timeout, so the server-side `maxTime` normally fires first and a dead
      * socket still returns. */
    private[util] val SOCKET_READ_MARGIN_SECONDS = 5

    /** The time limit a query runs under: [[QUERY_TIMEOUT_SECONDS]] for a
      * bounded query, [[UNLIMITED_QUERY_TIMEOUT_SECONDS]] for `limit < 0`. */
    private[util] def timeoutSeconds(limit: Int): Int =
        if (limit < 0) UNLIMITED_QUERY_TIMEOUT_SECONDS else QUERY_TIMEOUT_SECONDS

    private val BytesPerMb: Long = 1024L * 1024L

    /** Default result-size ceiling, in MB (1024 x 1024) of JSON text. Small
      * on purpose: while answering, the route holds the JSON strings, the
      * parsed documents and the serialised body at once, several times the
      * JSON text size in heap. */
    private[util] val DEFAULT_MAX_RESULT_MB = 32L

    /** The largest ceiling in MB whose byte count fits in a Long. */
    private[util] val MAX_RESULT_MB_LIMIT: Long = Long.MaxValue / BytesPerMb

    private val MaxResultMbVariable = "DATRIS_QUERY_MAX_RESULT_MB"

    /** The result-size ceiling in MB from the value of
      * `DATRIS_QUERY_MAX_RESULT_MB`: unset gives [[DEFAULT_MAX_RESULT_MB]],
      * `0` = no ceiling, a whole number is used as given (surrounding spaces
      * ignored; a value too large to count in bytes is clamped to
      * [[MAX_RESULT_MB_LIMIT]]). A blank, non-numeric, fractional or negative
      * value falls back to the default with one warning naming the variable. */
    private[util] def parseMaxResultMb(raw: Option[String]): Long =
        raw match {
            case None => DEFAULT_MAX_RESULT_MB
            case Some(value) =>
                Try(BigInt(value.trim)).toOption.filter(_ >= 0) match {
                    case Some(mb) => if (mb > BigInt(MAX_RESULT_MB_LIMIT)) MAX_RESULT_MB_LIMIT else mb.toLong
                    case None =>
                        logger.warn(
                            MaxResultMbVariable + "=\"" + value + "\" is not a whole number of MB >= 0; using the default of " +
                                DEFAULT_MAX_RESULT_MB + " MB"
                        )
                        DEFAULT_MAX_RESULT_MB
                }
        }

    /** The most JSON text (characters of the serialised documents; one byte
      * each for ASCII) a query may return, read once at load. `0` = no
      * ceiling. A `var` so a spec can set it. */
    @volatile private[datris] var maxResultBytes: Long = parseMaxResultMb(sys.env.get(MaxResultMbVariable)) * BytesPerMb

    /** What both refusals tell the caller to do instead. */
    private val SplitAdvice = "split the read into ranges with a filter, each with a positive limit."

    /** A query that did not finish within its time limit (the server's
      * `maxTime` or the client's socket read timeout). */
    final class QueryTimeLimitException(seconds: Int)
        extends DatrisException(
            "The query did not finish within its time limit of " + seconds + " seconds; " + SplitAdvice
        )

    /** A result larger than [[maxResultBytes]]. */
    final class ResultTooLargeException(ceilingBytes: Long)
        extends DatrisException(
            "The result is larger than " + formatMb(ceilingBytes) + " MB; " + SplitAdvice
        )

    private def formatMb(bytes: Long): String =
        if (bytes % BytesPerMb == 0) (bytes / BytesPerMb).toString
        else BigDecimal(bytes.toDouble / BytesPerMb).setScale(2, BigDecimal.RoundingMode.HALF_UP).bigDecimal.stripTrailingZeros.toPlainString
    private val jsonSettings = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build()

    // Operators that allow arbitrary JavaScript execution
    private[datris] val BLOCKED_OPERATORS = Set("$where", "$function", "$accumulator")

    /** MongoDB's own databases ([[MongoReservedNames.SystemDatabases]]). */
    private[datris] val SystemDatabases: Seq[String] = MongoReservedNames.SystemDatabases

    /** Body text of the 400 for a database or collection the query route does
      * not read. */
    val DatabaseNotAvailableMessage: String = "This database or collection is not available for queries."

    /** A query naming a database or collection the route does not read
      * ([[queryRefused]]). */
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

    /** Whether the query route refuses `collection` in `dbName`: a MongoDB
      * system database (whatever the collection), a `system.` collection, or
      * a platform collection in the internal database
      * ([[MongoReservedNames.protectedCollection]]). */
    def queryRefused(dbName: String, collection: String, env: DatrisEnvironment): Boolean =
        MongoReservedNames.systemDatabase(dbName) || (collection != null && MongoReservedNames.protectedCollection(dbName, collection, env))

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
        if (queryRefused(dbName, collection, env))
            throw new DatabaseNotAvailableException

        if (collection == null || collection.trim.isEmpty)
            throw new DatrisException("MongoDB collection name cannot be empty")

        val limitApplied = effectiveLimit(limit)

        // Validate filter and projection for operators that run server-side code
        if (filter != null) validateOperators(filter, "filter")
        if (projection != null) validateOperators(projection, "projection")

        logger.info("Querying MongoDB collection: " + collection + " with limit: " + limitApplied.map(_.toString).getOrElse("unlimited"))

        val seconds = timeoutSeconds(limit)
        val client = newClient(seconds)

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

            // The server stops the operation once the time limit passes.
            cursor = cursor.maxTime(seconds.toLong, TimeUnit.SECONDS)

            val results = readWithin(cursor, maxResultBytes, seconds)

            logger.info("Query returned " + results.size + " documents")
            results
        } finally {
            client.close()
        }
    }

    /** Reads `find` to the end, serialising each document once and adding up
      * its JSON text length. Past `ceilingBytes` (when > 0) it stops reading,
      * closes the cursor and throws [[ResultTooLargeException]]. A time-limit
      * failure from the driver becomes [[QueryTimeLimitException]] (no host or
      * port in its message). */
    private def readWithin(
        find: com.mongodb.client.FindIterable[Document],
        ceilingBytes: Long,
        seconds: Int
    ): java.util.List[String] = {
        val results = new java.util.ArrayList[String]()
        var total = 0L
        try {
            val it = find.iterator()
            var primary: Throwable = null
            try {
                while (it.hasNext) {
                    val json = it.next().toJson(jsonSettings)
                    total += json.length
                    if (ceilingBytes > 0 && total > ceilingBytes) {
                        logger.warn(
                            "MongoDB query result passed the ceiling of " + ceilingBytes + " characters after " +
                                (results.size + 1) + " documents; refused"
                        )
                        throw new ResultTooLargeException(ceilingBytes)
                    }
                    results.add(json)
                }
            } catch {
                case t: Throwable =>
                    primary = t
                    throw t
            } finally {
                // A failure to close never replaces what the read itself threw.
                try it.close()
                catch {
                    case closeFailure: Exception =>
                        if (primary != null) primary.addSuppressed(closeFailure)
                        else logger.warn("MongoDB query: closing the cursor failed: " + closeFailure.getClass.getName)
                }
            }
        } catch {
            case _: MongoExecutionTimeoutException | _: MongoSocketReadTimeoutException =>
                logger.warn("MongoDB query did not finish within " + seconds + " s")
                throw new QueryTimeLimitException(seconds)
        }
        results
    }

    /** Settings for a query's client: the connection string, and a socket
      * read timeout of the query's time limit plus
      * [[SOCKET_READ_MARGIN_SECONDS]]. */
    private[util] def clientSettings(connectionString: String, timeLimitSeconds: Int): com.mongodb.MongoClientSettings =
        com.mongodb.MongoClientSettings.builder()
            .applyConnectionString(new com.mongodb.ConnectionString(connectionString))
            .applyToSocketSettings(b => b.readTimeout(timeLimitSeconds + SOCKET_READ_MARGIN_SECONDS, TimeUnit.SECONDS))
            .build()

    /** One client per query, closed by [[query]]. Its socket read timeout is
      * the query's time limit plus [[SOCKET_READ_MARGIN_SECONDS]], so an
      * unlimited read gets the longer one. The test seam ignores the limit. */
    private def newClient(timeLimitSeconds: Int): MongoClient = {
        val factory = clientFactoryOverride
        if (factory != null) factory()
        else {
            val secrets = SecretsRetrieverUtil.mongoDbSecrets()
            val settings = clientSettings(secrets.connectionString, timeLimitSeconds)
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
