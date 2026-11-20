package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Database, PipelineConfig}
import org.slf4j.{Logger, LoggerFactory}

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileStatus, FileSystem, Path}

import ai.datris.model.{DatrisEnvironment, TenantContext, UserContext}

import java.io.FileNotFoundException
import java.net.URI
import java.util.concurrent.{
    Callable,
    CancellationException,
    ConcurrentHashMap,
    ExecutionException,
    Future,
    SynchronousQueue,
    ThreadFactory,
    ThreadPoolExecutor,
    TimeUnit,
    TimeoutException
}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.duration._
import java.sql.{Connection, SQLException, Statement}
import scala.util.Try
import scala.collection.JavaConverters._

/** Existence probe for the "new destinations only" save rule
  * ([[DestinationTargets]]): does a target of the candidate config already
  * exist, reached with the candidate's own connection?
  *
  * One read-only statement (or one object listing) per target. A missing
  * database / catalog or schema counts as "does not exist"; any other failure
  * (auth, network, an unknown answer) throws, and the caller refuses the save
  * (fail closed). Identifiers are quoted with each destination's own `ident`;
  * every compared value is a bound statement parameter. */
object DestinationProbe {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /** Deadline for the whole destination check of one save (every probe
      * and the Unity Catalog state read), and the JDBC login / network /
      * statement timeout of each probe connection, so an abandoned probe's
      * connection is closed by the driver soon after. */
    val DefaultDeadline: FiniteDuration = 30.seconds

    /** Thrown when a deadline-bounded call did not finish in time (the
      * caller answers 500; nothing is saved). */
    class DeadlineExceeded(what: String, timeout: FiniteDuration) extends RuntimeException(what + " did not finish within " + timeout)

    /** Deadline for the Unity Catalog state read of a rename, which runs
      * inside the catalog lock. */
    val StateReadDeadline: FiniteDuration = 5.seconds

    /** Checks one owner (`ResolvedKey.label`) may have running at once,
      * counted until each worker really ends: a check abandoned at its
      * deadline still counts until its blocking call returns. */
    val MaxPerOwner: Int = 2

    /** Thrown, before anything is started, when the owner already has
      * [[MaxPerOwner]] checks running (the caller answers 429; nothing is
      * saved). Never an answer about the destination. */
    class TooManyChecks(what: String)
        extends RuntimeException(what + " not started: " + MaxPerOwner + " destination checks of the same caller are still running")

    private val threadCounter = new AtomicInteger(0)

    /** owner -> checks in flight; an owner with none has no entry. */
    private val inFlightByOwner = new ConcurrentHashMap[String, Integer]()

    /** Checks of `owner` in flight (spec seam). */
    private[datris] def inFlight(owner: String): Int = Option(inFlightByOwner.get(owner)).map(_.intValue).getOrElse(0)

    private def acquire(owner: String): Boolean = {
        val taken = new AtomicBoolean(false)
        inFlightByOwner.compute(
            owner,
            (_, n) => {
                val cur = if (n == null) 0 else n.intValue
                if (cur >= MaxPerOwner) n
                else {
                    taken.set(true)
                    Integer.valueOf(cur + 1)
                }
            }
        )
        taken.get
    }

    private def release(owner: String): Unit = {
        inFlightByOwner.computeIfPresent(owner, (_, n) => if (n.intValue <= 1) null else Integer.valueOf(n.intValue - 1))
        ()
    }

    /** Daemon worker pool for probes, capped so hung probes cannot pile up
      * threads without bound; a full pool refuses (the save answers 500). */
    private lazy val pool: ThreadPoolExecutor = {
        val tf = new ThreadFactory {
            def newThread(r: Runnable): Thread = {
                val t = new Thread(r, "destination-probe-" + threadCounter.incrementAndGet())
                t.setDaemon(true)
                t
            }
        }
        new ThreadPoolExecutor(0, 16, 60L, TimeUnit.SECONDS, new SynchronousQueue[Runnable](), tf)
    }

    /** Run `body` on a probe worker and wait at most `timeout` for it. On
      * time-out the worker is interrupted (an S3A / SDK retry pause or a
      * Mongo wait ends there) and [[DeadlineExceeded]] is thrown; `body`'s own
      * failure is rethrown as is. The request's tenant environment is the only
      * request state carried over: the worker gets it, never the session user,
      * and both thread-locals are cleared when `body` ends, so nothing from one
      * request is left on a pooled thread. Takes no owner slot. */
    def withDeadline[T](timeout: FiniteDuration, what: String = "destination check")(body: => T): T = {
        if (timeout.toNanos <= 0) throw new DeadlineExceeded(what, timeout)
        run(timeout, what, () => (), defaultSubmit[T])(body)
    }

    /** As above, counted against `owner` (`ResolvedKey.label`):
      * [[TooManyChecks]] at once, nothing started, when `owner` already has
      * [[MaxPerOwner]] checks in flight. The slot is taken before the submit
      * and freed by the worker when `body` really ends (an abandoned body
      * keeps it until then), or at once when the submit fails in any way
      * (the pool rejects it, or the worker thread cannot be started) or the
      * task is cancelled before it starts. */
    def withDeadline[T](timeout: FiniteDuration, what: String, owner: String)(body: => T): T =
        withDeadlineSubmitting(timeout, what, owner, defaultSubmit[T])(body)

    /** [[withDeadline]] with the submit given (spec seam; production uses
      * the probe pool). */
    private[datris] def withDeadlineSubmitting[T](timeout: FiniteDuration, what: String, owner: String, submit: Callable[T] => Future[T])(
        body: => T
    ): T = {
        if (timeout.toNanos <= 0) throw new DeadlineExceeded(what, timeout)
        // The release is built before the slot is taken, so nothing between
        // acquire and the submit inside run can throw.
        val freed = new AtomicBoolean(false)
        val onEnd: () => Unit = () => if (freed.compareAndSet(false, true)) release(owner)
        if (!acquire(owner)) throw new TooManyChecks(what)
        run(timeout, what, onEnd, submit)(body)
    }

    private def defaultSubmit[T]: Callable[T] => Future[T] = task => pool.submit(task)

    /** `onEnd` runs once: in the worker's `finally`, or on the calling
      * thread when the task never runs (the submit threw anything, e.g. a
      * rejection or an `OutOfMemoryError` starting the worker thread, or the
      * task was cancelled before it started). */
    private def run[T](timeout: FiniteDuration, what: String, onEnd: () => Unit, submit: Callable[T] => Future[T])(body: => T): T = {
        val started = new AtomicBoolean(false)
        val future =
            try {
                val tenant = TenantContext.get()
                val task = new Callable[T] {
                    def call(): T = {
                        if (!started.compareAndSet(false, true)) throw new CancellationException(what + " cancelled before it started")
                        try {
                            TenantContext.clear()
                            UserContext.clear()
                            tenant.foreach(TenantContext.set)
                            body
                        } finally {
                            TenantContext.clear()
                            UserContext.clear()
                            onEnd()
                        }
                    }
                }
                submit(task)
            } catch {
                case e: Throwable =>
                    // The task never ran: free the slot here (onEnd is idempotent).
                    if (started.compareAndSet(false, true)) onEnd()
                    throw e
            }
        def abandon(f: Future[T]): Unit = {
            f.cancel(true)
            if (started.compareAndSet(false, true)) onEnd()
        }
        try future.get(timeout.toNanos, TimeUnit.NANOSECONDS)
        catch {
            case _: TimeoutException =>
                abandon(future)
                logger.warn("Destination probe: " + what + " did not finish within " + timeout + "; abandoned (worker interrupted)")
                throw new DeadlineExceeded(what, timeout)
            case e: ExecutionException => throw Option(e.getCause).getOrElse(e)
            case e: InterruptedException =>
                abandon(future)
                Thread.currentThread().interrupt()
                throw e
        }
    }

    def exists(c: PipelineConfig, t: DestinationTargets.Target): Boolean = exists(c, t, DefaultDeadline)

    /** As above; `timeout` sets the JDBC login, network and statement
      * timeouts of the probe's connection. */
    def exists(c: PipelineConfig, t: DestinationTargets.Target, timeout: FiniteDuration): Boolean = {
        require(c != null && c.destination != null && t != null, "no destination to probe")
        val seconds = math.max(1L, timeout.toSeconds).toInt
        t.key.family match {
            case "snowflake" => snowflake(database(c), seconds)
            case "databricks" => databricks(database(c), seconds)
            case "sqlserver" => sqlServer(database(c), seconds)
            case "objectstore" => objectStore(c)
            case "pgtable" if t.key.kind == "postgres" => postgres(t, seconds)
            case "mongodb" => mongo(c, t, seconds)
            case other => throw new IllegalArgumentException("no existence probe for destination family " + other)
        }
    }

    private def database(c: PipelineConfig): Database = {
        val db = c.destination.database
        require(db != null, "no database destination to probe")
        require(!blank(db.dbName) && !blank(db.table), "database destination without dbName or table")
        db
    }

    private def blank(s: String): Boolean = s == null || s.trim.isEmpty

    /** One prepared statement with bound string parameters; true when its
      * first row's first column is above zero. */
    private def countAboveZero(conn: Connection, sql: String, params: Seq[String], seconds: Int): Boolean = {
        val ps = conn.prepareStatement(sql)
        try {
            Try(ps.setQueryTimeout(seconds))
            params.zipWithIndex.foreach { case (p, i) => ps.setString(i + 1, p) }
            val rs = ps.executeQuery()
            try rs.next() && rs.getLong(1) > 0
            finally Try(rs.close())
        } finally Try(ps.close())
    }

    private def causes(t: Throwable): Seq[Throwable] =
        Iterator.iterate(t)(_.getCause).takeWhile(_ != null).take(16).toSeq

    // ------------------------------------------------------------ Snowflake

    /** `<db>.INFORMATION_SCHEMA.TABLES` on the effective (folded) names, as
      * the loader's column lookup. The connection is opened without a
      * database or schema so a missing one cannot fail the login; a missing
      * database is Snowflake error 2003 ("does not exist or not authorized")
      * and counts as "does not exist". */
    private def snowflake(db: Database, seconds: Int): Boolean = {
        import SnowflakeConnectionUtil.{effectiveName, ident}
        require(!blank(db.schema), "snowflake destination without schema")
        val sql = "SELECT COUNT(*) FROM " + ident(db.dbName) + ".INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?"
        try
            SnowflakeConnectionUtil.withConnection(db.copy(dbName = null, schema = null), timeoutSeconds = Some(seconds)) { conn =>
                countAboveZero(conn, sql, Seq(effectiveName(db.schema), effectiveName(db.table)), seconds)
            }
        catch {
            case e: Exception if snowflakeMissingDatabase(e) =>
                logger.info("Destination probe: snowflake database " + effectiveName(db.dbName) + " does not exist (or is not visible to these credentials)")
                false
        }
    }

    private[util] def snowflakeMissingDatabase(e: Throwable): Boolean =
        causes(e).exists {
            case s: SQLException => s.getErrorCode == 2003 || Option(s.getMessage).exists(_.contains("does not exist or not authorized"))
            case _ => false
        }

    // ------------------------------------------------------------ Databricks

    /** `<catalog>.information_schema.tables`, the loader's `tableExists`
      * query (tables and views). Opened without ConnCatalog / ConnSchema. A
      * missing catalog (NO_SUCH_CATALOG / CATALOG_NOT_FOUND) counts as "does
      * not exist". A catalog without an information_schema (`hive_metastore`,
      * `spark_catalog`: TABLE_OR_VIEW_NOT_FOUND on the view itself) falls back
      * to `DESCRIBE TABLE <catalog>.<schema>.<table>`, quoted identifiers only:
      * it answers for whatever the preview's `SELECT *` would resolve, and a
      * not-found table, schema or catalog there counts as "does not exist". */
    private def databricks(db: Database, seconds: Int): Boolean = {
        import DatabricksConnectionUtil.{effectiveName, ident}
        require(!blank(db.schema), "databricks destination without schema")
        val sql = "SELECT COUNT(*) FROM " + ident(db.dbName) + ".information_schema.tables WHERE lower(table_schema) = ? AND lower(table_name) = ?"
        val describe = "DESCRIBE TABLE " + ident(db.dbName) + "." + ident(db.schema) + "." + ident(db.table)
        try
            DatabricksConnectionUtil.withConnection(db.copy(dbName = null, schema = null), timeoutSeconds = Some(seconds)) { conn =>
                try countAboveZero(conn, sql, Seq(effectiveName(db.schema), effectiveName(db.table)), seconds)
                catch {
                    case e: SQLException if !databricksMissingCatalog(e) && databricksInformationSchemaMissing(e) =>
                        val st = conn.createStatement()
                        Try(st.setQueryTimeout(seconds))
                        try describeExists(st, describe)
                        finally Try(st.close())
                }
            }
        catch {
            case e: Exception if databricksMissingCatalog(e) =>
                logger.info("Destination probe: databricks catalog " + effectiveName(db.dbName) + " does not exist (or is not visible to these credentials)")
                false
        }
    }

    /** The `DESCRIBE TABLE` fallback on `st`: true when it answers, false
      * when it says the table, schema or catalog is not found; any other
      * failure (auth, network) propagates. */
    private[util] def describeExists(st: Statement, describe: String): Boolean =
        try {
            val rs = st.executeQuery(describe)
            Try(rs.close())
            true
        } catch {
            case x: SQLException if databricksObjectMissing(x) => false
        }

    private def upperMessages(e: Throwable): Seq[String] =
        causes(e).collect { case s: SQLException => Option(s.getMessage).getOrElse("").toUpperCase(java.util.Locale.ROOT) }

    /** The catalog itself is absent. */
    private[util] def databricksMissingCatalog(e: Throwable): Boolean =
        upperMessages(e).exists(m => m.contains("NO_SUCH_CATALOG") || m.contains("CATALOG_NOT_FOUND"))

    /** The `information_schema.tables` view is absent (a non-Unity catalog);
      * says nothing about the table. */
    private[util] def databricksInformationSchemaMissing(e: Throwable): Boolean =
        upperMessages(e).exists(_.contains("TABLE_OR_VIEW_NOT_FOUND"))

    /** `DESCRIBE TABLE` found no table, schema or catalog. */
    private[util] def databricksObjectMissing(e: Throwable): Boolean =
        upperMessages(e).exists(m =>
            m.contains("TABLE_OR_VIEW_NOT_FOUND") || m.contains("SCHEMA_NOT_FOUND") || m.contains("NO_SUCH_CATALOG") ||
                m.contains("CATALOG_NOT_FOUND")
        )

    // ------------------------------------------------------------ SQL Server

    /** Every object `SELECT * FROM schema.table` can resolve (tables, views,
      * synonyms, system views): `sys.all_objects JOIN sys.schemas`, compared
      * under the database collation. The connection opens the destination
      * database; a database the login cannot open (error 4060: missing, or no
      * user for the login there) counts as "does not exist"; a missing schema
      * simply matches nothing. */
    private def sqlServer(db: Database, seconds: Int): Boolean = {
        val sql = "SELECT COUNT(*) FROM sys.all_objects o JOIN sys.schemas s ON o.schema_id = s.schema_id WHERE s.name = ? AND o.name = ?"
        try
            SqlServerConnectionUtil.withConnection(db, timeoutSeconds = Some(seconds)) { conn =>
                countAboveZero(conn, sql, Seq(SqlServerConnectionUtil.schemaName(db), db.table), seconds)
            }
        catch {
            case e: Exception if sqlServerMissingDatabase(e) =>
                logger.info("Destination probe: sql server database " + db.dbName + " cannot be opened with these credentials")
                false
        }
    }

    private[util] def sqlServerMissingDatabase(e: Throwable): Boolean =
        causes(e).exists {
            case s: SQLException => s.getErrorCode == 4060 || Option(s.getMessage).exists(_.contains("Cannot open database"))
            case _ => false
        }

    // ------------------------------------------------------------ Postgres

    /** The JDBC URL the destination-type sample and the delete use: the
      * platform secret's URL, with `/<database>` appended unless it already
      * names a database ([[PostgresSql.jdbcUrl]]: the name percent-encoded). */
    private[util] def postgresJdbcUrl(baseUrl: String, database: String): String = PostgresSql.jdbcUrl(baseUrl, database)

    /** `"schema"."table"`, each a quoted identifier (case kept, `"` doubled),
      * as the loader and the sample address the table. */
    private[util] def postgresRegclass(schema: String, table: String): String = PgIdentifier.qualified(schema, table)

    /** `to_regclass` of the quoted name, a bound parameter: anything
      * `SELECT * FROM "schema"."table"` could resolve (table, view,
      * materialized view, foreign or partitioned table) answers non-null. */
    private[util] val PostgresExistsSql: String = "SELECT CASE WHEN to_regclass(CAST(? AS text)) IS NULL THEN 0 ELSE 1 END"

    /** The platform Postgres destination: the key's database (the tenant
      * database in multi-tenant mode, else `dbName`, as the loader), schema
      * and table. Opened with the platform secret and the probe's timeouts,
      * read-only, outside the pool. */
    private def postgres(t: DestinationTargets.Target, seconds: Int): Boolean = {
        val secrets = SecretsRetrieverUtil.postgresSecrets()
        Class.forName("org.postgresql.Driver")
        postgresWith(
            t,
            secrets.jdbcUrl,
            seconds,
            url => {
                val props = new java.util.Properties()
                props.setProperty("user", secrets.username)
                props.setProperty("password", secrets.password)
                props.setProperty("connectTimeout", seconds.toString)
                props.setProperty("loginTimeout", seconds.toString)
                props.setProperty("socketTimeout", seconds.toString)
                java.sql.DriverManager.getConnection(url, props)
            }
        )
    }

    /** [[postgres]] over `open` (given the JDBC URL). A missing database
      * (SQLSTATE 3D000) or schema (3F000) counts as "does not exist"; any
      * other failure propagates. */
    private[datris] def postgresWith(t: DestinationTargets.Target, baseUrl: String, seconds: Int, open: String => Connection): Boolean = {
        require(t != null && t.key.family == "pgtable" && t.key.kind == "postgres" && t.key.parts.size == 3, "no postgres table to probe")
        val Seq(database, schema, table) = t.key.parts
        require(!blank(database) && !blank(schema) && !blank(table), "postgres destination without database, schema or table")
        try {
            val conn = open(postgresJdbcUrl(baseUrl, database))
            try {
                Try(conn.setReadOnly(true))
                countAboveZero(conn, PostgresExistsSql, Seq(postgresRegclass(schema, table)), seconds)
            } finally Try(conn.close())
        } catch {
            case e: Exception if postgresMissing(e) =>
                logger.info("Destination probe: postgres database or schema of " + database + "." + schema + " does not exist")
                false
        }
    }

    private[util] def postgresMissing(e: Throwable): Boolean =
        causes(e).exists {
            case s: SQLException => s.getSQLState == "3D000" || s.getSQLState == "3F000"
            case _ => false
        }

    // ------------------------------------------------------------ pgvector

    /** The pgvector secret the loader uses: the environment's
      * `pgvectorSecretName`, else the config's `postgresSecretName` (the
      * delete's DROP uses the environment's, which is set on every install). */
    private[util] def pgvectorSecretName(envSecretName: String, c: PipelineConfig): String =
        if (envSecretName != null) envSecretName else Option(c.destination.pgvector).map(_.postgresSecretName).orNull

    /** The JDBC URL the pgvector loader and the delete's DROP connect to: the
      * secret's `jdbcUrl` as is, or with its last path segment replaced by the
      * tenant database in multi-tenant mode. */
    private[util] def pgvectorJdbcUrl(rawJdbcUrl: String, tenantDatabase: Option[String]): String =
        tenantDatabase match {
            case Some(db) => rawJdbcUrl.replaceFirst("/[^/]*$", "/" + db)
            case None => rawJdbcUrl
        }

    def vectorTableExists(c: PipelineConfig, t: DestinationTargets.Target): Boolean = vectorTableExists(c, t, DefaultDeadline)

    /** Does the candidate's pgvector table (a
      * `DestinationTargets.vectorTableTargets` target) already exist where
      * the pgvector loader writes and the delete drops it: the pgvector
      * secret's database (the tenant database in multi-tenant mode), the
      * schema and table of the target's `exact`, `to_regclass` of the quoted
      * name. A missing secret or `jdbcUrl` throws (the save answers 500). */
    def vectorTableExists(c: PipelineConfig, t: DestinationTargets.Target, timeout: FiniteDuration): Boolean = {
        require(c != null && c.destination != null && c.destination.pgvector != null && t != null, "no pgvector destination to probe")
        val seconds = math.max(1L, timeout.toSeconds).toInt
        val env = DatrisEnvironment.current
        val secretName = pgvectorSecretName(env.pgvectorSecretName, c)
        require(!blank(secretName), "no pgvector secret configured")
        val secret = SecretsUtil.getSecretMap(secretName).getOrElse(throw new IllegalStateException("pgvector secret not found"))
        val rawUrl = secret.get("jdbcUrl")
        require(!blank(rawUrl), "pgvector secret without jdbcUrl")
        val username = Option(secret.get("username")).getOrElse("postgres")
        val password = Option(secret.get("password")).getOrElse("")
        val tenant = if (env.multiTenant) Option(env.environment) else None
        Class.forName("org.postgresql.Driver")
        vectorTableWith(
            t,
            pgvectorJdbcUrl(rawUrl, tenant),
            seconds,
            url => {
                val props = new java.util.Properties()
                props.setProperty("user", username)
                props.setProperty("password", password)
                props.setProperty("connectTimeout", seconds.toString)
                props.setProperty("loginTimeout", seconds.toString)
                props.setProperty("socketTimeout", seconds.toString)
                java.sql.DriverManager.getConnection(url, props)
            }
        )
    }

    /** [[vectorTableExists]] over `open` (given the JDBC URL, used as is). A
      * missing database (SQLSTATE 3D000) or schema (3F000) counts as "does
      * not exist"; any other failure propagates. */
    private[datris] def vectorTableWith(t: DestinationTargets.Target, jdbcUrl: String, seconds: Int, open: String => Connection): Boolean = {
        require(t != null && t.key.family == "pgtable" && t.key.kind == "pgvector" && t.exact.size == 2, "no pgvector table to probe")
        val Seq(schema, table) = t.exact
        require(schema != null && schema.nonEmpty && !blank(table), "pgvector destination without schema or table")
        require(!schema.contains('"') && !table.contains('"'), "pgvector schema or table not a plain quoted identifier")
        try {
            val conn = open(jdbcUrl)
            try {
                Try(conn.setReadOnly(true))
                countAboveZero(conn, PostgresExistsSql, Seq(postgresRegclass(schema, table)), seconds)
            } finally Try(conn.close())
        } catch {
            case e: Exception if postgresMissing(e) =>
                logger.info("Destination probe: pgvector database or schema " + schema + " does not exist")
                false
        }
    }

    // ------------------------------------------------------------ MongoDB

    /** The databases a MongoDB destination is addressed as: the loader's
      * `dbName` and the key's database (the tenant database in multi-tenant
      * mode), distinct, blanks dropped. */
    private[util] def mongoDatabases(c: PipelineConfig, t: DestinationTargets.Target): Seq[String] =
        (Seq(t.key.parts.headOption.orNull) ++ Option(c.destination.database).map(_.dbName).toSeq).filterNot(blank).distinct

    /** True when a collection or view named exactly `table` (case kept) is in
      * any of [[mongoDatabases]]. A database that does not exist lists no
      * collections (MongoDB creates databases on first write), so it is
      * "does not exist"; any failure propagates. */
    private[datris] def mongoWith(c: PipelineConfig, t: DestinationTargets.Target, collectionExists: (String, String) => Boolean): Boolean = {
        require(c != null && c.destination != null && c.destination.database != null, "no mongodb destination to probe")
        val coll = c.destination.database.table
        require(!blank(coll), "mongodb destination without a collection")
        val dbs = mongoDatabases(c, t)
        require(dbs.nonEmpty, "mongodb destination without a database")
        dbs.exists(d => collectionExists(d, coll))
    }

    /** The platform MongoDB (the loader's secret), on a client of its own
      * with the probe's timeouts, closed here. */
    private def mongo(c: PipelineConfig, t: DestinationTargets.Target, seconds: Int): Boolean = {
        val secrets = SecretsRetrieverUtil.mongoDbSecrets()
        val settings = com.mongodb.MongoClientSettings
            .builder()
            .applyConnectionString(new com.mongodb.ConnectionString(secrets.connectionString))
            .applyToClusterSettings(b => { b.serverSelectionTimeout(seconds.toLong, TimeUnit.SECONDS); () })
            .applyToSocketSettings(b => {
                b.connectTimeout(seconds, TimeUnit.SECONDS)
                b.readTimeout(seconds, TimeUnit.SECONDS)
                ()
            })
            .build()
        val client = com.mongodb.client.MongoClients.create(settings)
        try
            mongoWith(
                c,
                t,
                (database, coll) =>
                    client
                        .getDatabase(database)
                        .listCollections()
                        .filter(com.mongodb.client.model.Filters.eq("name", coll))
                        .first() != null
            )
        finally Try(client.close())
    }

    // ------------------------------------------------------------ object store

    /** S3A settings of the probe's own FileSystem, as per-bucket keys (they
      * win over any global): connect within 5 s, a read answered within
      * 10 s, no SDK or S3A retries, throttled (503) answers included. A dead endpoint fails in about 5 s; a
      * worker is never busy long past the deadline. Loaders and readers keep
      * their own settings. */
    def ProbeS3ASettings(bucket: String): Seq[(String, String)] = {
        val p = "fs.s3a.bucket." + bucket + "."
        Seq(
            p + "connection.establish.timeout" -> "5000",
            p + "connection.timeout" -> "10000",
            p + "attempts.maximum" -> "0",
            p + "retry.limit" -> "0",
            p + "retry.throttle.limit" -> "0"
        )
    }

    /** Any object under `<bucket>/<prefix>/` (the whole bucket for an empty
      * prefix), listed through the Hadoop FileSystem with the per-bucket
      * settings applied, as reads and deletes do. A missing prefix or bucket
      * counts as "does not exist". */
    private def objectStore(c: PipelineConfig): Boolean =
        objectStoreWith(c, SparkSessionManager.getOrCreate().sparkContext.hadoopConfiguration, (u, conf) => FileSystem.newInstance(u, conf))

    /** [[objectStore]] over `shared` (the Spark Hadoop configuration) and
      * `open` (production: `FileSystem.newInstance`, uncached). The
      * candidate's per-bucket settings (endpoint checked by `SsrfGuard`
      * before anything is opened) and [[ProbeS3ASettings]] go on a COPY of
      * `shared`; the probe's FileSystem is its own and is closed here, on
      * this thread, after an answer, a failure or an interrupt. `shared`, the
      * fingerprint map and the cached FileSystem are never touched, so an
      * unsaved candidate changes nothing other pipelines of the bucket use. */
    private[datris] def objectStoreWith(c: PipelineConfig, shared: Configuration, open: (URI, Configuration) => FileSystem): Boolean = {
        require(c != null && c.destination != null, "no destination to probe")
        val os = c.destination.objectStore
        require(os != null, "no object-store destination to probe")
        val bucket = ObjectStoreSpark.resolveBucket(os)
        val prefix = ObjectStoreSpark.normalizePrefix(os.prefixKey)
        val conf = new Configuration(shared)
        ObjectStoreSpark.applyPerBucketSettings(conf, bucket, os)
        ProbeS3ASettings(bucket).foreach { case (k, v) => conf.set(k, v) }
        val path = new Path("s3a://" + bucket + "/" + prefix)
        val fs = open(new URI("s3a://" + bucket), conf)
        try anyObjectUnder(fs, path)
        finally {
            // An interrupt (the deadline) must not cut the close short: clear
            // the flag for the close and set it again afterwards.
            val interrupted = Thread.interrupted()
            try
                Try(fs.close()).failed.foreach(e =>
                    logger.warn("Destination probe: closing the probe FileSystem for s3a://" + bucket + "/ failed: " + e.getMessage)
                )
            finally if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /** True when `path` (expanded as a glob, as a Spark read expands it, and
      * normalised by `Path`) matches a file or a directory holding any file.
      * A path or bucket that is not there is false; any other failure throws. */
    private[util] def anyObjectUnder(fs: FileSystem, path: Path): Boolean =
        try {
            val matched = Option(fs.globStatus(path)).getOrElse(Array.empty[FileStatus])
            matched.exists { st =>
                if (st.isFile) true
                else
                    try fs.listFiles(st.getPath, true).hasNext
                    catch { case e: Exception if objectStoreMissing(e) => false }
            }
        } catch {
            case e: Exception if objectStoreMissing(e) => false
        }

    private[util] def objectStoreMissing(e: Throwable): Boolean =
        causes(e).exists {
            case _: FileNotFoundException => true
            case _: org.apache.hadoop.fs.s3a.UnknownStoreException => true
            case _ => false
        }

    // ------------------------------------------------------------ Unity Catalog state

    /** True when the Unity Catalog sync state kept for `pipeline` records a
      * catalog commit (`catalogMode` managed or rest). An object-store Iceberg
      * read of a pipeline of that name loads the recorded table, which for a
      * managed commit lives outside the prefix. A failed read throws. */
    def catalogCommitted(pipeline: String): Boolean = committed(UnityCatalogSyncIO.read(pipeline))

    private[util] def committed(doc: ai.datris.model.UnityCatalogSyncState): Boolean =
        doc != null && (IcebergRestSession.managedCommitted(doc) ||
            (doc.catalogMode == "rest" && doc.restMetadataLocation != null && doc.lastRestCommitAt != null))

    // ------------------------------------------------ qdrant, weaviate, milvus, chroma containers

    /** The requests the container probe sends for `exact` in `store`, in
      * order, addressed exactly as the delete's drop addresses the store
      * (`VectorStoreDrop.endpoint`: host, port, scheme, auth header):
      *  - qdrant: `GET /collections`, then `GET /aliases` (REST at the
      *    secret's gRPC port minus one);
      *  - weaviate: `GET /v1/schema`;
      *  - milvus: `POST /v2/vectordb/collections/has` with
      *    `{"collectionName": ...}` (no `dbName`: the default database, as
      *    the loader and the delete);
      *  - chroma: `GET` the collection under the default tenant and
      *    database, then the control list `GET .../collections?limit=1`.
      * Throws for a null secret, a blank host, an unusable port or scheme,
      * or an unknown store. */
    def vectorContainerRequest(store: String, secret: java.util.Map[String, String], exact: String): Seq[VectorStoreDrop.Request] =
        VectorStoreDrop.endpoint(store, secret) match {
            case Left(f) => throw new IllegalStateException(store + " container check: " + f.reason)
            case Right((base, headers)) =>
                store match {
                    case "qdrant" =>
                        Seq(
                            VectorStoreDrop.Request("GET", base + "/collections", headers, None),
                            VectorStoreDrop.Request("GET", base + "/aliases", headers, None)
                        )
                    case "weaviate" => Seq(VectorStoreDrop.Request("GET", base + "/v1/schema", headers, None))
                    case "milvus" =>
                        Seq(VectorStoreDrop.Request("POST", base + "/v2/vectordb/collections/has", headers, Some(VectorStoreDrop.milvusNameBody(exact))))
                    case "chroma" =>
                        Seq(
                            VectorStoreDrop.Request("GET", base + VectorStoreDrop.ChromaCollectionsPath + "/" + VectorStoreDrop.segment(exact), headers, None),
                            VectorStoreDrop.Request("GET", base + VectorStoreDrop.ChromaCollectionsPath + "?limit=1", headers, None)
                        )
                    case other => throw new IllegalArgumentException("not a probed vector store: " + other)
                }
        }

    private def unknown(store: String, what: String): Nothing = throw new IllegalStateException(store + " container check: " + what)

    private def jsonOf(store: String, body: String): com.google.gson.JsonElement =
        try {
            val e = com.google.gson.JsonParser.parseString(if (body == null) "" else body)
            if (e == null || e.isJsonNull) unknown(store, "empty answer") else e
        } catch {
            case _: com.google.gson.JsonParseException => unknown(store, "answer is not JSON")
        }

    private def objectOf(store: String, body: String): com.google.gson.JsonObject = {
        val e = jsonOf(store, body)
        if (e.isJsonObject) e.getAsJsonObject else unknown(store, "answer is not a JSON object")
    }

    private def arrayField(store: String, o: com.google.gson.JsonObject, field: String): Seq[com.google.gson.JsonObject] = {
        val a = o.get(field)
        if (a == null || !a.isJsonArray) unknown(store, "answer without " + field)
        a.getAsJsonArray.asScala.toSeq.map(e => if (e != null && e.isJsonObject) e.getAsJsonObject else unknown(store, "malformed " + field))
    }

    private def stringField(store: String, o: com.google.gson.JsonObject, field: String): String = {
        val v = o.get(field)
        if (v == null || !v.isJsonPrimitive || !v.getAsJsonPrimitive.isString) unknown(store, "answer without " + field)
        v.getAsString
    }

    private def ok(store: String, a: (Int, String)): String =
        if (a._1 == 200) a._2 else unknown(store, "answered HTTP " + a._1)

    /** Some(answer) once `answers` (a prefix of [[vectorContainerRequest]]'s
      * answers, in order) is definite, None when the next request is
      * needed; throws for anything that is neither. */
    private def decideVectorContainer(store: String, exact: String, answers: Seq[(Int, String)]): Option[Boolean] = {
        if (answers.isEmpty) return None
        store match {
            case "qdrant" =>
                val result = objectOf(store, ok(store, answers.head)).get("result")
                if (result == null || !result.isJsonObject) unknown(store, "answer without result")
                val listed = arrayField(store, result.getAsJsonObject, "collections").exists(c => stringField(store, c, "name") == exact)
                if (listed) Some(true)
                else if (answers.size < 2) None
                else {
                    // A same-named alias is read and written through by name, so it counts as existing.
                    val ar = objectOf(store, ok(store, answers(1))).get("result")
                    if (ar == null || !ar.isJsonObject) unknown(store, "alias answer without result")
                    Some(arrayField(store, ar.getAsJsonObject, "aliases").exists(a => stringField(store, a, "alias_name") == exact))
                }
            case "weaviate" =>
                Some(arrayField(store, objectOf(store, ok(store, answers.head)), "classes").exists(c => stringField(store, c, "class").equalsIgnoreCase(exact)))
            case "milvus" =>
                val o = objectOf(store, ok(store, answers.head))
                val code = o.get("code")
                if (code == null || !code.isJsonPrimitive || !code.getAsJsonPrimitive.isNumber || code.getAsJsonPrimitive.getAsDouble != 0.0)
                    unknown(store, "answer without code 0")
                val data = o.get("data")
                if (data == null || !data.isJsonObject) unknown(store, "answer without data")
                val has = data.getAsJsonObject.get("has")
                if (has == null || !has.isJsonPrimitive || !has.getAsJsonPrimitive.isBoolean) unknown(store, "answer without data.has")
                Some(has.getAsBoolean)
            case "chroma" =>
                answers.head._1 match {
                    case 200 =>
                        if (stringField(store, objectOf(store, answers.head._2), "name") == exact) Some(true)
                        else unknown(store, "answer names another collection")
                    case 404 =>
                        // 404 is "absent" only when the control list proves the tenant and database path.
                        if (answers.size < 2) None
                        else if (jsonOf(store, ok(store, answers(1))).isJsonArray) Some(false)
                        else unknown(store, "control answer is not a list")
                    case other => unknown(store, "answered HTTP " + other)
                }
            case other => throw new IllegalArgumentException("not a probed vector store: " + other)
        }
    }

    /** True = the container `exact` exists in `store`, false = definitely
      * absent, from the answers to [[vectorContainerRequest]] (a prefix of
      * them, in order; the probe stops once definite). Throws for anything
      * else (a status other than 200, chroma's 404 without the control, an
      * empty or non-JSON body, a missing field, milvus `code` not 0).
      *
      * The answers are the stores' documented ones; they were not recorded
      * against live stores (the Docker E2E checks qdrant, weaviate and
      * chroma; milvus is from its REST v2 reference only). Qdrant: 200
      * `result.collections[].name`, `result.aliases[].alias_name` (an alias
      * of that name counts as existing: the loader and the delete address it
      * by name). Weaviate: 200 `classes[].class`, compared ignoring case.
      * Milvus: 200 `code` 0 with boolean `data.has`. Chroma v2: 200 with the
      * collection's `name`, or 404 with the control list answering a 200
      * JSON array. */
    def vectorContainerAnswer(store: String, exact: String, answers: Seq[(Int, String)]): Boolean =
        decideVectorContainer(store, exact, Option(answers).getOrElse(Nil)).getOrElse(unknown(store, "no definite answer"))

    /** The container probe over a secret read keyed by store (Success(None)
      * = no secret, Failure = the read failed: both throw, nothing is sent)
      * and a transport. Sends [[vectorContainerRequest]]'s requests in order
      * until [[vectorContainerAnswer]] is definite. */
    def vectorContainerExistsWith(
        t: DestinationTargets.VectorContainerTarget,
        secretOf: String => scala.util.Try[Option[java.util.Map[String, String]]],
        send: VectorStoreDrop.Request => (Int, String)
    ): Boolean = {
        require(t != null && t.key != null, "no vector container to probe")
        val store = t.key.store
        val secret = secretOf(store).get.getOrElse(unknown(store, "the store is not configured"))
        val requests = vectorContainerRequest(store, secret, t.exact)
        var answers = Vector.empty[(Int, String)]
        var decided: Option[Boolean] = None
        val it = requests.iterator
        while (decided.isEmpty && it.hasNext) {
            answers = answers :+ send(it.next())
            decided = decideVectorContainer(store, t.exact, answers)
        }
        decided.getOrElse(unknown(store, "no definite answer"))
    }

    /** The environment's secret name for `store` (the one the loaders, the
      * search and the delete read; one store instance per environment). */
    def vectorStoreSecretName(store: String): String = {
        val e = DatrisEnvironment.current
        store match {
            case "qdrant" => e.qdrantSecretName
            case "weaviate" => e.weaviateSecretName
            case "milvus" => e.milvusSecretName
            case "chroma" => e.chromaSecretName
            case other => throw new IllegalArgumentException("not a probed vector store: " + other)
        }
    }

    /** Production: [[vectorContainerExistsWith]] over the environment's
      * store secret ([[vectorStoreSecretName]], read with
      * `SecretsUtil.tryGetSecretMap`) and `VectorStoreDrop.send` (5 s
      * connect and read, no redirects, a whole-call deadline). Runs under
      * the save's deadline and slot (`withDeadline`, by the caller). */
    def vectorContainerExists(t: DestinationTargets.VectorContainerTarget): Boolean =
        vectorContainerExists(t, name => SecretsUtil.tryGetSecretMap(name))

    /** [[vectorContainerExists]] with the secret read given (by secret
      * name): the environment's [[vectorStoreSecretName]] for the target's
      * store is read with `readSecret`, and the store is asked over
      * `VectorStoreDrop.send`. */
    def vectorContainerExists(
        t: DestinationTargets.VectorContainerTarget,
        readSecret: String => scala.util.Try[Option[java.util.Map[String, String]]]
    ): Boolean =
        vectorContainerExistsWith(t, store => scala.util.Try(vectorStoreSecretName(store)).flatMap(readSecret), r => VectorStoreDrop.send(r))
}
