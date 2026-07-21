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
 *  warehouse ID (from Connection details), from which the httpPath derives. */
object DatabricksConnectionUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /** Resolve the pipeline's `credentialsSecret`, open a connection routed at
     *  the config's catalog/schema/warehouse, run `f`, and clean up. `onInfo`
     *  lets callers mirror progress into their own status log. */
    def withConnection[T](db: Database, onInfo: String => Unit = _ => ())(f: Connection => T): T = {
        val creds = CredentialResolver.resolveDatabricks(db.credentialsSecret)

        Class.forName("com.databricks.client.jdbc.Driver")

        var conn: Connection = null
        try {
            val httpPath = warehouseHttpPath(db.warehouse)
            val properties = new Properties()
            properties.setProperty("ssl", "1")
            properties.setProperty("httpPath", httpPath)
            if (db.dbName != null) properties.setProperty("ConnCatalog", db.dbName)
            if (db.schema != null) properties.setProperty("ConnSchema", db.schema)
            properties.setProperty("UserAgentEntry", "Datris")
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

            val jdbcUrl = "jdbc:databricks://" + normalizeHost(creds.host) + ":443"
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
                    case e: Exception => throw translateConnectError(e, jdbcUrl, db.warehouse, creds)
                }
            onInfo("Databricks connection acquired")
            f(conn)
        } finally {
            if (conn != null) Try(conn.close())
        }
    }

    /** Convert the driver's opaque connect-time failures into actionable errors
     *  naming the secret/config field to fix. */
    private def translateConnectError(e: Exception, jdbcUrl: String, warehouse: String, creds: ResolvedDatabricksCredentials): DatrisException = {
        def causeChain(t: Throwable): List[Throwable] =
            if (t == null) Nil else t :: causeChain(t.getCause)
        val messages = causeChain(e).flatMap(t => Option(t.getMessage)).mkString(" | ")

        if (causeChain(e).exists(_.isInstanceOf[java.net.UnknownHostException]))
            new DatrisException("No Databricks workspace answers at " + jdbcUrl +
                ". The credentials secret's 'host' field must be the workspace hostname — e.g. " +
                "dbc-a1b2c3d4-e5f6.cloud.databricks.com or adb-1234567890123456.7.azuredatabricks.net — " +
                "copied from the workspace URL. Underlying driver error: " + messages)
        else if (messages.contains("RESOURCE_DOES_NOT_EXIST") || messages.toLowerCase.contains("invalid http path") || messages.contains("404"))
            new DatrisException("Databricks SQL warehouse '" + warehouse + "' was not found in this workspace. " +
                "'warehouse' must be the warehouse ID — SQL Warehouses → your warehouse → Connection details, " +
                "the trailing segment of the HTTP path (/sql/1.0/warehouses/<id>) — not the warehouse name. " +
                "Underlying driver error: " + messages)
        else if (messages.contains("invalid_client") || messages.contains("401") || messages.toLowerCase.contains("unauthorized"))
            new DatrisException((if (creds.clientId.isDefined)
                                     "Databricks OAuth M2M authentication failed — verify the secret's 'clientId'/'clientSecret', that the " +
                                         "service principal has been added to this workspace, and that its OAuth secret has not expired. "
                                 else
                                     "Databricks token authentication failed — verify the secret's 'token' is a current personal access token " +
                                         "for this workspace. ") + "Underlying driver error: " + messages)
        else if (messages.contains("503") || messages.toLowerCase.contains("timed out") || messages.toLowerCase.contains("timeout"))
            new DatrisException("Databricks connection timed out — the SQL warehouse may still be auto-starting " +
                "(classic warehouses can take several minutes; serverless starts in seconds). Retry once it is RUNNING. " +
                "Underlying driver error: " + messages)
        else
            new DatrisException("Databricks connection failed: " + messages)
    }

    /** The secret's `host` field should be a bare workspace hostname, but the
     *  natural paste is the full workspace URL (with protocol and often a path
     *  suffix like /sql/1.0/warehouses/...). Accept any of the shapes: strip
     *  the protocol, anything after the first slash, and any :port. */
    def normalizeHost(raw: String): String =
        raw.trim
            .replaceFirst("(?i)^[a-z]+://", "")
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

    def qualifiedTable(db: Database): String =
        ident(db.dbName) + "." + ident(db.schema) + "." + ident(db.table)
}
