package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.Gson
import ai.datris.model.{DatrisEnvironment, DatrisException, Notification, SchemaField, StagedFormat}
import ai.datris.model.JobContext
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Row, SaveMode, SparkSession}
import org.slf4j.{Logger, LoggerFactory}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import scala.collection.JavaConverters._

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

        val spark = SparkSessionManager.getOrCreate()

        val objectStore = config.destination.objectStore
        val bucket = ObjectStoreSpark.resolveBucket(objectStore)
        val prefixKey = objectStore.prefixKey
        val outputPath = "s3a://" + bucket + "/" + prefixKey

        ObjectStoreSpark.applyPerBucketConfig(spark, bucket, objectStore)

        // Build schema from pipeline config
        val schemaFields = config.destination.schemaProperties.fields.asScala.toList
        val sparkSchema = SparkObjectStoreLoader.buildSchema(schemaFields)

        val delimiter = {
            if (
                config.source.fileAttributes != null && config.source.fileAttributes.csvAttributes != null
                && config.source.fileAttributes.csvAttributes.delimiter != null
            )
                config.source.fileAttributes.csvAttributes.delimiter
            else
                ","
        }

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
        val df = SparkObjectStoreLoader.stagedDataFrame(spark, stagedPath, schemaFields, delimiter)

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
        val iceberg: Option[IcebergWriter.WriteResult] = SparkObjectStoreLoader.withPipelineWriteLock(outputPath) {
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
                statusUtil.info("processing", "Writing iceberg (" + writeModeName + ") to: " + outputPath)
                // MERGE uses UPDATE SET * / INSERT *, so the source must carry
                // exactly the dest schema's columns in its order.
                val projected = df.select(sparkSchema.fieldNames.map(df.col): _*)
                Some(IcebergWriter.write(projected, outputPath, writeModeName, partitions, keyFields, sparkSchema, statusUtil, config.name))
            } else {
                val writeMode = writeModeName match {
                    case "overwrite" => SaveMode.Overwrite
                    case "ignore" => SaveMode.Ignore
                    case "errorifexists" => SaveMode.ErrorIfExists
                    case _ => SaveMode.Append
                }

                // Write with optional partitioning
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
        sendNotification(outputPath, snapshotId)
        iceberg match {
            case Some(r) if r.snapshotId >= 0L =>
                statusUtil.info(
                    "end",
                    "Process completed, wrote " + r.addedRecords + " rows to " + outputPath +
                        ", snapshot " + r.snapshotId + " (deleted " + r.deletedRecords + ", total " + r.totalRecords + " rows)"
                )
            case _ =>
                statusUtil.info("end", "Process completed, wrote " + jobContext.data.rowCount + " rows to " + outputPath)
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
