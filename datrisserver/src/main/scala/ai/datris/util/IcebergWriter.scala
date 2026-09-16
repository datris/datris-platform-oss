package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.iceberg.hadoop.HadoopTables
import org.apache.iceberg.spark.{SparkCachedTableCatalog, SparkCatalog, SparkSchemaUtil, SparkTableCache}
import org.apache.iceberg.{PartitionSpec, Schema, Snapshot, Table, TableProperties}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.types.StructType
import org.slf4j.{Logger, LoggerFactory}

import java.util.UUID
import scala.collection.JavaConverters._

/** All Apache Iceberg write logic for objectStore destinations. Tables are
  *  addressed purely by path (`file://`, `s3a://`): no catalog registration,
  *  metadata lives at `<location>/metadata/`, data under `<location>/data/`.
  *
  *  Uses `df.sparkSession` rather than `SparkSessionManager.getOrCreate()` so
  *  it runs wherever the caller's session runs (including a plain local
  *  session in tests). The session must carry the Iceberg extension and the
  *  `datris` catalog that SparkSessionManager adds; `merge` needs both.
  */
object IcebergWriter {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /** Table property naming the pipeline that owns the table. */
    val PipelineProperty = "datris.pipeline"

    final case class WriteResult(snapshotId: Long, addedRecords: Long, deletedRecords: Long, totalRecords: Long)

    /** Write `df` to the Iceberg table at `location`, creating it if absent.
      *
      *  @param writeMode   append | overwrite | merge | ignore | errorifexists
      *  @param partitionBy identity partition columns; Nil = unpartitioned
      *  @param keyFields   MERGE ON columns; only read when writeMode == "merge"
      *  @param destSchema  pipeline dest schema; drives create + evolution
      */
    def write(
        df: DataFrame,
        location: String,
        writeMode: String,
        partitionBy: Seq[String],
        keyFields: Seq[String],
        destSchema: StructType,
        statusUtil: StatusUtil
    ): WriteResult = write(df, location, writeMode, partitionBy, keyFields, destSchema, statusUtil, pipelineName = null)

    /** As above, naming the owning pipeline for the `datris.pipeline` table
      *  property. Null falls back to the trailing path segment of `location`. */
    def write(
        df: DataFrame,
        location: String,
        writeMode: String,
        partitionBy: Seq[String],
        keyFields: Seq[String],
        destSchema: StructType,
        statusUtil: StatusUtil,
        pipelineName: String
    ): WriteResult = {
        val spark = df.sparkSession
        ensureCatalogs(spark, location)
        val conf = spark.sessionState.newHadoopConf()
        val tables = new HadoopTables(conf)
        val mode = Option(writeMode).map(_.trim.toLowerCase).filter(_.nonEmpty).getOrElse("append")

        if (!Modes.contains(mode))
            throw new DatrisException("Unsupported writeMode '" + writeMode + "' for an Iceberg destination; use one of " + Modes.mkString(", "))
        if (mode == "merge" && keyFields.isEmpty)
            throw new DatrisException("writeMode 'merge' requires keyFields (the MERGE ON columns)")

        val exists = tables.exists(location)
        if (!exists) guardPrefix(conf, location)

        if (exists && mode == "ignore") {
            val table = tables.load(location)
            statusUtil.info("processing", "Iceberg table exists at " + location + "; writeMode ignore, nothing written")
            return resultOf(table.currentSnapshot())
        }
        if (exists && mode == "errorifexists")
            throw new DatrisException("Iceberg table already exists at " + location + " and writeMode is errorifexists")

        var evolved: Seq[String] = Nil
        val table: Table =
            if (exists) {
                val t = tables.load(location)
                evolved = evolveSchema(t, destSchema, statusUtil)
                t
            } else
                createTable(tables, location, destSchema, partitionBy, pipelineName, statusUtil)

        try writeData(df, table, location, mode, exists, keyFields, statusUtil)
        catch {
            case e: Exception if evolved.nonEmpty =>
                // The schema change is its own committed metadata update; the
                // data write that followed is not. Say so rather than retry:
                // the next run finds the column already present and just appends.
                val wrapped = new DatrisException(
                    "Iceberg schema evolved at " + location + " (added column(s) " + evolved.mkString(", ") +
                        ") but the data write failed; the schema change is committed and the next run will only write data. Cause: " + e.getMessage
                )
                wrapped.initCause(e)
                throw wrapped
        }

        table.refresh()
        val snapshot = table.currentSnapshot()
        val result = resultOf(snapshot)
        statusUtil.info(
            "processing",
            "Iceberg commit at " + location + ": snapshot " + result.snapshotId +
                ", added " + result.addedRecords + ", deleted " + result.deletedRecords +
                ", total " + result.totalRecords + " rows"
        )
        result
    }

    private def writeData(
        df: DataFrame,
        table: Table,
        location: String,
        mode: String,
        exists: Boolean,
        keyFields: Seq[String],
        statusUtil: StatusUtil
    ): Unit =
        mode match {
            case "overwrite" if exists =>
                // One snapshot either way: dynamic partition overwrite replaces only
                // the partitions present in df; unpartitioned replaces all rows in
                // a single overwrite commit. No intermediate empty table.
                val writer = df.write.format("iceberg").mode("overwrite")
                val partitioned = table.spec().isPartitioned
                statusUtil.info("processing", "Iceberg " + (if (partitioned) "overwritePartitions" else "replace") + " at " + location)
                (if (partitioned) writer.option("overwrite-mode", "dynamic") else writer).save(location)

            case "merge" if exists =>
                merge(df, table, location, keyFields, statusUtil)

            case _ =>
                // append, plus any mode against a table that did not exist yet
                statusUtil.info("processing", "Iceberg append to " + location)
                df.write.format("iceberg").mode("append").save(location)
        }

    private val Modes = Seq("append", "overwrite", "merge", "ignore", "errorifexists")

    /** Name of the Iceberg catalog that `format("iceberg")` path reads/writes
      *  resolve through (IcebergSource registers it on first use). */
    val DefaultCatalog = "default_iceberg"

    /** Name of the cached-table catalog MERGE addresses a path-based table through. */
    val CacheCatalog = "datris_cache"

    /** Make sure the session can address Iceberg tables by path.
      *
      *  IcebergSource lazily registers `default_iceberg` as a `type=hive`
      *  catalog when absent; SparkCatalog then builds a HiveCatalog eagerly and
      *  fails on any `format("iceberg")` read or write when Hive metastore
      *  classes are not on the classpath (Datris ships spark-sql, not
      *  spark-hive). Path identifiers never consult the catalog's own store, so
      *  a hadoop-type definition with a placeholder warehouse is all that is
      *  needed. SparkSessionManager sets the same keys for the shared session;
      *  this covers sessions built elsewhere (tests). Both are no-ops when the
      *  keys already exist.
      */
    private[util] def ensureCatalogs(spark: SparkSession, location: String): Unit = {
        val prefix = "spark.sql.catalog." + DefaultCatalog
        if (spark.conf.getOption(prefix).isEmpty) {
            spark.conf.set(prefix, classOf[SparkCatalog].getName)
            spark.conf.set(prefix + ".type", "hadoop")
            spark.conf.set(prefix + ".warehouse", location)
            spark.conf.set(prefix + ".cache-enabled", "false")
        }
        val cache = "spark.sql.catalog." + CacheCatalog
        if (spark.conf.getOption(cache).isEmpty)
            spark.conf.set(cache, classOf[SparkCachedTableCatalog].getName)
    }

    /** Refuse to create a table over a prefix that already holds non-Iceberg
      *  objects (an existing non-iceberg pipeline output, for example). */
    private def guardPrefix(conf: Configuration, location: String): Unit = {
        val path = new Path(location)
        val fs = path.getFileSystem(conf)
        if (!fs.exists(path)) return
        val children = fs.listStatus(path)
        if (children.isEmpty) return
        if (children.exists(_.getPath.getName == "metadata"))
            throw new DatrisException(
                "prefix " + location + " has an Iceberg metadata/ directory but no loadable table (missing or corrupt version-hint); " +
                    "set deleteBeforeWrite or use a new prefix"
            )
        throw new DatrisException(
            "prefix contains non-Iceberg files at " + location + "; set deleteBeforeWrite or use a new prefix"
        )
    }

    private def createTable(
        tables: HadoopTables,
        location: String,
        destSchema: StructType,
        partitionBy: Seq[String],
        pipelineName: String,
        statusUtil: StatusUtil
    ): Table = {
        val schema: Schema = SparkSchemaUtil.convert(destSchema)
        val missing = partitionBy.filterNot(p => schema.findField(p) != null)
        if (missing.nonEmpty)
            throw new DatrisException("partitionBy column(s) not in dest schema: " + missing.mkString(", "))
        val spec = partitionBy.foldLeft(PartitionSpec.builderFor(schema))((b, p) => b.identity(p)).build()
        val props = Map(
            TableProperties.FORMAT_VERSION -> "2",
            TableProperties.DEFAULT_FILE_FORMAT -> "parquet",
            PipelineProperty -> Option(pipelineName).map(_.trim).filter(_.nonEmpty).getOrElse(pipelineNameFrom(location))
        ).asJava
        statusUtil.info(
            "processing",
            "Creating Iceberg table at " + location +
                (if (partitionBy.nonEmpty) " partitioned by " + partitionBy.mkString(", ") else "")
        )
        tables.create(schema, spec, props, location)
    }

    /** Owner name when the caller did not pass a pipeline name (the story-1
      *  signature): the trailing path segment of the table location. */
    private def pipelineNameFrom(location: String): String =
        location.stripSuffix("/").split('/').lastOption.filter(_.nonEmpty).getOrElse(location)

    /** Diff the dest schema against the table: add new nullable columns, refuse
      *  type changes, required additions and drops with a message that names
      *  the column. Nothing is committed when the diff is refused. Returns the
      *  names of the columns it added (empty when the schema already matched). */
    private def evolveSchema(table: Table, destSchema: StructType, statusUtil: StatusUtil): Seq[String] = {
        val current = table.schema()
        val destNames = destSchema.fields.map(_.name).toSet

        val dropped = current.columns().asScala.map(_.name()).filterNot(destNames.contains)
        if (dropped.nonEmpty)
            throw new DatrisException(
                "Iceberg schema evolution refused: column(s) " + dropped.mkString(", ") +
                    " exist in the table but not in the dest schema; dropping columns is not supported"
            )

        val typeChanges = destSchema.fields.toSeq.flatMap { f =>
            Option(current.findField(f.name)).flatMap { existing =>
                // Compare both sides as Iceberg sees them: Iceberg widens some
                // Spark types on create (ByteType/ShortType -> int), so the raw
                // dest type would read as a change on every write after the first.
                val have = SparkSchemaUtil.convert(existing.`type`()).catalogString
                val want = SparkSchemaUtil.convert(SparkSchemaUtil.convert(f.dataType)).catalogString
                if (have == want) None else Some(f.name + " " + have + " -> " + want)
            }
        }
        if (typeChanges.nonEmpty)
            throw new DatrisException(
                "Iceberg schema evolution refused: type change not supported for column(s) " + typeChanges.mkString("; ")
            )

        val added = destSchema.fields.toSeq.filter(f => current.findField(f.name) == null)
        val required = added.filterNot(_.nullable).map(_.name)
        if (required.nonEmpty)
            throw new DatrisException(
                "Iceberg schema evolution refused: new column(s) " + required.mkString(", ") +
                    " are not nullable; existing rows would have no value"
            )
        if (added.nonEmpty) {
            val update = table.updateSchema()
            added.foreach(f => update.addColumn(f.name, SparkSchemaUtil.convert(f.dataType)))
            update.commit()
            statusUtil.info("processing", "Iceberg schema evolved: added column(s) " + added.map(_.name).mkString(", "))
        }
        added.map(_.name)
    }

    private def merge(df: DataFrame, table: Table, location: String, keyFields: Seq[String], statusUtil: StatusUtil): Unit = {
        val spark = df.sparkSession
        // keyFields are persisted lowercased by the pipeline normaliser while
        // the table keeps the dest schema's spelling, so resolve them
        // case-insensitively and use the table's own column names in the ON.
        val resolved = keyFields.map(k => k -> Option(table.schema().caseInsensitiveFindField(k)).map(_.name()))
        val unknown = resolved.collect { case (k, None) => k }
        if (unknown.nonEmpty)
            throw new DatrisException("merge keyFields not in the Iceberg table: " + unknown.mkString(", "))
        val onColumns = resolved.collect { case (_, Some(name)) => name }

        // Spark SQL needs a catalog identifier for the MERGE target, and a
        // path-based table has none: SparkCatalog only path-resolves the
        // PathIdentifier that IcebergSource builds for format("iceberg"), not a
        // backticked path in SQL (verified on 1.11.0: TABLE_OR_VIEW_NOT_FOUND).
        // Iceberg's own Spark actions solve this with SparkTableCache + the
        // cached-table catalog: park the loaded Table under a one-off key and
        // address it as `datris_cache`.`<key>` for the statement.
        val key = "datris_merge_" + UUID.randomUUID().toString.replace("-", "")
        val source = key + "_src"
        SparkTableCache.get().add(key, table)
        df.createOrReplaceTempView(source)
        try {
            val target = CacheCatalog + "." + quote(key)
            val on = onColumns.map(k => "t." + quote(k) + " = s." + quote(k)).mkString(" AND ")
            val sql =
                "MERGE INTO " + target + " t USING " + source + " s ON " + on +
                    " WHEN MATCHED THEN UPDATE SET * WHEN NOT MATCHED THEN INSERT *"
            statusUtil.info("processing", "Iceberg merge into " + location + " on " + onColumns.mkString(", "))
            logger.debug("Iceberg merge SQL: " + sql)
            spark.sql(sql)
        } finally {
            spark.catalog.dropTempView(source)
            SparkTableCache.get().remove(key)
        }
    }

    private def resultOf(snapshot: Snapshot): WriteResult =
        if (snapshot == null) WriteResult(-1L, 0L, 0L, 0L)
        else {
            val summary = snapshot.summary()
            def count(key: String): Long = Option(summary.get(key)).map(_.toLong).getOrElse(0L)
            WriteResult(snapshot.snapshotId(), count("added-records"), count("deleted-records"), count("total-records"))
        }

    private def quote(name: String): String = "`" + name.replace("`", "``") + "`"
}
