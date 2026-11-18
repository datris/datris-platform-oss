package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Database, PipelineConfig}
import org.slf4j.{Logger, LoggerFactory}

import org.apache.hadoop.fs.{FileStatus, FileSystem, Path}

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
      * query (tables and views). Opened without ConnCatalog / ConnSchema. A
      * missing catalog (NO_SUCH_CATALOG / CATALOG_NOT_FOUND) counts as "does
      * not exist". A catalog without an information_schema (`hive_metastore`,
      * `spark_catalog`: TABLE_OR_VIEW_NOT_FOUND on the view itself) falls back
      * to `DESCRIBE TABLE <catalog>.<schema>.<table>`, quoted identifiers only:
      * it answers for whatever the preview's `SELECT *` would resolve, and a
      * not-found table, schema or catalog there counts as "does not exist". */
    private def databricks(db: Database): Boolean = {
        import DatabricksConnectionUtil.{effectiveName, ident}
        require(!blank(db.schema), "databricks destination without schema")
        val sql = "SELECT COUNT(*) FROM " + ident(db.dbName) + ".information_schema.tables WHERE lower(table_schema) = ? AND lower(table_name) = ?"
        val describe = "DESCRIBE TABLE " + ident(db.dbName) + "." + ident(db.schema) + "." + ident(db.table)
        try
            DatabricksConnectionUtil.withConnection(db.copy(dbName = null, schema = null)) { conn =>
                try countAboveZero(conn, sql, Seq(effectiveName(db.schema), effectiveName(db.table)))
                catch {
                    case e: SQLException if !databricksMissingCatalog(e) && databricksInformationSchemaMissing(e) =>
                        val st = conn.createStatement()
                        try {
                            try { Try(st.executeQuery(describe).close()); true }
                            catch { case x: SQLException if databricksObjectMissing(x) => false }
                        } finally Try(st.close())
                }
            }
        catch {
            case e: Exception if databricksMissingCatalog(e) =>
                logger.info("Destination probe: databricks catalog " + effectiveName(db.dbName) + " does not exist (or is not visible to these credentials)")
                false
        }
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
    private def sqlServer(db: Database): Boolean = {
        val sql = "SELECT COUNT(*) FROM sys.all_objects o JOIN sys.schemas s ON o.schema_id = s.schema_id WHERE s.name = ? AND o.name = ?"
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
        val path = new Path("s3a://" + bucket + "/" + prefix)
        // The FileSystem is the shared cached instance: never closed here.
        anyObjectUnder(path.getFileSystem(spark.sparkContext.hadoopConfiguration), path)
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
}
