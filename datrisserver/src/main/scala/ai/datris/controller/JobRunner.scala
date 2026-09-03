package ai.datris.controller

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonElement, JsonParser}
import ai.datris.model.{DatrisEnvironment, DatrisException, TenantContext, Data}
import ai.datris.model.{CANCELLED, JobContext, RunLineage, RunLineageInput, RunLineageOutput}
import ai.datris.util._
import org.slf4j.{Logger, LoggerFactory}

import java.util.concurrent.{Executors, ThreadFactory}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.{Duration, DurationInt}

object JobRunner {
    // Dedicated thread pool for blocking I/O destination loaders.
    // Sized to handle concurrent jobs each fanning out to multiple destinations.
    // Daemon threads ensure the pool does not prevent JVM shutdown.
    private val destinationEC: ExecutionContext = {
        val tf: ThreadFactory = (r: Runnable) => {
            val t = new Thread(r)
            t.setDaemon(true)
            t
        }
        ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(20, tf))
    }

    private val destinationTimeoutMinutes: Int = 10

    /** Derive a per-job record count and data type from the job's Data shape.
     *  CSV/delimited → row count, "record". Unstructured (rawBytes) → 1, "document".
     *  JSON/XML → array length if rawData parses as a JSON array, else 1, "record". */
    private[controller] def deriveCountAndType(data: Data): (Int, String) = {
        if (data == null) return (0, null)
        if (data.rows != null && data.rows.nonEmpty) return (data.rows.size, "record")
        if (data.rawBytes != null) return (1, "document")
        if (data.rawData != null && data.rawData.nonEmpty) {
            try {
                val el: JsonElement = JsonParser.parseString(data.rawData)
                if (el != null && el.isJsonArray) return (el.getAsJsonArray.size(), "record")
            } catch { case _: Throwable => () }
            return (1, "record")
        }
        (0, null)
    }
}

class JobRunner(jobContext: JobContext) extends Runnable {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    def run(): Unit = {
        // Set tenant context for this background thread
        if (jobContext.tenantEnvironment != null) {
            TenantContext.set(jobContext.tenantEnvironment)
        }
        try {
            runInternal()
        } finally {
            TenantContext.clear()
        }
    }

    private def runInternal(): Unit = {
        val config = jobContext.config
        val statusUtil = jobContext.statusUtil
        val tenantEnv = jobContext.tenantEnvironment

        statusUtil.overrideProcessName(this.getClass.getSimpleName)
        val startedAtMs = System.currentTimeMillis()

        // Stamp the per-job record count and data type onto the status summary so
        // ops dashboards can sum items across pipeline jobs (and not have to
        // back-derive from tap logs, which omits direct uploads).
        val (recordCount, dataType) = JobRunner.deriveCountAndType(jobContext.data)
        statusUtil.setRecordCount(recordCount)
        statusUtil.setDataType(dataType)

        // Loader futures by lineage kind, captured so the error path can still
        // report which destinations finished before the failure.
        var loaderFutures: List[(String, Future[Unit])] = Nil

        try {
            statusUtil.info("begin", "Process started")

            // Preprocessor?
            val jobContextPreprocessed = {
                if (config.preprocessor != null)
                    new RestEndpointRunner(jobContext, config.preprocessor).process()
                else
                    jobContext
            }

            // Do data quality?
            if (config.dataQuality != null)
                new DataQuality(jobContextPreprocessed).process()

            // Transformations?
            val jobContextTransform = {
                if (config.transformation != null)
                    new Transformation(jobContextPreprocessed).process()
                else
                    jobContextPreprocessed
            }

            // Provenance stamp (opt-in per pipeline): constant per-run fields
            // appended once, after transformation, so every destination loader
            // below sees the same data. No-op when provenance.stamp is off.
            val jobContextStamped = ProvenanceStamper.stamp(jobContextTransform)

            // Run all destinations in parallel on the dedicated thread pool
            implicit val ec: ExecutionContext = JobRunner.destinationEC

            // Wrap each destination loader to propagate tenant context to the thread pool
            def withTenant[T](f: => T): T = {
                if (tenantEnv != null) TenantContext.set(tenantEnv)
                try { f }
                finally { TenantContext.clear() }
            }

            // Wrap a loader's `process()` body for scheduling on destinationEC. Two
            // jobs in one:
            //  1. Set/clear tenant context on the pool thread (was withTenant).
            //  2. Translate ANY Throwable — including fatal LinkageError /
            //     IllegalAccessError / OutOfMemoryError — into a RuntimeException
            //     so Scala's Future captures it. Without this, fatal errors leave
            //     the Future's Promise uncompleted, the executing thread dies, and
            //     Await.result below blocks for the full destinationTimeoutMinutes
            //     before throwing TimeoutException — the user sees PROCESSING for
            //     ~10 minutes after a failure that should have ended the job
            //     instantly. JobRunner is the right boundary for that translation:
            //     the JVM is still healthy enough to log + fail this job; we don't
            //     need to take down the whole server for one bad pipeline.
            def runLoader(loaderName: String)(body: => Unit): Future[Unit] = Future {
                try { withTenant(body) }
                catch {
                    case t: Throwable =>
                        throw new RuntimeException(loaderName + " failed: " + t.getClass.getName + ": " + t.getMessage, t)
                }
            }

            // Each entry is tagged with its lineage dataset kind (the same
            // kinds LineageService.datasets derives) so the run-lineage doc
            // written below can report per-destination outcomes.
            val destinationFutures: List[(String, Future[Unit])] = List(
                if (config.destination.objectStore != null)
                    Some("objectstore" -> runLoader("SparkObjectStoreLoader")(new SparkObjectStoreLoader(jobContextStamped).process()))
                else None,
                if (config.destination.database != null && config.destination.database.usePostgres)
                    Some("postgres" -> runLoader("PostgresLoader")(new PostgresLoader(jobContextStamped).process()))
                else None,
                if (config.destination.database != null && config.destination.database.useMongoDB)
                    Some("mongodb" -> runLoader("MongoDBLoader")(new MongoDBLoader(jobContextStamped).process()))
                else None,
                if (config.destination.database != null && config.destination.database.useSnowflake)
                    Some("snowflake" -> runLoader("SnowflakeLoader")(new SnowflakeLoader(jobContextStamped).process()))
                else None,
                if (config.destination.database != null && config.destination.database.useDatabricks)
                    Some("databricks" -> runLoader("DatabricksLoader")(new DatabricksLoader(jobContextStamped).process()))
                else None,
                if (config.destination.restEndpoint != null)
                    Some("rest" -> runLoader("RestEndpointRunner")(new RestEndpointRunner(jobContextStamped, config.destination.restEndpoint).process()))
                else None,
                if (config.destination.kafka != null)
                    Some("kafka" -> runLoader("KafkaLoader")(new KafkaLoader(jobContextStamped).process()))
                else None,
                if (config.destination.activeMQ != null)
                    Some("activemq" -> runLoader("ActiveMQLoader")(new ActiveMQLoader(jobContextStamped).process()))
                else None,
                if (config.destination.qdrant != null)
                    Some("qdrant" -> runLoader("QdrantLoader")(new QdrantLoader(jobContextStamped).process()))
                else None,
                if (config.destination.weaviate != null)
                    Some("weaviate" -> runLoader("WeaviateLoader")(new WeaviateLoader(jobContextStamped).process()))
                else None,
                if (config.destination.pgvector != null)
                    Some("pgvector" -> runLoader("PGVectorLoader")(new PGVectorLoader(jobContextStamped).process()))
                else None,
                if (config.destination.milvus != null)
                    Some("milvus" -> runLoader("MilvusLoader")(new MilvusLoader(jobContextStamped).process()))
                else None,
                if (config.destination.chroma != null)
                    Some("chroma" -> runLoader("ChromaLoader")(new ChromaLoader(jobContextStamped).process()))
                else None
            ).flatten
            loaderFutures = destinationFutures

            Await.result(Future.sequence(destinationFutures.map(_._2)), Duration(JobRunner.destinationTimeoutMinutes, "minutes"))

            statusUtil.overrideProcessName(this.getClass.getSimpleName)
            statusUtil.info("end", "Process completed")
            recordRunLineage(startedAtMs, recordCount, dataType, loaderFutures, "SUCCESS", null)
        } catch {
            // Catch Throwable (not Exception) for defense in depth. The runLoader
            // wrapper above already translates fatal errors into RuntimeException
            // at the Future site, but anything raised on this thread directly
            // (validator, preprocessor, transform) could still be a fatal Error.
            // VirtualMachineError (OOM, StackOverflow) gets logged + reported here
            // but the JVM may still be unhealthy — that's a separate concern.
            case e: Throwable =>
                val errorMessage = Throwables.getStackTraceAsString(e)
                statusUtil.error("end", "Process completed, error: " + errorMessage)
                recordRunLineage(
                    startedAtMs, recordCount, dataType, loaderFutures,
                    if (jobContext.state == CANCELLED || e.isInstanceOf[InterruptedException]) "CANCELLED" else "ERROR",
                    Option(e.getMessage).getOrElse(e.getClass.getName)
                )
                val fix = FixSuggestionUtil.suggest("pipeline", new Gson().toJson(jobContext.config), errorMessage)
                if (fix != null) {
                    logger.info("AI Fix Suggestion: " + fix.summary)
                    statusUtil.suggestion(fix)
                }
                // Recovery agent: a pipeline job ended in error — open an
                // incident (no-op unless enabled; one per resource; cooldowns
                // and the recovered-rule apply inside).
                try {
                    val trigger = new com.google.gson.JsonObject()
                    trigger.addProperty("pipelineToken", jobContext.pipelineToken)
                    trigger.addProperty("error", errorMessage.take(500))
                    if (fix != null) trigger.addProperty("aiSummary", fix.summary)
                    ai.datris.incident.IncidentRunner.open(ai.datris.incident.Incident.KindPipelineFailure, "pipeline", jobContext.config.name, trigger)
                } catch {
                    case ie: Exception => logger.debug("incident open skipped: " + ie.getMessage)
                }
                throw new DatrisException("Pipeline error: " + errorMessage)
        }
    }

    /** Write the run-lineage doc (what this run read and wrote). Per-destination
      * status comes from each loader future's completed value; a loader still
      * running when the job failed reports UNKNOWN rather than a guess. Must
      * never fail or delay the job — same guarantee as the provenance stamper. */
    private def recordRunLineage(
        startedAtMs: Long,
        recordCount: Int,
        dataType: String,
        loaders: List[(String, Future[Unit])],
        status: String,
        error: String
    ): Unit = {
        try {
            val config = jobContext.config
            val md = jobContext.metadata
            val datasets = LineageService.datasets(config).map(d => d.kind -> d).toMap
            val outputs = new java.util.ArrayList[RunLineageOutput]()
            loaders.foreach { case (kind, f) =>
                val ds = datasets.get(kind)
                val (st, err) = f.value match {
                    case Some(scala.util.Success(_)) => ("SUCCESS", null)
                    case Some(scala.util.Failure(t)) => ("ERROR", Option(t.getMessage).getOrElse(t.getClass.getName).take(500))
                    case None => ("UNKNOWN", null)
                }
                val coords =
                    if (ds.isDefined) ds.get.name.stripPrefix(kind + ":")
                    else if (kind == "rest" && config.destination.restEndpoint != null) config.destination.restEndpoint.endpoint
                    else null
                outputs.add(RunLineageOutput(
                    kind = kind,
                    coords = coords,
                    datasetId = ds.map(_.id).orNull,
                    status = st,
                    recordCount = if (st == "SUCCESS") recordCount else 0,
                    error = err
                ))
            }
            val input =
                if (md != null && md.tapName != null)
                    RunLineageInput("tap", md.tapName, md.tapRunTime, md.tapScriptSha, md.tapSource, md.dataFileName)
                else
                    RunLineageInput("upload", filename = if (md != null) md.dataFileName else null)
            val now = System.currentTimeMillis()
            RunLineageIO.write(RunLineage(
                runId = jobContext.pipelineToken,
                pipeline = config.name,
                configVersion = if (config.version > 0) config.version else 1,
                input = input,
                outputs = outputs,
                recordCount = recordCount,
                dataType = dataType,
                startedAt = java.time.Instant.ofEpochMilli(startedAtMs).toString,
                completedAt = java.time.Instant.ofEpochMilli(now).toString,
                durationMs = now - startedAtMs,
                status = status
            ))
        } catch {
            case e: Exception =>
                logger.warn("run-lineage write skipped for " + jobContext.pipelineToken + ": " + e.getMessage)
        }
    }
}
