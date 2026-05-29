package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.{DatrisEnvironment, DatrisException}
import org.apache.spark.sql.Row
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

    case class QueryResult(
        columns: java.util.List[String],
        rows: java.util.List[java.util.Map[String, Any]],
        path: String,
        format: String
    )

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
            throw new DatrisException("Pipeline '" + pipelineName + "' does not have an objectStore destination — use query_postgres / query_mongodb / search_* depending on the actual destination type.")

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

        logger.info(s"ObjectStoreQuery: pipeline=$pipelineName, path=$path, format=$format, limit=$cappedLimit, jobGroup=$jobGroup, timeout=${queryTimeoutSec}s")

        // Run the Spark read on the dedicated executor and Await it with a
        // wall-clock cap. A fatal error inside Spark's parmap (LinkageError,
        // NoSuchMethodError, etc.) leaves Spark's internal Promise uncompleted
        // and the driver thread would block forever — the timeout is our only
        // safety net for that bug class.
        val readFuture: Future[QueryResult] = Future {
            try {
                val df = spark.read.format(format).load(path)
                val columns = df.columns.toList.asJava
                val rows = df.limit(cappedLimit).collectAsList().asScala.map(row => rowToMap(row, df.columns)).asJava
                QueryResult(columns, rows, path, format)
            } catch {
                case e: org.apache.hadoop.mapred.InvalidInputException =>
                    logger.info(s"ObjectStoreQuery: no data at $path yet (${e.getMessage}) — returning empty result")
                    QueryResult(new java.util.ArrayList[String](), new java.util.ArrayList[java.util.Map[String, Any]](), path, format)
                case e: org.apache.hadoop.fs.UnsupportedFileSystemException =>
                    throw new DatrisException("Object store read failed (unsupported scheme on " + path + "): " + e.getMessage)
            }
        }(queryEC)

        try Await.result(readFuture, queryTimeoutSec.seconds)
        catch {
            case _: TimeoutException =>
                logger.warn(s"ObjectStoreQuery: timed out after ${queryTimeoutSec}s for pipeline=$pipelineName — cancelling job group $jobGroup")
                try spark.sparkContext.cancelJobGroup(jobGroup) catch { case _: Throwable => () }
                throw new DatrisException("Object store query timed out after " + queryTimeoutSec + "s. The Spark read did not complete — most often a classpath/version mismatch (e.g. hadoop-aws vs. hadoop-common). Check server logs for NoSuchMethodError, IllegalAccessError, or similar fatal Throwables. The wall-clock limit is tunable via DATRIS_OBJECTSTORE_QUERY_TIMEOUT_SEC.")
        }
        finally spark.sparkContext.clearJobGroup()
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
        case null                            => null
        case s: String                       => s
        case b: java.lang.Boolean            => b
        // BigDecimal comes before java.lang.Number (BigDecimal IS-A Number) so
        // we preserve exact precision via toPlainString instead of letting Gson
        // serialize it via Number's default behavior.
        case bd: java.math.BigDecimal        => bd.toPlainString
        case n: java.lang.Number             => n
        case ts: java.sql.Timestamp          => ts.toInstant.toString
        case d: java.sql.Date                => d.toString
        case ba: Array[Byte]                 => java.util.Base64.getEncoder.encodeToString(ba)
        case seq: scala.collection.Seq[_]    => seq.map(sparkValueToJson).asJava
        case arr: Array[_]                   => arr.toSeq.map(sparkValueToJson).asJava
        case m: scala.collection.Map[_, _]   => m.map { case (k, vv) => (k.toString, sparkValueToJson(vv)) }.asJava
        case r: Row                          => rowToMap(r, r.schema.fieldNames)
        case other                           => other.toString
    }
}
