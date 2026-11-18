package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Database, PipelineConfig}
import org.slf4j.{Logger, LoggerFactory}

import java.io.FileNotFoundException
import java.sql.{Connection, SQLException}
import scala.util.Try

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

    def exists(c: PipelineConfig, t: DestinationTargets.Target): Boolean = {
        require(c != null && c.destination != null && t != null, "no destination to probe")
        t.key.family match {
            case "snowflake" => snowflake(database(c))
            case "databricks" => databricks(database(c))
            case "sqlserver" => sqlServer(database(c))
            case "objectstore" => objectStore(c)
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
    private def countAboveZero(conn: Connection, sql: String, params: Seq[String]): Boolean = {
        val ps = conn.prepareStatement(sql)
        try {
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
    private def snowflake(db: Database): Boolean = {
        import SnowflakeConnectionUtil.{effectiveName, ident}
        require(!blank(db.schema), "snowflake destination without schema")
        val sql = "SELECT COUNT(*) FROM " + ident(db.dbName) + ".INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?"
        try
            SnowflakeConnectionUtil.withConnection(db.copy(dbName = null, schema = null)) { conn =>
                countAboveZero(conn, sql, Seq(effectiveName(db.schema), effectiveName(db.table)))
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
      * query. Opened without ConnCatalog / ConnSchema; a missing catalog
      * (its information_schema not found) counts as "does not exist". */
    private def databricks(db: Database): Boolean = {
        import DatabricksConnectionUtil.{effectiveName, ident}
        require(!blank(db.schema), "databricks destination without schema")
        val sql = "SELECT COUNT(*) FROM " + ident(db.dbName) + ".information_schema.tables WHERE lower(table_schema) = ? AND lower(table_name) = ?"
        try
            DatabricksConnectionUtil.withConnection(db.copy(dbName = null, schema = null)) { conn =>
                countAboveZero(conn, sql, Seq(effectiveName(db.schema), effectiveName(db.table)))
            }
        catch {
            case e: Exception if databricksMissingCatalog(e) =>
                logger.info("Destination probe: databricks catalog " + effectiveName(db.dbName) + " does not exist (or is not visible to these credentials)")
                false
        }
    }

    private[util] def databricksMissingCatalog(e: Throwable): Boolean =
        causes(e).exists {
            case s: SQLException =>
                val m = Option(s.getMessage).getOrElse("").toUpperCase(java.util.Locale.ROOT)
                m.contains("NO_SUCH_CATALOG") || m.contains("CATALOG_NOT_FOUND") || m.contains("TABLE_OR_VIEW_NOT_FOUND")
            case _ => false
        }

    // ------------------------------------------------------------ SQL Server

    /** The loader's `sys.tables JOIN sys.schemas` test. The connection opens
      * the destination database; a database the login cannot open (error
      * 4060: missing, or no user for the login there) counts as "does not
      * exist"; a missing schema simply matches nothing. */
    private def sqlServer(db: Database): Boolean = {
        val sql = "SELECT COUNT(*) FROM sys.tables t JOIN sys.schemas s ON t.schema_id = s.schema_id WHERE s.name = ? AND t.name = ?"
        try
            SqlServerConnectionUtil.withConnection(db) { conn =>
                countAboveZero(conn, sql, Seq(SqlServerConnectionUtil.schemaName(db), db.table))
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

    // ------------------------------------------------------------ object store

    /** Any object under `<bucket>/<prefix>/` (the whole bucket for an empty
      * prefix), listed through the Hadoop FileSystem with the per-bucket
      * settings applied, as reads and deletes do. A missing prefix or bucket
      * counts as "does not exist". */
    private def objectStore(c: PipelineConfig): Boolean = {
        val os = c.destination.objectStore
        require(os != null, "no object-store destination to probe")
        val bucket = ObjectStoreSpark.resolveBucket(os)
        val prefix = ObjectStoreSpark.normalizePrefix(os.prefixKey)
        val spark = SparkSessionManager.getOrCreate()
        ObjectStoreSpark.applyPerBucketConfig(spark, bucket, os)
        val path = new org.apache.hadoop.fs.Path("s3a://" + bucket + "/" + prefix)
        // The FileSystem is the shared cached instance: never closed here.
        val fs = path.getFileSystem(spark.sparkContext.hadoopConfiguration)
        try fs.listFiles(path, true).hasNext
        catch {
            case e: Exception if objectStoreMissing(e) => false
        }
    }

    private[util] def objectStoreMissing(e: Throwable): Boolean =
        causes(e).exists {
            case _: FileNotFoundException => true
            case x => x.getClass.getSimpleName == "UnknownStoreException"
        }
}
