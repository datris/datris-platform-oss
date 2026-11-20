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

import java.util.concurrent.atomic.AtomicBoolean
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
        // The group is set on the thread that runs the read (a pool thread),
        // since Spark's job group is a thread-local property.
        val jobGroup = "objectstore-query-" + java.util.UUID.randomUUID().toString

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
        val cancelled = new AtomicBoolean(false)
        val readFuture: Future[QueryResult] = Future {
            spark.sparkContext.setJobGroup(jobGroup, "objectstore query: " + pipelineName, interruptOnCancel = true)
            try {
                val resolved = if (format == "iceberg") IcebergTableResolver.forPipeline(pipelineConfig, path) else None
                readPath(spark, path, format, cappedLimit, resolved, bucket, readSchemaFor(pipelineConfig), cancelled = cancelled)
            } finally spark.sparkContext.clearJobGroup()
        }(queryEC)

        try Await.result(readFuture, queryTimeoutSec.seconds)
        catch {
            case _: TimeoutException =>
                logger.warn(s"ObjectStoreQuery: timed out after ${queryTimeoutSec}s for pipeline=$pipelineName — cancelling job group $jobGroup")
                cancelled.set(true)
                try spark.sparkContext.cancelJobGroup(jobGroup)
                catch { case _: Throwable => () }
                throw new DatrisException(
                    "Object store query timed out after " + queryTimeoutSec + "s: the read did not finish. Common causes are a slow or unreachable object store, a very large location, or a Spark classpath/version mismatch (e.g. hadoop-aws vs. hadoop-common; check server logs for NoSuchMethodError or IllegalAccessError). The limit is tunable via DATRIS_OBJECTSTORE_QUERY_TIMEOUT_SEC."
                )
        }
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

    /** Up to this many data files, a query of Parquet files, or of ORC files, reads every
      * file's footer (in parallel on the server, no Spark job) to learn every
      * column and type the location holds. Above it the query reads with the
      * schema of one sampled file, as it always did, plus the stored columns
      * that file lacks. 1,000 footers is one small ranged read each; how long
      * that takes depends on the object store, and the cap keeps it bounded. */
    private[util] val FileSchemaScanMaxFiles: Int = 1000

    /** Footers read at once. */
    private val FooterReadThreads: Int = 16

    private val footerEC: ExecutionContext = {
        val tf: ThreadFactory = (r: Runnable) => {
            val t = new Thread(r, "objectstore-footer")
            t.setDaemon(true)
            t
        }
        ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(FooterReadThreads, tf))
    }

    /** What one read did, for specs: footers opened, Spark reads issued, and
      * a hook run after the listing (before any footer or data read). */
    private[util] final class ReadStats(val afterListing: () => Unit = () => (), val beforeFooter: () => Unit = () => ()) {
        @volatile var footersRead: Int = 0
        @volatile var dataReads: Int = 0
        @volatile var path: String = ""
    }

    private def find(schema: StructType, name: String): Option[StructField] = schema.fields.find(_.name.equalsIgnoreCase(name))

    /** A read failed because a column's type in a file differs from the
      * type it was read as. */
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

    /** The files of `base` that `keep` names, with the partition values
      * `base` listed for them: a read of part of a location that neither
      * lists again nor infers partition values again. */
    private final class SubsetIndex(base: org.apache.spark.sql.execution.datasources.FileIndex, keep: Set[String])
        extends org.apache.spark.sql.execution.datasources.FileIndex {
        override def rootPaths: Seq[org.apache.hadoop.fs.Path] = base.rootPaths
        override def listFiles(
            partitionFilters: Seq[org.apache.spark.sql.catalyst.expressions.Expression],
            dataFilters: Seq[org.apache.spark.sql.catalyst.expressions.Expression]
        ): Seq[org.apache.spark.sql.execution.datasources.PartitionDirectory] =
            base.listFiles(partitionFilters, dataFilters).map(d => d.copy(files = d.files.filter(f => keep.contains(f.getPath.toString)))).filter(
                _.files.nonEmpty
            )
        override def inputFiles: Array[String] = base.inputFiles.filter(keep.contains)
        override def refresh(): Unit = ()
        override def sizeInBytes: Long = base.sizeInBytes
        override def partitionSchema: StructType = base.partitionSchema
    }

    /** One data file's own schema, read from its footer; a failure names
      * the file. */
    private def fileSchema(
        format: String,
        status: org.apache.hadoop.fs.FileStatus,
        conf: org.apache.hadoop.conf.Configuration,
        sqlConf: org.apache.spark.sql.internal.SQLConf
    ): StructType = nullable(fileSchemaAsWritten(format, status, conf, sqlConf)).asInstanceOf[StructType]

    /** Every field nullable, as Spark's own file read reports a schema. */
    private def nullable(t: org.apache.spark.sql.types.DataType): org.apache.spark.sql.types.DataType = {
        import org.apache.spark.sql.types._
        t match {
            case s: StructType => StructType(s.fields.map(f => f.copy(dataType = nullable(f.dataType), nullable = true)))
            case a: ArrayType => ArrayType(nullable(a.elementType), containsNull = true)
            case m: MapType => MapType(nullable(m.keyType), nullable(m.valueType), valueContainsNull = true)
            case other => other
        }
    }

    private def fileSchemaAsWritten(
        format: String,
        status: org.apache.hadoop.fs.FileStatus,
        conf: org.apache.hadoop.conf.Configuration,
        sqlConf: org.apache.spark.sql.internal.SQLConf
    ): StructType =
        try format match {
                case "orc" =>
                    org.apache.spark.sql.execution.datasources.orc.OrcUtils
                        .readSchema(status.getPath, conf, false)
                        .map(org.apache.spark.sql.execution.datasources.orc.OrcUtils.toCatalystSchema)
                        .getOrElse(StructType(Nil))
                case _ =>
                    val reader = org.apache.parquet.hadoop.ParquetFileReader.open(org.apache.parquet.hadoop.util.HadoopInputFile.fromStatus(status, conf))
                    try {
                        val meta = reader.getFooter.getFileMetaData
                        // As Spark's own footer read: the Spark schema the writer
                        // stored when there is one that parses, else the
                        // Parquet schema converted under the session's settings.
                        Option(meta.getKeyValueMetaData.get("org.apache.spark.sql.parquet.row.metadata"))
                            .flatMap(j => scala.util.Try(org.apache.spark.sql.types.DataType.fromJson(j)).toOption.collect { case st: StructType => st })
                            .getOrElse(new org.apache.spark.sql.execution.datasources.parquet.ParquetToSparkSchemaConverter(sqlConf).convert(meta.getSchema))
                    } finally reader.close()
            }
        catch {
            case e: Exception =>
                throw new IllegalStateException("Could not read the schema of " + status.getPath + ": " + e.getMessage, e)
        }

    /** A column's types across the files that hold it, read as one type
      * when every type fits the widest exactly: tinyint, smallint, int,
      * bigint; float, double; tinyint, smallint, int and float with double;
      * decimals (the widest scale and integer part, up to 38 digits). None:
      * the column is text. */
    private[util] def oneType(types: Seq[org.apache.spark.sql.types.DataType]): Option[org.apache.spark.sql.types.DataType] = {
        import org.apache.spark.sql.types._
        val ts = types.distinct
        val integral = Seq[DataType](ByteType, ShortType, IntegerType, LongType)
        val fractional = Seq[DataType](FloatType, DoubleType)
        val toDouble = Seq[DataType](ByteType, ShortType, IntegerType, FloatType, DoubleType)
        if (ts.size == 1) Some(ts.head)
        else if (ts.forall(integral.contains)) Some(ts.maxBy(t => integral.indexOf(t)))
        else if (ts.forall(fractional.contains)) Some(DoubleType)
        else if (ts.forall(toDouble.contains)) Some(DoubleType) // tinyint, smallint, int and float all fit a double exactly
        else if (ts.forall(_.isInstanceOf[DecimalType])) {
            val ds = ts.map(_.asInstanceOf[DecimalType])
            val scale = ds.map(_.scale).max
            val precision = ds.map(d => d.precision - d.scale).max + scale
            if (precision <= DecimalType.MAX_PRECISION) Some(DecimalType(precision, scale)) else None
        } else None
    }

    /** The result's columns: the stored schema in its order, then the
      * provenance stamp columns `schema` holds (and, with `assumedStamps`,
      * the ones being stamped now), then the other columns `schema` holds in
      * its order, then partition columns the stored schema does not name. */
    private def columnList(stored: StructType, schema: StructType, partitions: StructType, assumedStamps: Seq[String]): Seq[String] = {
        def storedHas(n: String) = find(stored, n).isDefined
        def partition(n: String) = find(partitions, n).isDefined
        val stamps = ProvenanceStamper.AllFields.filter(n => (find(schema, n).isDefined || assumedStamps.contains(n)) && !storedHas(n))
        val others = schema.fieldNames.toSeq.filter(n => !storedHas(n) && !stamps.exists(_.equalsIgnoreCase(n)) && !partition(n))
        stored.fieldNames.toSeq ++ stamps ++ others ++ partitions.fieldNames.toSeq.filterNot(storedHas)
    }

    /** `order` from `df` (names matched without regard to case), each
      * named `nameOf(n)`; a name `df` lacks is a null of the stored type
      * (string otherwise). */
    private def project(df: DataFrame, order: Seq[String], stored: StructType, nameOf: String => String): DataFrame =
        df.select(order.map { n =>
            df.columns.find(_.equalsIgnoreCase(n)) match {
                case Some(actual) => df.col(IcebergWriter.quote(actual)).as(nameOf(n))
                case None => org.apache.spark.sql.functions.lit(null).cast(find(stored, n).map(_.dataType).getOrElse(StringType)).as(nameOf(n))
            }
        }: _*)

    /** Thrown when a Spark read would start after the query was given up. */
    private[util] final class QueryCancelled extends RuntimeException("The object store query was cancelled after its time limit")

    private def collect(df: DataFrame, limit: Int, path: String, format: String, stats: ReadStats, cancelled: AtomicBoolean): QueryResult = {
        if (cancelled.get) throw new QueryCancelled
        stats.dataReads += 1
        val rows = df.limit(limit).collectAsList().asScala.map(row => rowToMap(row, df.columns)).asJava
        QueryResult(df.columns.toList.asJava, rows, path, format)
    }

    /** One file's schema as the footer scan derives it (for specs). */
    private[util] def fileSchemaOf(spark: SparkSession, format: String, file: String): StructType = {
        val p = new org.apache.hadoop.fs.Path(file)
        val conf = spark.sessionState.newHadoopConf()
        fileSchema(format, p.getFileSystem(conf).getFileStatus(p), conf, spark.sessionState.conf)
    }

    /** How long the footer scan may take before the query reads the faster
      * way instead (the sampled file's schema), well inside the query's own
      * time limit. */
    private[util] val FooterScanBudgetMs: Long = 10000L

    /** File schemas already read, by format, path, length and modification
      * time (a rewritten file has a new key), so repeated queries of a
      * location do not read the same footers again. Least recently used
      * entries go first. */
    private object FileSchemaCache {
        private val MaxEntries = 20000
        private val entries = new java.util.LinkedHashMap[(String, String, Long, Long), StructType](1024, 0.75f, true) {
            override def removeEldestEntry(e: java.util.Map.Entry[(String, String, Long, Long), StructType]): Boolean = size() > MaxEntries
        }
        def key(format: String, f: org.apache.hadoop.fs.FileStatus): (String, String, Long, Long) =
            (format, f.getPath.toString, f.getLen, f.getModificationTime)
        def get(k: (String, String, Long, Long)): Option[StructType] = entries.synchronized(Option(entries.get(k)))
        // Files with one schema share one instance (bounded, least recently used out).
        private val canonical = new java.util.LinkedHashMap[StructType, StructType](64, 0.75f, true) {
            override def removeEldestEntry(e: java.util.Map.Entry[StructType, StructType]): Boolean = size() > 1000
        }
        def put(k: (String, String, Long, Long), v: StructType): Unit = entries.synchronized {
            val shared = Option(canonical.get(v)).getOrElse { canonical.put(v, v); v }
            entries.put(k, shared)
        }
    }

    /** Every file's schema, read in parallel within `budgetMs`; None (with a
      * warning naming the reason) when any footer cannot be read, a file has
      * vanished, or the budget runs out: the caller then reads the faster
      * way, which fails only where Spark's own read reaches a bad file. */
    private def scanSchemas(
        spark: SparkSession,
        path: String,
        format: String,
        files: Seq[org.apache.hadoop.fs.FileStatus],
        budgetMs: Long,
        stats: ReadStats
    ): Option[Seq[StructType]] = {
        val conf = spark.sessionState.newHadoopConf()
        val sqlConf = spark.sessionState.conf
        val stop = new AtomicBoolean(false)
        val tasks = files.map { f =>
            Future {
                if (stop.get) None
                else {
                    val k = FileSchemaCache.key(format, f)
                    FileSchemaCache.get(k).orElse {
                        stats.beforeFooter()
                        stats.synchronized(stats.footersRead += 1)
                        val schema = fileSchema(format, f, conf, sqlConf)
                        FileSchemaCache.put(k, schema)
                        Some(schema)
                    }
                }
            }(footerEC)
        }
        try {
            val got = Await.result(Future.sequence(tasks)(implicitly, footerEC), budgetMs.millis)
            if (got.forall(_.isDefined)) Some(got.flatten) else None
        } catch {
            case _: TimeoutException =>
                stop.set(true)
                logger.warn("ObjectStoreQuery: " + path + ": reading " + files.size + " file schemas took longer than " + budgetMs +
                    " ms; reading with the sampled file's schema instead")
                None
            case e: Exception =>
                stop.set(true)
                logger.warn("ObjectStoreQuery: " + path + ": " + String.valueOf(e.getMessage).split("\n").head.take(300) +
                    "; reading with the sampled file's schema instead")
                None
        }
    }

    /** The Parquet files, or the ORC files, at `path`.
      *
      * The plain read of the location (one listing and one sampled file's
      * schema, as the query always did) gives the listing and the partition
      * columns with their values. Then:
      *  - Up to [[FileSchemaScanMaxFiles]] non-empty data files, when every
      *    footer can be read within [[FooterScanBudgetMs]]: the result has
      *    every column the location holds, each under the type the files hold
      *    it in. Files that agree are read in one Spark read. When files hold
      *    a column under different types, each group of files with one schema
      *    is read on its own (one read per schema, never per file), and a
      *    column whose types do not fit one type exactly (see [[oneType]])
      *    comes back as text; this is decided from every file's schema, so it
      *    does not depend on the limit.
      *  - Otherwise (more files, a footer that cannot be read, a vanished
      *    file, the budget spent): the sampled file's columns and types, plus
      *    the stored columns and the stamps being stamped now that it lacks
      *    (null where a file lacks them); if a file holds one of those under
      *    another type, the read is the sampled file's columns only. This
      *    fails only where Spark's own read of the location fails.
      * The stored schema decides the order and adds a null column for a
      * stored column no file holds. A column the files hold keeps the files'
      * letter case (the sampled file's when files differ). Zero-byte files
      * are skipped. */
    private def readFiles(
        spark: SparkSession,
        path: String,
        format: String,
        limit: Int,
        readSchema: Option[ReadSchema],
        maxScan: Int,
        budgetMs: Long,
        stats: ReadStats,
        cancelled: AtomicBoolean
    ): QueryResult = {
        val plain =
            try spark.read.format(format).load(path)
            catch {
                case e: org.apache.hadoop.mapred.InvalidInputException =>
                    logger.info(s"ObjectStoreQuery: no data at $path yet (${e.getMessage}) — returning empty result")
                    return emptyResult(path, format)
                // An existing location with no data files yet: the same empty
                // result as a missing one, for both formats.
                case e: AnalysisException if e.getErrorClass == "UNABLE_TO_INFER_SCHEMA" || e.getErrorClass == "PATH_NOT_FOUND" =>
                    logger.info(s"ObjectStoreQuery: no data at $path yet (${e.getMessage}) — returning empty result")
                    return emptyResult(path, format)
            }
        val relation = relationOf(plain).getOrElse(return collect(plain, limit, path, format, stats, cancelled))
        stats.path = path
        stats.afterListing()
        val files: Seq[org.apache.hadoop.fs.FileStatus] = (relation.location match {
            case p: org.apache.spark.sql.execution.datasources.PartitioningAwareFileIndex => p.allFiles()
            case _ => Nil
        }).filter(_.getLen > 0)
        val partitions = relation.partitionSchema
        val sampled = relation.dataSchema
        val stored = readSchema.map(_.stored).getOrElse(StructType(Nil))
        def dataOnly(s: StructType): StructType = StructType(s.fields.filterNot(f => find(partitions, f.name).isDefined))
        // A column's name in the result: the files' letter case (the sampled
        // file first), else the partition's, else the stored schema's.
        def namer(fileSchemas: Seq[StructType]): String => String = {
            val m = scala.collection.mutable.LinkedHashMap[String, String]()
            (Seq(sampled) ++ fileSchemas ++ Seq(partitions)).foreach(_.fieldNames.foreach(n => m.getOrElseUpdate(n.toLowerCase, n)))
            n => m.getOrElse(n.toLowerCase, n)
        }

        val scanned: Option[Seq[StructType]] =
            if (files.nonEmpty && files.size <= maxScan) scanSchemas(spark, path, format, files, budgetMs, stats).map(_.map(dataOnly)) else None

        scanned match {
            case Some(schemas) =>
                // every column the files hold, first-seen order and name
                val names = scala.collection.mutable.LinkedHashMap[String, String]()
                val types = scala.collection.mutable.LinkedHashMap[String, Seq[org.apache.spark.sql.types.DataType]]().withDefaultValue(Nil)
                schemas.foreach(_.fields.foreach { f =>
                    val k = f.name.toLowerCase
                    names.getOrElseUpdate(k, f.name)
                    if (!types(k).contains(f.dataType)) types(k) = types(k) :+ f.dataType
                })
                val all = StructType(names.toSeq.map { case (k, n) => StructField(n, types(k).head, nullable = true) })
                val order = columnList(stored, all, partitions, Nil)
                val nameOf = namer(schemas)
                if (types.values.forall(_.size == 1)) {
                    val df = spark.baseRelationToDataFrame(relation.copy(dataSchema = all)(spark))
                    collect(project(df, order, stored, nameOf), limit, path, format, stats, cancelled)
                } else {
                    val target = types.map { case (k, ts) => k -> (if (ts.size > 1) oneType(ts) else Some(ts.head)) }
                    val text = target.collect { case (k, None) => k }.toSet
                    types.collect { case (k, ts) if ts.size > 1 => k + " (" + ts.map(_.simpleString).mkString(", ") + ")" }.foreach(c =>
                        logger.warn("ObjectStoreQuery: " + path + ": the files hold column " + c + " under different types; reading each schema on its own" +
                            (if (text.nonEmpty) ", returning " + text.mkString(", ") + " as text" else ""))
                    )
                    // groups of files with one schema, in listing order
                    val groups = files.zip(schemas).groupBy(_._2).toSeq.sortBy { case (_, fs) => files.indexOf(fs.head._1) }
                    val out = new java.util.ArrayList[java.util.Map[String, Any]]()
                    val it = groups.iterator
                    while (out.size < limit && it.hasNext) {
                        val (schema, fs) = it.next()
                        val keep = fs.map(_._1.getPath.toString).toSet
                        val df = spark.baseRelationToDataFrame(relation.copy(location = new SubsetIndex(relation.location, keep), dataSchema = schema)(spark))
                        val part = project(df, order, stored, nameOf)
                        if (cancelled.get) throw new QueryCancelled
                        stats.dataReads += 1
                        part.limit(limit - out.size).collectAsList().asScala.foreach { row =>
                            val m = rowToMap(row, part.columns)
                            order.foreach { n =>
                                val k = n.toLowerCase
                                val key = nameOf(n)
                                Option(m.get(key)).foreach { v =>
                                    if (text.contains(k)) m.put(key, String.valueOf(v))
                                    else target.get(k).flatten match {
                                        // one double column: whole numbers from integer files as doubles
                                        case Some(org.apache.spark.sql.types.DoubleType) if types(k).size > 1 =>
                                            v match {
                                                case n: java.lang.Integer => m.put(key, java.lang.Double.valueOf(n.doubleValue))
                                                case n: java.lang.Short => m.put(key, java.lang.Double.valueOf(n.doubleValue))
                                                case n: java.lang.Byte => m.put(key, java.lang.Double.valueOf(n.doubleValue))
                                                case _ => ()
                                            }
                                        // one decimal column: every value at the widest scale
                                        case Some(d: org.apache.spark.sql.types.DecimalType) if types(k).size > 1 =>
                                            m.put(key, new java.math.BigDecimal(String.valueOf(v)).setScale(d.scale).toPlainString)
                                        case _ => ()
                                    }
                                }
                            }
                            out.add(m)
                        }
                    }
                    QueryResult(order.map(nameOf).toList.asJava, out, path, format)
                }
            case None =>
                val assumed = readSchema.map(_.stamps).getOrElse(Nil)
                // A stored tinyint or smallint the sampled file lacks is read as
                // int: Parquet would otherwise truncate a wider value silently.
                val missing = dataOnly(stored).fields.filter(f => find(sampled, f.name).isEmpty).map { f =>
                    f.dataType match {
                        case org.apache.spark.sql.types.ByteType | org.apache.spark.sql.types.ShortType =>
                            f.copy(dataType = org.apache.spark.sql.types.IntegerType)
                        case _ => f
                    }
                } ++
                    assumed.filter(n => find(sampled, n).isEmpty && find(stored, n).isEmpty).map(n => StructField(n, StringType, nullable = true))
                val order = columnList(stored, sampled, partitions, assumed)
                val nameOf = namer(Nil)
                val wide = spark.baseRelationToDataFrame(relation.copy(dataSchema = StructType(sampled.fields ++ missing))(spark))
                def differentTypes(e: Exception): Nothing =
                    throw new IllegalStateException(
                        "The files at " + path + " hold a column under different types, and the query could not read every file's schema " +
                            "to reconcile them (more than " + maxScan + " data files, a file whose schema cannot be read, or the time to read " +
                            "them ran out). Rewrite the data with one type per column (for example with deleteBeforeWrite on the next run). Cause: " +
                            String.valueOf(e.getMessage).split("\n").head.take(300),
                        e
                    )
                def narrow(): QueryResult = {
                    val keepOrder = order.filter(n => plain.columns.exists(_.equalsIgnoreCase(n)))
                    try collect(project(plain, keepOrder, stored, nameOf), limit, path, format, stats, cancelled)
                    catch { case e2: Exception if typeConflict(e2) => differentTypes(e2) }
                }
                try collect(project(wide, order, stored, nameOf), limit, path, format, stats, cancelled)
                catch {
                    // Any failure of the wider read (a type ORC or Parquet cannot
                    // convert, whatever the error looks like): read exactly as the
                    // query always did, which fails only where that read fails.
                    case e: Exception if !e.isInstanceOf[QueryCancelled] && !cancelled.get && missing.nonEmpty =>
                        logger.warn(
                            "ObjectStoreQuery: " + path + ": reading the stored columns the sampled file lacks failed (" +
                                String.valueOf(e.getMessage).split("\n").head.take(200) + "); reading the sampled file's columns only"
                        )
                        narrow()
                    case e: Exception if !e.isInstanceOf[QueryCancelled] && typeConflict(e) => differentTypes(e)
                }
        }
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
        schemaScanMaxFiles: Int = FileSchemaScanMaxFiles,
        stats: ReadStats = new ReadStats(),
        scanBudgetMs: Long = FooterScanBudgetMs,
        cancelled: AtomicBoolean = new AtomicBoolean(false)
    ): QueryResult = {
        // Runs on the objectstore-query pool, which never created the session;
        // the Iceberg source resolves its catalog through the thread-active one.
        SparkSession.setActiveSession(spark)
        if (format != "iceberg")
            try readFiles(spark, path, format, limit, readSchema, schemaScanMaxFiles, scanBudgetMs, stats, cancelled)
            catch {
                case e: org.apache.hadoop.fs.UnsupportedFileSystemException =>
                    throw new DatrisException("Object store read failed (unsupported scheme on " + path + "): " + e.getMessage)
            }
        else
            try readIceberg(spark, path, limit, resolved, bucket)
            catch {
                case e: org.apache.hadoop.mapred.InvalidInputException =>
                    logger.info(s"ObjectStoreQuery: no data at $path yet (${e.getMessage}) — returning empty result")
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
