package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.Gson
import ai.datris.model.{CsvAttributes, DatrisEnvironment, DatrisException, Notification, SchemaField, StagedFormat}
import ai.datris.model.{JobContext, PipelineConfig}
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Row, SaveMode, SparkSession}
import org.slf4j.{Logger, LoggerFactory}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import scala.collection.JavaConverters._
import scala.util.Try

object SparkObjectStoreLoader {
    private val writeLocks = new ConcurrentHashMap[String, ReentrantLock]()

    /** Serialise writes to one objectStore destination within this JVM. Runs
      *  of a single pipeline can overlap (ScheduledBatchTasks.startJobs only
      *  gates on destination.database.table), and two concurrent writers would
      *  race Iceberg's metadata commit or interleave parquet and ORC part files.
      *  `lockKey` is the write target — the loader passes the output path
      *  (bucket + prefix), so two pipelines pointed at the same prefix also
      *  serialise while unrelated destinations never wait on each other. */
    private[util] def withPipelineWriteLock[T](lockKey: String)(body: => T): T = {
        val lock = writeLocks.computeIfAbsent(lockKey, _ => new ReentrantLock())
        lock.lock()
        try body
        finally lock.unlock()
    }

    /** Read the staged delimited file with Spark's CSV reader under the
      *  destination schema (plans/stories/streaming-pipeline-phase2.md, step 7).
      *  Replaces `parallelize(rows.map(split + castValue))`: the payload no
      *  longer passes through the driver heap. Spark runs in-process
      *  (SparkSessionManager builds a local[*] session), so the run's staged
      *  file is readable by every task through `file://`.
      *
      *  Semantics kept from the old path: header-less input, the configured
      *  delimiter, values trimmed, an empty value is null, and a value that
      *  does not cast to its column type fails the load (FAILFAST, as
      *  `castValue`'s exception did). RFC 4180 quoting (`""` escapes a quote,
      *  multi-line quoted values) is honoured, which the old `split` was not:
      *  a quoted value containing the delimiter now parses as one column. */
    private[util] def stagedDataFrame(spark: SparkSession, stagedPath: String, schemaFields: List[SchemaField], delimiter: String): DataFrame = {
        val schema = buildSchema(schemaFields)
        if (stagedPath == null)
            return spark.createDataFrame(spark.sparkContext.emptyRDD[Row], schema)
        val uri = java.nio.file.Paths.get(stagedPath).toAbsolutePath.toUri.toString // file:///...
        spark.read
            .schema(schema)
            .option("header", "false")
            .option("delimiter", delimiter)
            .option("quote", "\"")
            .option("escape", "\"")
            .option("multiLine", "true")
            .option("ignoreLeadingWhiteSpace", "true")
            .option("ignoreTrailingWhiteSpace", "true")
            .option("nullValue", "")
            .option("emptyValue", "")
            .option("mode", "FAILFAST")
            .csv(uri)
    }

    /** The staged file as a DataFrame laid out as the destination schema.
      *
      *  The staged rows are in `header` order. When that is the destination
      *  schema's own order (or there is no header) the file is read by position,
      *  as before. When a transformation added, dropped or reordered columns
      *  and every header column is a destination column, the file is read in
      *  header order and each value lands in the destination column of the same
      *  name; a destination column the rows do not carry is null. A header
      *  naming a column the destination does not have keeps the positional read. */
    private[util] def stagedDataFrameFor(
        spark: SparkSession,
        stagedPath: String,
        destFields: List[SchemaField],
        header: List[String],
        delimiter: String
    ): DataFrame = {
        if (!mapsByName(destFields, header)) return stagedDataFrame(spark, stagedPath, destFields, delimiter)
        val headerKeys = header.map(columnKey)
        val byName = destFields.map(f => columnKey(f.name) -> f).toMap
        val read = stagedDataFrame(spark, stagedPath, headerKeys.map(byName), delimiter)
        val present = headerKeys.toSet
        val columns = buildSchema(destFields).fields.map { f =>
            if (present.contains(columnKey(f.name))) read.col(IcebergWriter.quote(f.name))
            else org.apache.spark.sql.functions.lit(null).cast(f.dataType).as(f.name)
        }
        read.select(columns: _*)
    }

    private def columnKey(n: String): String = if (n == null) "" else n.toLowerCase

    /** True when [[stagedDataFrameFor]] reads the rows by name (header order)
      * rather than by position. */
    private def mapsByName(destFields: List[SchemaField], header: List[String]): Boolean = {
        val destKeys = destFields.map(f => columnKey(f.name))
        val headerKeys = Option(header).getOrElse(Nil).map(columnKey)
        val dest = destKeys.toSet
        headerKeys.nonEmpty && headerKeys != destKeys && headerKeys.distinct.size == headerKeys.size && headerKeys.forall(dest.contains)
    }

    /** The destination columns [[stagedDataFrameFor]] fills with null because
      * the rows (`header`) do not carry them, in destination order. Empty when
      * the rows are read by position. */
    private[util] def nullFilledColumns(destFields: List[SchemaField], header: List[String]): List[String] =
        if (!mapsByName(destFields, header)) Nil
        else {
            val present = header.map(columnKey).toSet
            destFields.map(_.name).filterNot(n => present.contains(columnKey(n)))
        }

    private def norm(s: String): String = Option(s).map(_.trim.toLowerCase).getOrElse("")

    private def namesOf(l: java.util.List[String]): List[String] =
        Option(l).map(_.asScala.toList.filter(n => n != null && n.trim.nonEmpty).map(_.trim)).getOrElse(Nil)

    /** The refusal for rows without a merge key or partition column: `filled`
      * are the ones the load would fill with null, `absent` the ones the rows
      * to write do not have at all (field protection `drop` removed them). */
    private[util] def requiredColumnRefusal(
        kind: String,
        filled: Seq[String],
        absent: Seq[String],
        rowColumns: Seq[String],
        destColumns: Seq[String]
    ): DatrisException = {
        val what =
            if (filled.nonEmpty)
                "The rows to load are missing required " + kind + ": " + filled.mkString(", ") + ", which cannot be loaded as null. Row columns: " +
                    rowColumns.mkString(", ") + ". Destination columns: " + destColumns.mkString(", ")
            else
                "The rows to load do not carry required " + kind + ": " + absent.mkString(", ") +
                    " (removed before the load, for example by field protection drop), so they cannot be written. Row columns: " + rowColumns.mkString(", ")
        new DatrisException(what + ". Nothing was written to the object store")
    }

    /** Refuses, before the write lock and deleteBeforeWrite, rows that would
      * load a null Iceberg merge key (a null key never matches, so every row
      * would be inserted again) or a null partition column, whatever produced
      * the header (the source file, a transformation, a REST preprocessor), and
      * rows that do not carry such a column at all (field protection `drop`).
      * Null-filling is examined only for the columns the by-name read would
      * fill, and never for a payload of `rowCount` 0, so a positional read and
      * rows that carry every key and partition column are never refused.
      *
      * Partition columns are checked here for Parquet and ORC, and for Iceberg
      * only with deleteBeforeWrite (the table is rebuilt from the config);
      * otherwise IcebergWriter checks the existing table's own partition spec.
      * Parquet and ORC writeMode ignore without deleteBeforeWrite writes nothing
      * when the path exists, so its null-fill check waits for
      * [[refuseIgnoreWrite]] under the lock (a dropped column fails in Spark
      * in every mode, so it is refused here). */
    private[util] def refuseNullFilledRequired(config: PipelineConfig, destFields: List[SchemaField], header: List[String], rowCount: Long): Unit = {
        val os = Option(config.destination).flatMap(d => Option(d.objectStore))
        if (os.isEmpty) return
        val o = os.get
        val format = Option(o.fileFormat).map(norm).filter(_.nonEmpty).getOrElse("parquet")
        val mode = Option(o.writeMode).map(norm).filter(_.nonEmpty).getOrElse("append")
        val keys = if (format == "iceberg" && mode == "merge") namesOf(o.keyFields) else Nil
        val partitionsHere = if (format != "iceberg" || o.deleteBeforeWrite) namesOf(o.partitionBy) else Nil
        val deferFill = format != "iceberg" && mode == "ignore" && !o.deleteBeforeWrite
        val dest = destFields.map(f => norm(f.name)).toSet
        val filled = (if (rowCount > 0) nullFilledColumns(destFields, header) else Nil).map(norm).toSet
        val rowColumns = Option(header).getOrElse(Nil)
        def check(kind: String, cols: List[String], fill: Boolean): Unit = {
            val absent = cols.filterNot(c => dest.contains(norm(c)))
            val nulls = if (fill) destFields.map(_.name).filter(n => filled.contains(norm(n)) && cols.exists(c => norm(c) == norm(n))) else Nil
            if (nulls.nonEmpty || absent.nonEmpty) throw requiredColumnRefusal(kind, nulls, absent, rowColumns, destFields.map(_.name))
        }
        check("key field(s)", keys, fill = true)
        check("partition column(s)", partitionsHere, fill = !deferFill)
    }

    /** Parquet and ORC writeMode ignore without deleteBeforeWrite, under the write
      * lock: Spark writes nothing when the path exists, so null-filled rows are
      * refused only when it does not. A check that cannot be answered leaves
      * the write as it was (Spark makes the same check itself). */
    private[util] def refuseIgnoreWrite(
        config: PipelineConfig,
        destFields: List[SchemaField],
        header: List[String],
        rowCount: Long,
        pathExists: () => Boolean
    ): Unit = {
        val o = config.destination.objectStore
        if (rowCount <= 0 || o.deleteBeforeWrite || Option(o.writeMode).map(norm).getOrElse("") != "ignore") return
        val partitions = namesOf(o.partitionBy).map(norm).toSet
        val nulls = nullFilledColumns(destFields, header).filter(n => partitions.contains(norm(n)))
        if (nulls.nonEmpty && !Try(pathExists()).getOrElse(true))
            throw requiredColumnRefusal("partition column(s)", nulls, Nil, Option(header).getOrElse(Nil), destFields.map(_.name))
    }

    /** `df` with exactly `schema`'s columns, in its order. Each name is
      * backtick-quoted (IcebergWriter.quote) before Spark resolves it, so a
      * dot, a space or a backtick in a name is part of the name, never a
      * nested-field path. */
    private[util] def projectToSchema(df: DataFrame, schema: StructType): DataFrame =
        df.select(schema.fieldNames.map(n => df.col(IcebergWriter.quote(n))): _*)

    private[util] def buildSchema(fields: List[SchemaField]): StructType = {
        StructType(fields.map(field => {
            val dataType = field.`type`.toLowerCase match {
                case "boolean" => BooleanType
                case "int" | "integer" => IntegerType
                case "tinyint" => ByteType
                case "smallint" => ShortType
                case "bigint" => LongType
                case "float" => FloatType
                case "double" => DoubleType
                case t if t.startsWith("decimal(") => {
                    val params = t.stripPrefix("decimal(").stripSuffix(")").split(",").map(_.trim.toInt)
                    DecimalType(params(0), if (params.length > 1) params(1) else 0)
                }
                case "date" => DateType
                case "timestamp" => TimestampType
                case _ => StringType
            }
            StructField(field.name, dataType, nullable = true)
        }))
    }
}

class SparkObjectStoreLoader(jobContext: JobContext) {
    private val logger: Logger = LoggerFactory.getLogger(classOf[SparkObjectStoreLoader])
    private val config = jobContext.config
    private val statusUtil = jobContext.statusUtil

    def process(): Unit = {
        statusUtil.overrideProcessName(this.getClass.getSimpleName)
        statusUtil.info("begin", "Writing data to object store")

        // Build schema from pipeline config
        val schemaFields = config.destination.schemaProperties.fields.asScala.toList

        // Read the staged file directly (no driver-side row list). Only a
        // delimited payload has rows to write (the old `data.rows` was null —
        // and failed — for anything else).
        val staged = jobContext.data.staged
        val stagedPath =
            if (staged == null || staged.isEmpty) null
            else staged.format match {
                case StagedFormat.Delimited(_) => staged.path
                case other => throw new DatrisException("objectStore destination needs delimited rows; the payload is " + other)
            }

        // Rows without a merge key or partition column fail here, before the
        // write lock, the delete-before-write or any object-store access. A
        // payload with no rows has nothing to null-fill and loads as before
        // (every delimited payload's rowCount is counted as it is staged).
        val rowCount = if (stagedPath != null) staged.rowCount else 0L
        SparkObjectStoreLoader.refuseNullFilledRequired(config, schemaFields, jobContext.data.header, rowCount)

        val spark = SparkSessionManager.getOrCreate()

        val objectStore = config.destination.objectStore
        val bucket = ObjectStoreSpark.resolveBucket(objectStore)
        val prefixKey = objectStore.prefixKey
        val outputPath = "s3a://" + bucket + "/" + prefixKey

        ObjectStoreSpark.applyPerBucketConfig(spark, bucket, objectStore)

        val sparkSchema = SparkObjectStoreLoader.buildSchema(schemaFields)

        val delimiter = CsvAttributes.delimiterOf(config)

        val df = SparkObjectStoreLoader.stagedDataFrameFor(spark, stagedPath, schemaFields, jobContext.data.header, delimiter)

        // Determine file format (default parquet)
        val fileFormat = {
            if (config.destination.objectStore.fileFormat != null)
                config.destination.objectStore.fileFormat.trim.toLowerCase
            else
                "parquet"
        }

        // Determine write mode (default append). The validator lowercases only
        // for its own comparison; the persisted value keeps the caller's case.
        val writeModeName = Option(config.destination.objectStore.writeMode).map(_.trim.toLowerCase).filter(_.nonEmpty).getOrElse("append")
        val partitions = Option(config.destination.objectStore.partitionBy).map(_.asScala.toList).getOrElse(Nil)

        // Runs of one pipeline can overlap, so the whole write (including the
        // delete-before-write) runs under the per-destination lock, keyed on
        // the output path so two pipelines sharing a prefix serialise too.
        // Iceberg commits would otherwise race on metadata; parquet and ORC part
        // files would interleave.
        var restPlan: IcebergRestSession.Plan = IcebergRestSession.Inactive
        val iceberg: Option[IcebergWriter.WriteResult] = SparkObjectStoreLoader.withPipelineWriteLock(outputPath) {
            // catalogMode rest (IcebergRestSession): decided BEFORE the
            // delete-before-write, which it refuses on a table the catalog
            // holds (for any format: a parquet flip must not delete it either). Inactive for every other pipeline (path write). Throws
            // only when a path write would fork a catalog-committed table.
            if (fileFormat == "iceberg" || objectStore.deleteBeforeWrite)
                restPlan = IcebergRestSession.prepare(jobContext, outputPath, objectStore.deleteBeforeWrite)
            // Delete existing data if requested. Route through the Hadoop FileSystem
            // (S3A) rather than the MinIO Java SDK, so it honors the per-bucket config
            // we just applied and works for both MinIO and AWS S3. Using the MinIO SDK
            // here would always hit the global MinIO endpoint — wrong for S3 buckets.
            // For Iceberg this is a recursive prefix delete too: it drops the whole
            // table (metadata/ and data/), not just the current snapshot's rows.
            if (objectStore.deleteBeforeWrite) {
                statusUtil.info("processing", "Deleting existing data at: " + outputPath)
                try {
                    val path = new org.apache.hadoop.fs.Path(outputPath)
                    val fs = path.getFileSystem(spark.sparkContext.hadoopConfiguration)
                    if (fs.exists(path)) fs.delete(path, true)
                } catch {
                    case e: Exception => logger.info("No existing data to delete at: " + outputPath + " (" + e.getMessage + ")")
                }
            }

            if (fileFormat == "iceberg") {
                // writeToTemporaryLocation is ignored here: the validator rejects it
                // for Iceberg, whose commits are already atomic. keyFields are
                // persisted lowercased; IcebergWriter resolves them against the
                // table schema case-insensitively. An empty list means absent.
                val keyFields = Option(config.destination.objectStore.keyFields).map(_.asScala.toList.filter(k => k != null && k.trim.nonEmpty)).getOrElse(Nil)
                if (partitions.nonEmpty) statusUtil.info("processing", "Partitioning by: " + partitions.mkString(", "))
                statusUtil.info(
                    "processing",
                    "Writing iceberg (" + writeModeName + ") to: " +
                        (if (restPlan.managed) restPlan.qualified + " through Unity Catalog (catalogMode managed)" else outputPath)
                )
                // MERGE uses UPDATE SET * / INSERT *, so the source must carry
                // exactly the dest schema's columns in its order.
                val projected = SparkObjectStoreLoader.projectToSchema(df, sparkSchema)
                // IcebergWriter checks these against the table's partition spec
                // (or, for a new table, the config's partitionBy).
                val nullFilled = if (rowCount > 0) SparkObjectStoreLoader.nullFilledColumns(schemaFields, jobContext.data.header) else Nil
                def writeIceberg(): IcebergWriter.WriteResult =
                    IcebergWriter.write(
                        projected,
                        outputPath,
                        writeModeName,
                        partitions,
                        keyFields,
                        sparkSchema,
                        statusUtil,
                        config.name,
                        restPlan.target,
                        nullFilled
                    )
                try Some(writeIceberg())
                catch {
                    // Lost a create race for the identifier: re-decide once
                    // (ours ⇒ retry through the catalog; foreign ⇒ path write).
                    case e: org.apache.iceberg.exceptions.AlreadyExistsException if restPlan.created =>
                        restPlan = IcebergRestSession.afterCreateConflict(jobContext, restPlan, e)
                        try Some(writeIceberg())
                        catch {
                            case r: IcebergWriter.RestLocationRefused if restPlan.target.isDefined =>
                                // catalogMode managed: afterLocationRefused fails
                                // the run (never a path write).
                                restPlan = IcebergRestSession.afterLocationRefused(jobContext, restPlan, r)
                                Some(writeIceberg())
                        }
                    // The catalog's table is outside our root (a managed
                    // table): nothing was written through it; write by path.
                    // catalogMode managed never falls back: the table must be
                    // in the pipeline's bucket, else afterLocationRefused
                    // records it, audits and fails the run.
                    case r: IcebergWriter.RestLocationRefused if restPlan.target.isDefined =>
                        restPlan = IcebergRestSession.afterLocationRefused(jobContext, restPlan, r)
                        Some(writeIceberg())
                } finally restPlan.close()
            } else {
                val writeMode = writeModeName match {
                    case "overwrite" => SaveMode.Overwrite
                    case "ignore" => SaveMode.Ignore
                    case "errorifexists" => SaveMode.ErrorIfExists
                    case _ => SaveMode.Append
                }

                // Write with optional partitioning
                SparkObjectStoreLoader.refuseIgnoreWrite(
                    config,
                    schemaFields,
                    jobContext.data.header,
                    rowCount,
                    () => {
                        val path = new org.apache.hadoop.fs.Path(outputPath)
                        path.getFileSystem(spark.sparkContext.hadoopConfiguration).exists(path)
                    }
                )
                val writer = df.write.mode(writeMode)
                val partitionedWriter = {
                    if (partitions.nonEmpty) {
                        statusUtil.info("processing", "Partitioning by: " + partitions.mkString(", "))
                        writer.partitionBy(partitions: _*)
                    } else {
                        writer
                    }
                }

                statusUtil.info("processing", "Writing " + fileFormat + " to: " + outputPath)
                partitionedWriter.format(fileFormat).save(outputPath)
                None
            }
        }

        // A WriteResult with snapshotId -1 means nothing was committed (writeMode
        // ignore on an empty table): no snapshot to report.
        val snapshotId: Option[Long] = iceberg.map(_.snapshotId).filter(_ >= 0L)
        // catalogMode managed: the table lives where the catalog put it.
        val notifyPath = iceberg match {
            case Some(r) if restPlan.managed && r.metadataLocation != null => IcebergRestSession.tableLocationOf(r.metadataLocation)
            case _ => outputPath
        }
        sendNotification(notifyPath, snapshotId)
        // Unity Catalog registration (opt-in) runs before the `end` line:
        // nothing may follow it. A no-op unless the pipeline opted in; never
        // throws. In catalogMode rest and managed the commit already went
        // through the catalog, so the register hook is skipped and the REST
        // state recorded.
        if (
            config.unityCatalog != null && config.unityCatalog.enabled && config.unityCatalog.registerOn && fileFormat == "iceberg" &&
            !config.unityCatalog.throughCatalog
        )
            iceberg.filter(_.snapshotId >= 0L).foreach(r => IcebergCatalogRegistrar.sync(jobContext, r))
        IcebergRestSession.record(jobContext, iceberg, restPlan)
        // catalogMode managed: the rows live at the catalog-chosen location,
        // not under prefixKey.
        val writtenTo = iceberg match {
            case Some(r) if restPlan.managed && r.metadataLocation != null =>
                IcebergRestSession.tableLocationOf(r.metadataLocation) + " (" + restPlan.qualified + ", through Unity Catalog)"
            case _ => outputPath
        }
        iceberg match {
            case Some(r) if r.snapshotId >= 0L =>
                statusUtil.info(
                    "end",
                    "Process completed, wrote " + r.addedRecords + " rows to " + writtenTo +
                        ", snapshot " + r.snapshotId + " (deleted " + r.deletedRecords + ", total " + r.totalRecords + " rows)"
                )
            case _ =>
                statusUtil.info("end", "Process completed, wrote " + jobContext.data.rowCount + " rows to " + writtenTo)
        }
    }

    private def sendNotification(outputPath: String, snapshotId: Option[Long]): Unit = {
        val notification = Notification(
            config.name,
            jobContext.metadata.publisherToken,
            jobContext.pipelineToken,
            "objectStore",
            config.destination.objectStore.prefixKey,
            outputPath,
            null,
            null,
            null,
            null,
            null
        )
        val gson = new Gson
        val jsonNotification = gson.toJson(notification)

        val attributes = new java.util.HashMap[String, String]
        attributes.put("pipeline", config.name)
        attributes.put("destination", "objectStore")
        attributes.put("prefixKey", config.destination.objectStore.prefixKey)
        // Iceberg only: lets a downstream consumer pin its read to this commit.
        snapshotId.foreach(id => attributes.put("snapshotId", id.toString))

        NotificationUtil.add(DatrisEnvironment.current.pipelineTopic, jsonNotification, attributes.asScala.toMap)
        statusUtil.info("processing", "notification sent: " + jsonNotification)
    }
}
