package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import org.slf4j.{Logger, LoggerFactory}

import java.sql.{Connection, SQLException, SQLTimeoutException}
import java.util.Locale
import java.util.concurrent.TimeUnit
import scala.util.Try
import scala.util.control.NonFatal

/** The pipeline delete's removal of one destination's data (a Postgres,
  * pgvector, SQL Server, Snowflake or Databricks table, a MongoDB
  * collection, an object-store prefix, the scratch folder) as a [[Step]]
  * with an [[Outcome]], so a removal that failed is reported instead of only
  * logged. No Spring, no DatrisEnvironment: the caller reads the secrets.
  *
  * The outcome type is [[VectorStoreDrop]]'s (one type for every store) and
  * the warning sentences are the ones the vector-store drop introduced.
  * Reasons are a closed set; they never carry an exception message, SQL
  * text, host, JDBC URL or secret name (restricted users read delete
  * warnings). That detail goes to the server log only.
  *
  * Bounds: every JDBC drop connects, logs in and reads within
  * [[TimeoutSeconds]] (the destination probe's deadline) and its statement
  * has the same query timeout. The Postgres and pgvector drops also run in an
  * explicit transaction with a server-side `statement_timeout`, so a drop
  * cut off by the bound (a lock wait behind a running load, a dead socket)
  * is abandoned by the server and never commits after the caller was told it
  * failed. The MongoDB drop client has the probe's server selection, connect
  * and read timeouts. S3A deletes keep S3A's own timeouts and retries.
  *
  * The codes classified below were NOT recorded against live stores (the
  * story's local-stack recording step was not run; unit tests only). They
  * are taken from the drivers' and stores' documentation:
  *  - Postgres (pgjdbc, PostgreSQL error-code appendix): class 08 connection
  *    exception (refused, broken, cannot connect); class 28 invalid
  *    authorization (28P01 wrong password, 28000); 42501 insufficient
  *    privilege (not the owner); 57014 query canceled (statement_timeout or a
  *    client cancel); 55P03 lock not available; 3D000 database and 3F000
  *    schema missing (as `DestinationProbe.postgresMissing`). A stopped
  *    server is a `PSQLException` 08001 caused by `ConnectException`.
  *  - MongoDB (driver 4.11): `MongoSocketOpenException` (no listener),
  *    `MongoTimeoutException` (server selection timed out: a stopped server),
  *    `MongoSocketReadTimeoutException`, `MongoSecurityException`
  *    (authentication), command codes 13 Unauthorized, 18 AuthenticationFailed,
  *    26 NamespaceNotFound, 50 MaxTimeMSExpired.
  *  - Snowflake (JDBC driver): 390100 incorrect username or password, 390144
  *    invalid JWT (key-pair), 3001 insufficient privileges, 604 / 57014
  *    statement canceled by timeout, class 08 communication errors. 2003
  *    "does not exist or not authorized" is ambiguous (the table may exist
  *    and be invisible to the role), so it is a reported error, not Absent.
  *  - SQL Server (mssql-jdbc): 18456 login failed, 229 / 262 / 3701
  *    permission denied (with IF EXISTS a missing table is not an error),
  *    911 database does not exist, 08S01 link failure, HY008 / SQLTimeout
  *    query timed out. 4060 "cannot open database" (missing, or the login has
  *    no user there) is ambiguous, so it is a reported error, not Absent.
  *  - Databricks (databricks-jdbc 3.x): the error classes in the message
  *    (`INSUFFICIENT_PERMISSIONS`, `PERMISSION_DENIED`, `SCHEMA_NOT_FOUND`,
  *    `NO_SUCH_CATALOG_EXCEPTION`, `CATALOG_NOT_FOUND`); connect failures
  *    arrive as the text `DatabricksConnectionUtil.translateConnectError`
  *    builds (its cause dropped). Whether the driver's `socketTimeout` cuts
  *    off a stopped warehouse's start was not measured.
  *  - Object store (S3A, hadoop-aws 3.3.4): `AccessDeniedException` (403;
  *    S3A cannot tell refused credentials from a denied action, so both are
  *    "refused permission"), `FileNotFoundException` and
  *    `UnknownStoreException` (missing prefix or bucket), connect failures as
  *    a `ConnectException` / `UnknownHostException` cause.
  *  - A missing secret or credentials secret: the `DatrisException` texts of
  *    `SecretsRetrieverUtil`, `CredentialResolver` and `DatabricksWarehouse`. */
object DestinationDrop {

    private val logger: Logger = LoggerFactory.getLogger(getClass)

    // One outcome type: the vector-store drop's.
    type Outcome = VectorStoreDrop.Outcome
    val Removed: VectorStoreDrop.Removed.type = VectorStoreDrop.Removed
    val Absent: VectorStoreDrop.Absent.type = VectorStoreDrop.Absent
    val NotSent: VectorStoreDrop.NotSent.type = VectorStoreDrop.NotSent
    type Failed = VectorStoreDrop.Failed
    val Failed: VectorStoreDrop.Failed.type = VectorStoreDrop.Failed

    // The closed set of reasons.
    val NotConfigured: String = VectorStoreDrop.NotConfigured
    val Unreachable: String = VectorStoreDrop.Unreachable
    val TimedOut: String = VectorStoreDrop.TimedOut
    val CredentialsRefused = "the store refused the credentials"
    val PermissionRefused = "the store refused permission to remove it"
    val ReportedError: String = VectorStoreDrop.ReportedError

    /** The bound on login, each read and the drop statement, in seconds:
      * `DestinationProbe.DefaultDeadline`. */
    val TimeoutSeconds: Int = 30

    /** One removal. `kind` (postgres, pgvector, mongodb, snowflake,
      * sqlserver, databricks, objectstore, scratch, or empty) is what a
      * throwing `run` is classified as. `countsAsData` is false for the
      * scratch folder and stored history: their failure does not keep the
      * document-tap ledgers. */
    case class Step(label: String, countsAsData: Boolean, run: () => Outcome, kind: String = "")

    /** Runs every step in order; never throws. A step that throws is
      * [[classify]]d by its kind (the detail is logged here). */
    def runAll(steps: Seq[Step]): Seq[(Step, Outcome)] =
        steps.map { s =>
            val o: Outcome =
                try Option(s.run()).getOrElse(Failed(ReportedError))
                catch {
                    case e: InterruptedException =>
                        Thread.currentThread().interrupt()
                        logger.warn("Pipeline delete: removing " + s.label + " was interrupted")
                        Failed(TimedOut)
                    case e: Throwable if !e.isInstanceOf[VirtualMachineError] =>
                        val r = classify(s.kind, e)
                        logger.warn("Pipeline delete: could not remove " + s.label + " (" + describe(r) + "): " + e)
                        r
                }
            s -> o
        }

    private def describe(o: Outcome): String = o match {
        case VectorStoreDrop.Failed(r) => r
        case other => other.toString
    }

    /** The delete warning for data that could not be removed: the vector
      * store drop's sentences around `label`. */
    def warning(label: String, reason: String, configDeleted: Boolean): String =
        "Could not remove " + label + ": " + reason + ". " +
            // A drop that outlasted the timeout may still finish in the store.
            (if (reason == TimedOut) "The removal may still complete in the store; check it there. " else "") +
            (if (configDeleted) "Its pipeline configuration was deleted; remove it in the store."
             else "Run the reset again once the store is available.")

    // ------------------------------------------------------------ bounds

    /** Postgres / pgvector connection properties: the credentials and the
      * probe's connect, login and socket timeouts. */
    def jdbcProperties(user: String, password: String): java.util.Properties = {
        val p = new java.util.Properties()
        p.setProperty("user", if (user == null) "" else user)
        p.setProperty("password", if (password == null) "" else password)
        p.setProperty("connectTimeout", TimeoutSeconds.toString)
        p.setProperty("loginTimeout", TimeoutSeconds.toString)
        p.setProperty("socketTimeout", TimeoutSeconds.toString)
        p
    }

    private def transactional(kind: String): Boolean = kind == "postgres" || kind == "pgvector"

    /** Runs the drop statement `sql` on `conn` with a [[TimeoutSeconds]]
      * query timeout; never throws. Postgres and pgvector: inside an explicit
      * transaction with `SET LOCAL statement_timeout`, committed only when the
      * drop answered, so a drop the bound cut off is rolled back by the server
      * rather than completed later. Removed when the statement ran (a
      * `DROP ... IF EXISTS` of a missing table included); otherwise the
      * classified failure (detail logged). */
    def execute(kind: String, conn: Connection, sql: String): Outcome =
        try {
            val st = conn.createStatement()
            try {
                Try(st.setQueryTimeout(TimeoutSeconds))
                if (transactional(kind)) {
                    val began = Try(conn.setAutoCommit(false)).isSuccess && !Try(conn.getAutoCommit).getOrElse(true)
                    try {
                        st.execute("SET LOCAL statement_timeout = " + (TimeoutSeconds * 1000L))
                        st.execute(sql)
                        if (began) conn.commit()
                    } catch {
                        case e: Throwable =>
                            if (began) Try(conn.rollback())
                            throw e
                    }
                } else st.execute(sql)
                Removed
            } finally Try(st.close())
        } catch {
            case NonFatal(e) =>
                val r = classify(kind, e)
                logger.warn("Pipeline delete: " + kind + " drop failed (" + describe(r) + "): " + e)
                r
        }

    /** The MongoDB drop client's settings: the connection string, then the
      * probe's 30 s server selection, connect and read timeouts. */
    def mongoSettings(connectionString: String): com.mongodb.MongoClientSettings =
        com.mongodb.MongoClientSettings
            .builder()
            .applyConnectionString(new com.mongodb.ConnectionString(connectionString))
            .applyToClusterSettings(b => { b.serverSelectionTimeout(TimeoutSeconds.toLong, TimeUnit.SECONDS); () })
            .applyToSocketSettings(b => {
                b.connectTimeout(TimeoutSeconds, TimeUnit.SECONDS)
                b.readTimeout(TimeoutSeconds, TimeUnit.SECONDS)
                ()
            })
            .build()

    private def blank(s: String): Boolean = s == null || s.trim.isEmpty

    /** Where the pgvector drop connects: the URL the loader and the probe
      * use (`DestinationProbe.pgvectorJdbcUrl`) and the bounded properties
      * (username defaults to `postgres`, as the loader). Left "not
      * configured" for no secret or a secret without `jdbcUrl`. */
    def pgvectorJdbc(
        secret: Option[java.util.Map[String, String]],
        tenantDatabase: Option[String]
    ): Either[Outcome, (String, java.util.Properties)] =
        secret.filter(_ != null) match {
            case None => Left(Failed(NotConfigured))
            case Some(m) =>
                val raw = m.get("jdbcUrl")
                if (blank(raw)) Left(Failed(NotConfigured))
                else
                    Right(
                        (
                            DestinationProbe.pgvectorJdbcUrl(raw, tenantDatabase),
                            jdbcProperties(Option(m.get("username")).getOrElse("postgres"), Option(m.get("password")).getOrElse(""))
                        )
                    )
        }

    // ------------------------------------------------------------ classify

    private def causes(t: Throwable): Seq[Throwable] = {
        val out = Seq.newBuilder[Throwable]
        val seen = new java.util.IdentityHashMap[Throwable, java.lang.Boolean]()
        var c = t
        var n = 0
        while (c != null && n < 16 && !seen.containsKey(c)) {
            seen.put(c, java.lang.Boolean.TRUE)
            out += c
            c = c.getCause
            n += 1
        }
        out.result()
    }

    /** What a failed removal of `kind` tells the caller: walks `e` and its
      * causes, outermost first, and the first one recognised decides;
      * nothing recognised is "the store reported an error". Pure. Only
      * exception classes, SQLSTATEs, vendor codes and the error classes and
      * fixed texts the platform's own helpers use are read; nothing of the
      * message reaches the outcome. */
    def classify(kind: String, e: Throwable): Outcome =
        causes(e).iterator.map(t => one(if (kind == null) "" else kind.toLowerCase(Locale.ROOT), t)).collectFirst { case Some(o) => o }.getOrElse(
            Failed(ReportedError)
        )

    private def one(kind: String, t: Throwable): Option[Outcome] =
        t match {
            case d: DatrisException => datrisText(kind, Option(d.getMessage).getOrElse(""))
            case _ =>
                val specific = kind match {
                    case "postgres" | "pgvector" => sql(t).flatMap(postgres)
                    case "snowflake" => sql(t).flatMap(snowflake)
                    case "sqlserver" => sql(t).flatMap(sqlServer)
                    case "databricks" => sql(t).flatMap(databricks)
                    case "mongodb" => mongo(t)
                    case "objectstore" | "scratch" => objectStore(t)
                    case _ => None
                }
                specific.orElse(generic(t))
        }

    private def sql(t: Throwable): Option[SQLException] = t match {
        case s: SQLException => Some(s)
        case _ => None
    }

    private def state(s: SQLException): String = Option(s.getSQLState).getOrElse("")

    /** SQLSTATE classes most drivers share. */
    private def standardState(s: SQLException): Option[Outcome] = {
        val st = state(s)
        if (s.isInstanceOf[SQLTimeoutException] || st == "57014") Some(Failed(TimedOut))
        else if (st.startsWith("08")) Some(Failed(Unreachable))
        else if (st.startsWith("28")) Some(Failed(CredentialsRefused))
        else if (st == "42501") Some(Failed(PermissionRefused))
        else None
    }

    private def postgres(s: SQLException): Option[Outcome] =
        state(s) match {
            case "55P03" => Some(Failed(TimedOut))
            case "3D000" | "3F000" => Some(Absent)
            case _ => standardState(s)
        }

    private def snowflake(s: SQLException): Option[Outcome] =
        s.getErrorCode match {
            case 390100 | 390144 => Some(Failed(CredentialsRefused))
            case 3001 => Some(Failed(PermissionRefused))
            case 604 => Some(Failed(TimedOut))
            // "does not exist or not authorized": the table may be there.
            case 2003 => Some(Failed(ReportedError))
            case _ => standardState(s)
        }

    private def sqlServer(s: SQLException): Option[Outcome] =
        s.getErrorCode match {
            case 18456 => Some(Failed(CredentialsRefused))
            case 229 | 262 | 3701 => Some(Failed(PermissionRefused))
            case 911 => Some(Absent)
            // "cannot open database": missing, or the login has no user there.
            case 4060 => Some(Failed(ReportedError))
            case _ if state(s) == "HY008" => Some(Failed(TimedOut))
            case _ => standardState(s)
        }

    private def databricks(s: SQLException): Option[Outcome] = {
        val m = Option(s.getMessage).getOrElse("").toUpperCase(Locale.ROOT)
        if (m.contains("INSUFFICIENT_PERMISSIONS") || m.contains("PERMISSION_DENIED")) Some(Failed(PermissionRefused))
        else if (m.contains("SCHEMA_NOT_FOUND") || m.contains("NO_SUCH_CATALOG") || m.contains("CATALOG_NOT_FOUND")) Some(Absent)
        else standardState(s)
    }

    private def mongo(t: Throwable): Option[Outcome] =
        t match {
            case _: com.mongodb.MongoSocketReadTimeoutException => Some(Failed(TimedOut))
            case _: com.mongodb.MongoExecutionTimeoutException => Some(Failed(TimedOut))
            // Server selection timed out: a stopped or unreachable server.
            case _: com.mongodb.MongoTimeoutException => Some(Failed(Unreachable))
            case _: com.mongodb.MongoSocketException => Some(Failed(Unreachable))
            case _: com.mongodb.MongoSecurityException => Some(Failed(CredentialsRefused))
            case c: com.mongodb.MongoCommandException =>
                c.getErrorCode match {
                    case 13 => Some(Failed(PermissionRefused))
                    case 18 => Some(Failed(CredentialsRefused))
                    case 26 => Some(Absent)
                    case 50 => Some(Failed(TimedOut))
                    case _ => Some(Failed(ReportedError))
                }
            case _ => None
        }

    private def objectStore(t: Throwable): Option[Outcome] =
        t match {
            // S3A cannot tell refused credentials from a denied action.
            case _: java.nio.file.AccessDeniedException => Some(Failed(PermissionRefused))
            case _: java.io.FileNotFoundException => Some(Absent)
            case _: org.apache.hadoop.fs.s3a.UnknownStoreException => Some(Absent)
            case _ => None
        }

    private def generic(t: Throwable): Option[Outcome] =
        t match {
            case _: SQLTimeoutException => Some(Failed(TimedOut))
            case _: java.net.SocketTimeoutException => Some(Failed(TimedOut))
            case _: java.net.ConnectException => Some(Failed(Unreachable))
            case _: java.net.UnknownHostException => Some(Failed(Unreachable))
            case _: java.net.NoRouteToHostException => Some(Failed(Unreachable))
            case _: java.net.PortUnreachableException => Some(Failed(Unreachable))
            case _ => None
        }

    /** The fixed texts of the platform's own exceptions (they carry no
      * cause): a missing or incomplete secret, and the connection helpers'
      * translated connect failures. */
    private def datrisText(kind: String, m: String): Option[Outcome] = {
        val lower = m.toLowerCase(Locale.ROOT)
        val notConfigured =
            (m.contains("Secrets Manager") && (m.contains("Could not retrieve") || m.contains("not found"))) ||
                (m.contains("credentialsSecret") && (m.contains("missing required field") || m.contains("requires a credentialsSecret") ||
                    m.contains("must contain either") || m.contains("unsupported authMethod") || m.contains("has an invalid"))) ||
                m.contains("is a platform secret and cannot be used") || m.startsWith("No SQL warehouse configured") ||
                m.startsWith("PostgreSQL secret not found") || m.startsWith("Unsupported SQL Server authMethod")
        if (notConfigured) Some(Failed(NotConfigured))
        else
            kind match {
                case "snowflake" if m.startsWith("No Snowflake account answers") => Some(Failed(Unreachable))
                case "databricks" if m.startsWith("No Databricks workspace answers") => Some(Failed(Unreachable))
                case "databricks" if lower.contains("authentication failed") => Some(Failed(CredentialsRefused))
                case "databricks" if m.startsWith("Databricks connection timed out") => Some(Failed(TimedOut))
                case "databricks" if m.startsWith("Databricks connection failed") => Some(Failed(Unreachable))
                case "sqlserver" if m.startsWith("Cannot reach SQL Server") => Some(Failed(Unreachable))
                case "sqlserver" if lower.contains("rejected the login") || lower.contains("login failed for") || lower.contains("sign-in failed") =>
                    Some(Failed(CredentialsRefused))
                case _ => None
            }
    }
}
