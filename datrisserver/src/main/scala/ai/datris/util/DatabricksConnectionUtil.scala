package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.slf4j.{Logger, LoggerFactory}

import java.sql.{Connection, DriverManager}
import java.util.Properties
import scala.util.Try

/** Opens JDBC connections to the Databricks workspace a pipeline's `Database`
 *  destination points at, plus the identifier-emission rules that go with it.
 *  Shared by [[DatabricksLoader]] (writes) and [[DatabricksQueryUtil]] (reads)
 *  so the paste-shape normalizations and actionable errors live in one place.
 *
 *  Uses the Databricks OSS JDBC driver. Auth is OAuth M2M (service principal
 *  clientId/clientSecret) by default; a personal access token is the fallback.
 *  `db.dbName` is the Unity Catalog catalog; `db.warehouse` is the SQL
 *  warehouse ID (from Connection details), from which the httpPath derives —
 *  optional when the credentials secret has a `warehouse` field (see
 *  [[DatabricksWarehouse.effective]]). */
object DatabricksConnectionUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /** Resolve the pipeline's `credentialsSecret`, open a connection routed at
     *  the config's catalog/schema/warehouse, run `f`, and clean up. `onInfo`
     *  lets callers mirror progress into their own status log.
     *  `timeoutSeconds` (None for loaders and queries: driver defaults) sets
     *  the driver's `socketTimeout` (seconds; the name databricks-jdbc 3.4.1 declares in
     *  `DatabricksJdbcUrlParams`, looked up lower-cased).
     *  `warehouseStartSeconds` (None everywhere but the pipeline delete)
     *  bounds how long the driver keeps retrying a warehouse that answers
     *  "temporarily unavailable" while it starts, and keeps the per-request
     *  socket timeout at least that long ([[timeoutProperties]]). */
    def withConnection[T](
        db: Database,
        onInfo: String => Unit = _ => (),
        pipelineName: String = null,
        timeoutSeconds: Option[Int] = None,
        warehouseStartSeconds: Option[Int] = None
    )(
        f: Connection => T
    ): T = {
        val creds = CredentialResolver.resolveDatabricks(db.credentialsSecret)
        // `warehouse` is optional on the destination when the secret carries one.
        val warehouse = DatabricksWarehouse.effective(db.warehouse, creds.extra, pipelineName, db.credentialsSecret) match {
            case Right(w) => w
            case Left(message) => throw new DatrisException(message)
        }

        Class.forName("com.databricks.client.jdbc.Driver")

        var conn: Connection = null
        try {
            val httpPath = warehouseHttpPath(warehouse)
            val properties = new Properties()
            properties.setProperty("ssl", "1")
            properties.setProperty("httpPath", httpPath)
            if (db.dbName != null) properties.setProperty("ConnCatalog", db.dbName)
            if (db.schema != null) properties.setProperty("ConnSchema", db.schema)
            properties.setProperty("UserAgentEntry", "Datris")
            timeoutProperties(timeoutSeconds, warehouseStartSeconds).foreach { case (k, v) => properties.setProperty(k, v) }
            // Required for the driver's Unity Catalog volume operations (PUT/REMOVE):
            // it refuses to read local files outside this allowlist. Staging CSVs are
            // written to the JVM temp dir.
            properties.setProperty("VolumeOperationAllowedLocalPaths", System.getProperty("java.io.tmpdir"))

            (creds.clientId, creds.clientSecret) match {
                case (Some(id), Some(secret)) =>
                    // OAuth M2M (service principal) — the path enterprise customers use.
                    properties.setProperty("AuthMech", "11")
                    properties.setProperty("Auth_Flow", "1")
                    properties.setProperty("OAuth2ClientId", id)
                    properties.setProperty("OAuth2Secret", secret)
                case _ =>
                    // Personal access token fallback.
                    properties.setProperty("AuthMech", "3")
                    properties.setProperty("UID", "token")
                    properties.setProperty("PWD", creds.token.get)
            }

            val host = normalizeHost(creds.host)
            val jdbcUrl = "jdbc:databricks://" + host + ":" + Port
            // The delete only: the driver uses one value for its connect and
            // socket timeouts, so with the long warehouse-start bound a dead
            // workspace host would wait that long; a plain TCP connect first
            // fails it within WorkspaceConnectSeconds.
            // Skipped behind a configured proxy (a direct connect may be refused there).
            if (warehouseStartSeconds.isDefined && !proxyConfigured) workspaceReachable(host, Port, jdbcUrl)
            logger.info("Databricks jdbc url: " + LogRedactUtil.redactJdbcUrl(jdbcUrl) + " httpPath: " + httpPath)
            onInfo("jdbc url: " + LogRedactUtil.redactJdbcUrl(jdbcUrl) + " httpPath: " + httpPath)
            // getConnection blocks while a stopped warehouse auto-starts — classic
            // warehouses can take minutes, serverless seconds. Say so up front, since
            // there is no driver callback to report it from.
            onInfo("Connecting — if the SQL warehouse is stopped it auto-starts now (serverless: seconds, classic: up to a few minutes)")
            conn =
                try {
                    DriverManager.getConnection(jdbcUrl, properties)
                } catch {
                    case e: Exception => throw translateConnectError(e, jdbcUrl, warehouse, creds)
                }
            onInfo("Databricks connection acquired")
            f(conn)
        } finally {
            if (conn != null) Try(conn.close())
        }
    }

    /** The port of the workspace's JDBC URL. */
    val Port = 443

    /** An HTTPS proxy configured for the JVM or the process: the pre-check
      * cannot tell a refused direct connect from a dead host there. */
    private[util] def proxyConfigured: Boolean =
        Seq(System.getProperty("https.proxyHost"), System.getenv("HTTPS_PROXY"), System.getenv("https_proxy")).exists(v => v != null && v.trim.nonEmpty)

    /** The delete's workspace pre-check connect bound, in seconds. */
    val WorkspaceConnectSeconds = 30

    /** Test seam: opens (and closes) a TCP connection to (host, port) within
      * the timeout in milliseconds, or throws. */
    @volatile private[util] var tcpConnect: (String, Int, Int) => Unit = (host, port, timeoutMs) => {
        val s = new java.net.Socket()
        try s.connect(new java.net.InetSocketAddress(host, port), timeoutMs)
        finally Try(s.close())
    }

    /** The delete's pre-check: the workspace accepts a TCP connection on
      * `port` within [[WorkspaceConnectSeconds]]; otherwise the same "No
      * Databricks workspace answers at" error the driver's unknown-host
      * failure gives (the host is in the message, which only the log sees). */
    private[util] def workspaceReachable(host: String, port: Int, jdbcUrl: String): Unit =
        try tcpConnect(host, port, WorkspaceConnectSeconds * 1000)
        catch {
            case e: Exception =>
                logger.warn("Databricks workspace pre-check failed for " + LogRedactUtil.redactJdbcUrl(jdbcUrl) + ": " + e)
                val out = new DatrisException(
                    "No Databricks workspace answers at " + jdbcUrl + " within " + WorkspaceConnectSeconds + " seconds. " +
                        "The credentials secret's 'host' field must be the workspace hostname."
                )
                out.initCause(e)
                throw out
        }

    /** The driver timeout properties for `timeoutSeconds` and
     *  `warehouseStartSeconds` (databricks-jdbc 3.4.1, read from its
     *  `DatabricksJdbcUrlParams` and `DatabricksHttpClient`; not measured
     *  against a live warehouse). `socketTimeout` (seconds, default 900) is
     *  applied to every HTTP request as Apache HttpClient's connect, socket
     *  (read idle) and connection-request timeouts. A 503 "temporarily
     *  unavailable" answer (a warehouse starting) is retried with backoff for
     *  up to `TemporarilyUnavailableRetryTimeout` seconds (default 900); a
     *  socket timeout is not retried. So with `warehouseStartSeconds` the
     *  retry window is that bound and the per-request socket timeout is
     *  raised to it too, so a start answered by one long request is not cut
     *  short by `timeoutSeconds`. Nothing set: driver defaults. */
    private[util] def timeoutProperties(timeoutSeconds: Option[Int], warehouseStartSeconds: Option[Int]): Seq[(String, String)] = {
        val socket = (timeoutSeconds.toSeq ++ warehouseStartSeconds.toSeq).reduceOption(_ max _)
        socket.map(s => "socketTimeout" -> s.toString).toSeq ++
            warehouseStartSeconds.map(s => "TemporarilyUnavailableRetryTimeout" -> s.toString).toSeq
    }

    /** Convert the driver's opaque connect-time failures into actionable errors
     *  naming the secret/config field to fix. */
    private[util] def translateConnectError(e: Exception, jdbcUrl: String, warehouse: String, creds: ResolvedDatabricksCredentials): DatrisException = {
        def causeChain(t: Throwable): List[Throwable] =
            if (t == null) Nil else t :: causeChain(t.getCause)
        val messages = causeChain(e).flatMap(t => Option(t.getMessage)).mkString(" | ")
        // Classify on the full text; the driver messages can carry the server's
        // stack dump, so only the first line of each (capped) is shown.
        val shown = DatabricksErrorText.cap(
            causeChain(e).flatMap(t => Option(t.getMessage)).map(DatabricksErrorText.firstLine).filter(_.nonEmpty).distinct.mkString(" | "),
            300
        )

        if (causeChain(e).exists(_.isInstanceOf[java.net.UnknownHostException]))
            new DatrisException("No Databricks workspace answers at " + jdbcUrl +
                ". The credentials secret's 'host' field must be the workspace hostname — e.g. " +
                "dbc-a1b2c3d4-e5f6.cloud.databricks.com or adb-1234567890123456.7.azuredatabricks.net — " +
                "copied from the workspace URL. Underlying driver error: " + shown)
        else if (messages.contains("RESOURCE_DOES_NOT_EXIST") || messages.toLowerCase.contains("invalid http path") || messages.contains("404"))
            new DatrisException("Databricks SQL warehouse '" + warehouse + "' was not found in this workspace. " +
                "'warehouse' must be the warehouse ID — SQL Warehouses → your warehouse → Connection details, " +
                "the trailing segment of the HTTP path (/sql/1.0/warehouses/<id>) — not the warehouse name. " +
                "Underlying driver error: " + shown)
        else if (messages.contains("invalid_client") || messages.contains("401") || messages.toLowerCase.contains("unauthorized"))
            new DatrisException((if (creds.clientId.isDefined)
                                     "Databricks OAuth M2M authentication failed — verify the secret's 'clientId'/'clientSecret', that the " +
                                         "service principal has been added to this workspace, and that its OAuth secret has not expired. "
                                 else
                                     "Databricks token authentication failed — verify the secret's 'token' is a current personal access token " +
                                         "for this workspace. ") + "Underlying driver error: " + shown)
        else if (messages.contains("503") || messages.toLowerCase.contains("timed out") || messages.toLowerCase.contains("timeout"))
            new DatrisException("Databricks connection timed out — the SQL warehouse may still be auto-starting " +
                "(classic warehouses can take several minutes; serverless starts in seconds). Retry once it is RUNNING. " +
                "Underlying driver error: " + shown)
        else if (messages.contains("Failed to connect to server") && warehouse != null && warehouse.trim.nonEmpty)
            new DatrisException("Databricks connection failed — check the warehouse ID/HTTP path ('" + warehouse.trim + "') " +
                "and that the service principal can use the warehouse. Underlying driver error: " + shown)
        else
            new DatrisException("Databricks connection failed: " + shown)
    }

    /** The secret's `host` field should be a bare workspace hostname, but the
     *  natural paste is the full workspace URL (with protocol and often a path
     *  suffix like /sql/1.0/warehouses/...). Accept any of the shapes: strip
     *  the protocol, any user@ prefix, anything after the first slash, and
     *  any :port. */
    def normalizeHost(raw: String): String =
        raw.trim
            .replaceFirst("(?i)^[a-z]+://", "")
            .replaceFirst("^[^/@]*@", "")
            .replaceFirst("[/?#].*$", "")
            .replaceFirst(":\\d+$", "")

    /** `Database.warehouse` holds the SQL warehouse ID, but Connection details
     *  hands out the full HTTP path (and some users paste the whole JDBC URL).
     *  Accept: a bare ID, an httpPath containing /warehouses/<id>, or a JDBC
     *  URL containing httpPath=...; emit the canonical httpPath. */
    def warehouseHttpPath(raw: String): String = {
        val trimmed = raw.trim
        val warehouseIdRe = "(?i)warehouses/([0-9a-f]+)".r
        warehouseIdRe.findFirstMatchIn(trimmed) match {
            case Some(m) => "/sql/1.0/warehouses/" + m.group(1).toLowerCase
            case None => "/sql/1.0/warehouses/" + trimmed.toLowerCase
        }
    }

    /** Emit a Databricks identifier. Simple names (letters/digits/underscore,
     *  not digit-leading — note: no `$`, unlike Snowflake) go unquoted;
     *  Databricks resolves them case-insensitively. Anything else is
     *  backtick-quoted. */
    def ident(identifier: String): String =
        if (isSimpleIdent(identifier)) identifier else quote(identifier)
    def isSimpleIdent(s: String): Boolean = s.matches("[A-Za-z_][A-Za-z0-9_]*")

    /** The name information_schema stores: Unity Catalog lowercases object
     *  names regardless of quoting, so compare lowercase on both sides. */
    def effectiveName(s: String): String = s.toLowerCase
    def quote(identifier: String): String = "`" + identifier.replace("`", "``") + "`"

    /** Escape a value for a single-quoted SQL string literal (quotes doubled).
      * Shared by the loader and UnityCatalogMetadataSync. */
    def sqlLiteral(value: String): String = value.replace("'", "''")

    def qualifiedTable(db: Database): String =
        ident(db.dbName) + "." + ident(db.schema) + "." + ident(db.table)
}
