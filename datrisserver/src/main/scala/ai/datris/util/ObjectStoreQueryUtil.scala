package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, DatrisException, PipelineConfig, SchemaField}
import org.apache.hadoop.fs.Path
import org.apache.iceberg.hadoop.HadoopTables
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import org.apache.spark.sql.{AnalysisException, DataFrame, Row, SparkSession}
import org.slf4j.{Logger, LoggerFactory}

import java.util.concurrent.{Executors, ThreadFactory, TimeUnit}
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}

/** Read rows from a pipeline's objectStore destination. Reuses the same
  *  per-bucket S3A config the writer applies, so MinIO and S3 destinations
  *  both work without duplicating credential resolution. */
object ObjectStoreQueryUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    // Wall-clock cap on a single objectstore query. Long enough for a cold
    // SparkSession + first S3A connection, short enough that a fatal error
    // inside Spark's internal ThreadUtils.parmap (which has the same
    // NonFatal-leaks-fatal-throwables bug as our JobRunner used to) surfaces
    // to the caller as a clean error instead of an indefinite hang. Tunable
    // via DATRIS_OBJECTSTORE_QUERY_TIMEOUT_SEC if a large parquet read needs
    // more headroom.
    private val queryTimeoutSec: Int = sys.env.getOrElse("DATRIS_OBJECTSTORE_QUERY_TIMEOUT_SEC", "90").toInt

    // Dedicated single-thread pool so the timeout future can be Await'd from
    // the request thread without blocking the calling executor.
    private val queryEC: ExecutionContext = {
        val tf: ThreadFactory = (r: Runnable) => {
            val t = new Thread(r, "objectstore-query")
            t.setDaemon(true)
            t
        }
        ExecutionContext.fromExecutorService(Executors.newCachedThreadPool(tf))
    }

    /** `snapshotId` / `snapshotTimestamp` (ISO-8601 instant) identify the
      *  Iceberg snapshot the rows were read from. Null for parquet and orc, so
      *  existing consumers keep seeing the four fields they always had. */
    case class QueryResult(
        columns: java.util.List[String],
        rows: java.util.List[java.util.Map[String, Any]],
        path: String,
        format: String,
        snapshotId: java.lang.Long = null,
        snapshotTimestamp: String = null
    )

    private def emptyResult(path: String, format: String): QueryResult =
        QueryResult(new java.util.ArrayList[String](), new java.util.ArrayList[java.util.Map[String, Any]](), path, format)

    /** Return up to `limit` rows from the pipeline's objectStore destination.
      *
      *  Returns an empty result (not an error) when the bucket/prefix has no
      *  data yet — that's the legitimate "pipeline exists but no runs have
      *  succeeded" state and the caller should see it as "0 rows" rather than
      *  a failure. */
    def query(pipelineName: String, limit: Int): QueryResult = {
        val pipelineConfig = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, pipelineName)
        if (pipelineConfig == null)
            throw new DatrisException("Pipeline not found: " + pipelineName)
        if (pipelineConfig.destination == null || pipelineConfig.destination.objectStore == null)
            throw new DatrisException(
                "Pipeline '" + pipelineName + "' does not have an objectStore destination — use query_postgres / query_mongodb / search_* depending on the actual destination type."
            )

        val objectStore = pipelineConfig.destination.objectStore
        val bucket = ObjectStoreSpark.resolveBucket(objectStore)
        val prefixKey = objectStore.prefixKey
        if (prefixKey == null || prefixKey.isEmpty)
            throw new DatrisException("Pipeline '" + pipelineName + "' objectStore is missing prefixKey")

        val format = Option(objectStore.fileFormat).filter(_.nonEmpty).getOrElse("parquet").toLowerCase
        val path = "s3a://" + bucket + "/" + prefixKey
        val cappedLimit = if (limit <= 0) 100 else math.min(limit, 10000)

        val spark = SparkSessionManager.getOrCreate()
        ObjectStoreSpark.applyPerBucketConfig(spark, bucket, objectStore)

        // Group the Spark jobs we're about to launch so we can cancel them
        // cleanly if the timeout fires. Without a job group, an orphaned read
        // keeps running in the background after Await throws.
        val jobGroup = "objectstore-query-" + java.util.UUID.randomUUID().toString
        spark.sparkContext.setJobGroup(jobGroup, "objectstore query: " + pipelineName, interruptOnCancel = true)

        logger.info(
            s"ObjectStoreQuery: pipeline=$pipelineName, path=$path, format=$format, limit=$cappedLimit, jobGroup=$jobGroup, timeout=${queryTimeoutSec}s"
        )

        // Run the Spark read on the dedicated executor and Await it with a
        // wall-clock cap. A fatal error inside Spark's parmap (LinkageError,
        // NoSuchMethodError, etc.) leaves Spark's internal Promise uncompleted
        // and the driver thread would block forever — the timeout is our only
        // safety net for that bug class.
        // An Iceberg table committed through the Unity Catalog REST catalog is
        // read at the catalog's current metadata file, not the version hint.
        // Resolved inside the future so the catalog calls share the timeout.
        val readFuture: Future[QueryResult] = Future {
            val resolved = if (format == "iceberg") IcebergTableResolver.forPipeline(pipelineConfig, path) else None
            readPath(spark, path, format, cappedLimit, resolved, bucket, readSchemaFor(pipelineConfig))
        }(queryEC)

        try Await.result(readFuture, queryTimeoutSec.seconds)
        catch {
            case _: TimeoutException =>
                logger.warn(s"ObjectStoreQuery: timed out after ${queryTimeoutSec}s for pipeline=$pipelineName — cancelling job group $jobGroup")
                try spark.sparkContext.cancelJobGroup(jobGroup)
                catch { case _: Throwable => () }
                throw new DatrisException(
                    "Object store query timed out after " + queryTimeoutSec + "s. The Spark read did not complete — most often a classpath/version mismatch (e.g. hadoop-aws vs. hadoop-common). Check server logs for NoSuchMethodError, IllegalAccessError, or similar fatal Throwables. The wall-clock limit is tunable via DATRIS_OBJECTSTORE_QUERY_TIMEOUT_SEC."
                )
        } finally spark.sparkContext.clearJobGroup()
    }

    /** Read up to `limit` rows at `path` in `format` (parquet | orc | iceberg).
      *
      *  A location nothing has been written to yet is the legitimate "pipeline
      *  exists but no run has succeeded" state and comes back as an empty
      *  result rather than an exception: Spark 3.5 raises
      *  `AnalysisException[PATH_NOT_FOUND]` for a missing non-iceberg prefix
      *  (older Hadoop input formats raised `InvalidInputException`), and a
      *  path with no Iceberg `metadata/` is detected up front in `readIceberg`
      *  (the catalyst `NoSuchTableException` catch covers the DataFrame read
      *  resolver's own view of the same state).
      *
      *  Package-private so the spec can drive it with a file:// path; `query`
      *  owns config lookup, per-bucket S3A config, limit capping and the
      *  wall-clock timeout. */
    private[util] def readPath(spark: SparkSession, path: String, format: String, limit: Int): QueryResult =
        readPath(spark, path, format, limit, None)

    /** As above; `metadataLocation` (Iceberg only) reads the table at that
      * metadata file instead of through the version hint
      * (IcebergTableResolver: catalog-committed tables). */
    private[util] def readPath(spark: SparkSession, path: String, format: String, limit: Int, metadataLocation: Option[String]): QueryResult =
        readPath(spark, path, format, limit, metadataLocation.map(IcebergTableResolver.Resolved(_, managed = false)), null)

    /** As above with the resolver's answer: a managed table
      * (`catalogMode: managed`) lives where the catalog put it, so instead of
      * the table-root check its location must be in the pipeline's `bucket`. */
    private[util] def readPath(
        spark: SparkSession,
        path: String,
        format: String,
        limit: Int,
        resolved: Option[IcebergTableResolver.Resolved],
        bucket: String
    ): QueryResult = readPath(spark, path, format, limit, resolved, bucket, None)

    /** What a query of a known Parquet pipeline, or ORC one, asks for: the stored
      * destination schema (the source schema when there is none), in its
      * order and with the types the loader writes
      * (SparkObjectStoreLoader.buildSchema), and the provenance stamp columns
      * the stamper writes now (`ProvenanceStamper.enabled` and the fields
      * the block selects; none while stamping is off), which the stamper
      * adds to the written data and never to the stored schema. */
    final case class ReadSchema(stored: StructType, stamps: Seq[String])

    /** The [[ReadSchema]] of `config`; None when it has no fields. */
    private[util] def readSchemaFor(config: PipelineConfig): Option[ReadSchema] = {
        def fieldsOf(p: ai.datris.model.SchemaProperties): List[SchemaField] =
            if (p == null || p.fields == null) Nil else p.fields.asScala.toList.filter(f => f != null && f.name != null)
        val schemaFields =
            Option(config).flatMap(c => Option(c.destination)).map(d => fieldsOf(d.schemaProperties)).filter(_.nonEmpty)
                .orElse(Option(config).flatMap(c => Option(c.source)).map(s => fieldsOf(s.schemaProperties)))
                .getOrElse(Nil)
        if (schemaFields.isEmpty) return None
        val named = schemaFields.map(_.name.toLowerCase).toSet
        val stamps =
            if (!ProvenanceStamper.enabled(config)) Nil
            else {
                val selected = Option(config.provenance.fields).map(_.asScala.toList).filter(_.nonEmpty).getOrElse(ProvenanceStamper.AllFields)
                ProvenanceStamper.AllFields.filter(f => selected.exists(_.equalsIgnoreCase(f))).filterNot(f => named.contains(f.toLowerCase))
            }
        Some(ReadSchema(SparkObjectStoreLoader.buildSchema(schemaFields), stamps))
    }

    /** Above this many files an ORC query does not merge every file's schema
      * (one footer read per file, run as a Spark job): it reads with the
      * stored types instead, as a Parquet query always does. At a few
      * milliseconds per footer on object storage, 1,000 files keep the
      * merge to seconds, well inside the query's wall-clock limit. */
    private[util] val OrcMergeMaxFiles: Int = 1000

    private def find(schema: StructType, name: String): Option[StructField] = schema.fields.find(_.name.equalsIgnoreCase(name))

    /** A read failed because a column's type in a file differs from the
      * type it was read as, or because files disagree on a type. */
    private[util] def typeConflict(e: Throwable): Boolean =
        Iterator.iterate(e)(_.getCause).takeWhile(_ != null).take(20).exists { t =>
            val m = String.valueOf(t.getMessage)
            t.getClass.getName.contains("SchemaColumnConvertNotSupportedException") ||
            m.contains("cannot be converted") || m.contains("PARQUET_COLUMN_DATA_TYPE_MISMATCH") ||
            m.contains("Unable to create Parquet converter") || m.contains("CANNOT_MERGE") || m.contains("Failed to merge")
        }

    /** The listing the location's plain read made (one listing, one file's
      * footer: the read the query always did), as its relation. */
    private def relationOf(df: DataFrame): Option[org.apache.spark.sql.execution.datasources.HadoopFsRelation] =
        df.queryExecution.analyzed.collectFirst {
            case l: org.apache.spark.sql.execution.datasources.LogicalRelation => l.relation
        }.collect { case h: org.apache.spark.sql.execution.datasources.HadoopFsRelation => h }

    private def result(df: DataFrame, limit: Int, path: String, format: String): QueryResult = {
        val rows = df.limit(limit).collectAsList().asScala.map(row => rowToMap(row, df.columns)).asJava
        QueryResult(df.columns.toList.asJava, rows, path, format)
    }

    /** The Parquet files, or the ORC files, at `path`: every column of
      * every file, one column list for every row.
      *
      * The plain read of the location (what the query always did: one
      * listing, the schema of one file) supplies the listing, the partition
      * columns (their types inferred from the directory values, as before)
      * and that one file's schema. The data is then read over the same
      * listing with:
      *  - ORC, up to [[OrcMergeMaxFiles]] files: the merge of every file's
      *    schema (the files' own types; ORC's reader would convert a
      *    mismatched requested type and null what it cannot convert);
      *  - Parquet, and ORC above that: the stored types, no footer opened.
      * Columns come back in the stored order, then the stamp columns the
      * data holds; a column a file lacks is null; a column the files hold
      * but the stored schema does not is not returned. When the stored type
      * of a column differs from what the files hold (the sampled file
      * already shows it, the merge fails, or the read fails converting),
      * each file is read with its own types instead ([[readFileByFile]]).
      * Without `readSchema` the files' own schemas are merged. */
    private def readFiles(
        spark: SparkSession,
        path: String,
        format: String,
        limit: Int,
        readSchema: Option[ReadSchema],
        orcMergeMaxFiles: Int
    ): QueryResult = {
        val plain = spark.read.format(format).load(path)
        val relation = relationOf(plain).getOrElse(return result(plain, limit, path, format))
        val files: Seq[org.apache.hadoop.fs.FileStatus] = relation.location match {
            case p: org.apache.spark.sql.execution.datasources.PartitioningAwareFileIndex => p.allFiles()
            case _ => Nil
        }
        val sampled = relation.dataSchema
        val partitions = relation.partitionSchema
        def isPartition(n: String): Boolean = find(partitions, n).isDefined
        def stampsIn(schema: StructType, stored: StructType): Seq[String] =
            ProvenanceStamper.AllFields.filter(n => find(schema, n).isDefined && find(stored, n).isEmpty)
        def fallBack(why: String): QueryResult = {
            logger.warn("ObjectStoreQuery: " + path + ": " + why + "; reading each file with its own types")
            readFileByFile(spark, path, format, limit, readSchema.map(_.stored), files)
        }
        def merged(): Option[StructType] =
            try format match {
                    case "orc" => new org.apache.spark.sql.execution.datasources.orc.OrcFileFormat().inferSchema(spark, Map("mergeSchema" -> "true"), files)
                    case _ => new org.apache.spark.sql.execution.datasources.parquet.ParquetFileFormat().inferSchema(spark, Map("mergeSchema" -> "true"), files)
                }
            catch { case e: Exception if typeConflict(e) => None }

        val stored = readSchema.map(_.stored).getOrElse(StructType(Nil))
        val (dataFields, order, conflict): (Seq[StructField], Seq[String], Option[String]) =
            if (readSchema.isEmpty || (format == "orc" && files.size <= orcMergeMaxFiles)) {
                merged() match {
                    case None => (Nil, Nil, Some("the files disagree on a column's type"))
                    case Some(m) if readSchema.isEmpty =>
                        (
                            m.fields.toSeq.filterNot(f => isPartition(f.name)),
                            m.fieldNames.toSeq ++ partitions.fieldNames.filterNot(n => find(m, n).isDefined),
                            None
                        )
                    case Some(m) =>
                        val storedData = stored.fields.toSeq.filterNot(f => isPartition(f.name)).map(f => find(m, f.name).getOrElse(f))
                        val stamps = stampsIn(m, stored)
                        (storedData ++ stamps.flatMap(n => find(m, n)), stored.fieldNames.toSeq ++ stamps, None)
                }
            } else {
                val storedData = stored.fields.toSeq.filterNot(f => isPartition(f.name))
                storedData.find(f => find(sampled, f.name).exists(_.dataType != f.dataType)) match {
                    case Some(f) =>
                        (
                            Nil,
                            Nil,
                            Some("column '" + f.name + "' is stored as " + f.dataType.simpleString + " but the files hold " +
                                find(sampled, f.name).get.dataType.simpleString)
                        )
                    case None =>
                        val stamps = ProvenanceStamper.AllFields.filter(n =>
                            (readSchema.get.stamps.contains(n) || find(sampled, n).isDefined) && find(stored, n).isEmpty
                        )
                        (
                            storedData ++ stamps.map(n => find(sampled, n).getOrElse(StructField(n, StringType, nullable = true))),
                            stored.fieldNames.toSeq ++ stamps,
                            None
                        )
                }
            }
        conflict match {
            case Some(why) => fallBack(why)
            case None =>
                val df = spark.baseRelationToDataFrame(relation.copy(dataSchema = StructType(dataFields))(spark))
                val projected = df.select(order.map { n =>
                    val actual = df.columns.find(_.equalsIgnoreCase(n)).getOrElse(n)
                    df.col(IcebergWriter.quote(actual)).as(n)
                }: _*)
                try result(projected, limit, path, format)
                catch {
                    case e: Exception if typeConflict(e) =>
                        fallBack("a column's stored type differs from what a file holds (" + String.valueOf(e.getMessage).split("\n").head.take(200) + ")")
                }
        }
    }

    /** Each file read with its own types (and the location's partition
      * columns), until `limit` rows: what a query does when a column's
      * stored type differs from what the files hold. Columns: those of
      * `stored` that a read file holds, in that order (all the files'
      * columns, in first-seen order, without `stored`), then the stamp
      * columns a read file holds. A column the read files hold under two
      * types is returned as text in every row. */
    private def readFileByFile(
        spark: SparkSession,
        path: String,
        format: String,
        limit: Int,
        stored: Option[StructType],
        files: Seq[org.apache.hadoop.fs.FileStatus]
    ): QueryResult = {
        val rows = scala.collection.mutable.ArrayBuffer[Map[String, Any]]()
        val types = scala.collection.mutable.LinkedHashMap[String, scala.collection.mutable.Set[String]]()
        val names = scala.collection.mutable.LinkedHashMap[String, String]()
        val it = files.sortBy(_.getPath.toString).iterator
        while (rows.size < limit && it.hasNext) {
            val f = it.next()
            val d = spark.read.format(format).option("basePath", path).load(f.getPath.toString)
            d.schema.fields.foreach { sf =>
                names.getOrElseUpdate(sf.name.toLowerCase, sf.name)
                types.getOrElseUpdate(sf.name.toLowerCase, scala.collection.mutable.Set[String]()) += sf.dataType.simpleString
            }
            d.limit(limit - rows.size).collect().foreach(r => rows += d.columns.map(_.toLowerCase).zip(r.toSeq).toMap)
        }
        val order: Seq[String] = stored match {
            case Some(s) =>
                s.fieldNames.toSeq.filter(n => names.contains(n.toLowerCase)) ++
                    ProvenanceStamper.AllFields.filter(n => names.contains(n.toLowerCase) && find(s, n).isEmpty)
            case None => names.values.toSeq
        }
        val text = types.collect { case (k, ts) if ts.size > 1 => k }.toSet
        val out = rows.map { r =>
            val m = new java.util.LinkedHashMap[String, Any]()
            order.foreach { n =>
                val v = sparkValueToJson(r.getOrElse(n.toLowerCase, null).asInstanceOf[AnyRef])
                m.put(n, if (v != null && text.contains(n.toLowerCase)) String.valueOf(v) else v)
            }
            m: java.util.Map[String, Any]
        }
        QueryResult(order.toList.asJava, out.asJava, path, format)
    }

    /** As above, reading Parquet and ORC for `readSchema` ([[readFiles]]). */
    private[util] def readPath(
        spark: SparkSession,
        path: String,
        format: String,
        limit: Int,
        resolved: Option[IcebergTableResolver.Resolved],
        bucket: String,
        readSchema: Option[ReadSchema],
        orcMergeMaxFiles: Int = OrcMergeMaxFiles
    ): QueryResult = {
        // Runs on the objectstore-query pool, which never created the session;
        // the Iceberg source resolves its catalog through the thread-active one.
        SparkSession.setActiveSession(spark)
        try {
            if (format == "iceberg") readIceberg(spark, path, limit, resolved, bucket)
            else {
                readFiles(spark, path, format, limit, readSchema, orcMergeMaxFiles)
            }
        } catch {
            case e: org.apache.hadoop.mapred.InvalidInputException =>
                logger.info(s"ObjectStoreQuery: no data at $path yet (${e.getMessage}) — returning empty result")
                emptyResult(path, format)
            // An existing location with no data files yet: the same empty
            // result as a missing one, for both formats.
            case e: AnalysisException if e.getErrorClass == "UNABLE_TO_INFER_SCHEMA" && format != "iceberg" =>
                logger.info(s"ObjectStoreQuery: no data files at $path yet (${e.getMessage}) — returning empty result")
                emptyResult(path, format)
            case e: AnalysisException if e.getErrorClass == "PATH_NOT_FOUND" =>
                logger.info(s"ObjectStoreQuery: no data at $path yet (${e.getMessage}) — returning empty result")
                emptyResult(path, format)
            case e: org.apache.spark.sql.catalyst.analysis.NoSuchTableException =>
                logger.info(s"ObjectStoreQuery: no Iceberg table at $path yet (${e.getMessage}) — returning empty result")
                emptyResult(path, format)
            case e: org.apache.hadoop.fs.UnsupportedFileSystemException =>
                throw new DatrisException("Object store read failed (unsupported scheme on " + path + "): " + e.getMessage)
        }
    }

    /** Resolve the table's current snapshot first, then pin the DataFrame
      *  read to it, so the id we report is exactly the one the rows came
      *  from even if a run commits in between.
      *
      *  "Empty" means only "no `metadata/` under the prefix". That check is a
      *  plain filesystem `exists`, which propagates auth failures (S3A raises
      *  AccessDeniedException) the same way the parquet and orc path does.
      *  `HadoopTables.load` is deliberately NOT used as the existence probe:
      *  it swallows IO errors while looking for the version hint and reports
      *  a wrong access key as NoSuchTableException, which would turn an auth
      *  error into an HTTP 200 with 0 rows. Once `metadata/` is known to
      *  exist, any load failure (corrupt table, denied reads) propagates. */
    private def readIceberg(spark: SparkSession, path: String, limit: Int, resolved: Option[IcebergTableResolver.Resolved], bucket: String): QueryResult = {
        val metadataLocation = resolved.map(_.metadataLocation)
        IcebergWriter.ensureCatalogs(spark, path)
        val conf = spark.sessionState.newHadoopConf()
        val metadataDir = new Path(path, "metadata")
        if (metadataLocation.isEmpty && !metadataDir.getFileSystem(conf).exists(metadataDir)) {
            logger.info(s"ObjectStoreQuery: no Iceberg metadata/ at $path yet — returning empty result")
            return emptyResult(path, "iceberg")
        }
        // A `.metadata.json` location loads that exact metadata (read-only);
        // the table path loads through the version hint.
        val source = metadataLocation.getOrElse(path)
        val table = new HadoopTables(conf).load(source)
        if (resolved.exists(_.managed)) IcebergWriter.assertManagedLocation(table, bucket)
        else IcebergWriter.assertTableLocation(table, path)
        // catalogMode managed: the rows live where the catalog put the table,
        // not under prefixKey; report that location.
        val reported = if (resolved.exists(_.managed)) Option(table.location()).getOrElse(path) else path
        val snapshot = table.currentSnapshot()
        if (snapshot == null) {
            // Table created but nothing ever committed: no rows, no snapshot.
            return QueryResult(
                table.schema().columns().asScala.map(_.name()).toList.asJava,
                new java.util.ArrayList[java.util.Map[String, Any]](),
                reported,
                "iceberg"
            )
        }
        val df = spark.read.format("iceberg").option("snapshot-id", snapshot.snapshotId()).load(source)
        val columns = df.columns.toList.asJava
        val rows = df.limit(limit).collectAsList().asScala.map(row => rowToMap(row, df.columns)).asJava
        QueryResult(
            columns,
            rows,
            reported,
            "iceberg",
            java.lang.Long.valueOf(snapshot.snapshotId()),
            java.time.Instant.ofEpochMilli(snapshot.timestampMillis()).toString
        )
    }

    private def rowToMap(row: Row, columns: Array[String]): java.util.Map[String, Any] = {
        val map = new java.util.LinkedHashMap[String, Any]()
        columns.zipWithIndex.foreach { case (col, i) =>
            val raw = if (i < row.length) row.get(i) else null
            map.put(col, sparkValueToJson(raw))
        }
        map
    }

    /** Convert Spark column values to JSON-safe primitives. Timestamps and
      *  dates become ISO strings so the JSON response is self-describing
      *  without the agent having to interpret epoch millis. */
    private def sparkValueToJson(v: Any): Any = v match {
        case null => null
        case s: String => s
        case b: java.lang.Boolean => b
        // BigDecimal comes before java.lang.Number (BigDecimal IS-A Number) so
        // we preserve exact precision via toPlainString instead of letting Gson
        // serialize it via Number's default behavior.
        case bd: java.math.BigDecimal => bd.toPlainString
        case n: java.lang.Number => n
        case ts: java.sql.Timestamp => ts.toInstant.toString
        case d: java.sql.Date => d.toString
        case ba: Array[Byte] => java.util.Base64.getEncoder.encodeToString(ba)
        case seq: scala.collection.Seq[_] => seq.map(sparkValueToJson).asJava
        case arr: Array[_] => arr.toSeq.map(sparkValueToJson).asJava
        case m: scala.collection.Map[_, _] => m.map { case (k, vv) => (k.toString, sparkValueToJson(vv)) }.asJava
        case r: Row => rowToMap(r, r.schema.fieldNames)
        case other => other.toString
    }
}
