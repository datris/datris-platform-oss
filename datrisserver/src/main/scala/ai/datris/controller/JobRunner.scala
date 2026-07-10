package ai.datris.controller

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonElement, JsonParser}
import ai.datris.model.{DatrisEnvironment, DatrisException, TenantContext, Data}
import ai.datris.model.JobContext
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

        // Stamp the per-job record count and data type onto the status summary so
        // ops dashboards can sum items across pipeline jobs (and not have to
        // back-derive from tap logs, which omits direct uploads).
        val (recordCount, dataType) = JobRunner.deriveCountAndType(jobContext.data)
        statusUtil.setRecordCount(recordCount)
        statusUtil.setDataType(dataType)

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
            if(config.dataQuality != null)
                new DataQuality(jobContextPreprocessed).process()

            // Transformations?
            val jobContextTransform = {
                if(config.transformation != null)
                    new Transformation(jobContextPreprocessed).process()
                else
                    jobContextPreprocessed
            }

            // Run all destinations in parallel on the dedicated thread pool
            implicit val ec: ExecutionContext = JobRunner.destinationEC

            // Wrap each destination loader to propagate tenant context to the thread pool
            def withTenant[T](f: => T): T = {
                if (tenantEnv != null) TenantContext.set(tenantEnv)
                try { f } finally { TenantContext.clear() }
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

            val destinationFutures = List(
                if (config.destination.objectStore != null)
                    Some(runLoader("SparkObjectStoreLoader")(new SparkObjectStoreLoader(jobContextTransform).process()))
                else None,

                if (config.destination.database != null && config.destination.database.usePostgres)
                    Some(runLoader("PostgresLoader")(new PostgresLoader(jobContextTransform).process()))
                else None,

                if (config.destination.database != null && config.destination.database.useMongoDB)
                    Some(runLoader("MongoDBLoader")(new MongoDBLoader(jobContextTransform).process()))
                else None,

                if (config.destination.database != null && config.destination.database.useSnowflake)
                    Some(runLoader("SnowflakeLoader")(new SnowflakeLoader(jobContextTransform).process()))
                else None,

                if (config.destination.database != null && config.destination.database.useDatabricks)
                    Some(runLoader("DatabricksLoader")(new DatabricksLoader(jobContextTransform).process()))
                else None,

                if (config.destination.restEndpoint != null)
                    Some(runLoader("RestEndpointRunner")(new RestEndpointRunner(jobContextTransform, config.destination.restEndpoint).process()))
                else None,

                if (config.destination.kafka != null)
                    Some(runLoader("KafkaLoader")(new KafkaLoader(jobContextTransform).process()))
                else None,

                if (config.destination.activeMQ != null)
                    Some(runLoader("ActiveMQLoader")(new ActiveMQLoader(jobContextTransform).process()))
                else None,

                if (config.destination.qdrant != null)
                    Some(runLoader("QdrantLoader")(new QdrantLoader(jobContextTransform).process()))
                else None,

                if (config.destination.weaviate != null)
                    Some(runLoader("WeaviateLoader")(new WeaviateLoader(jobContextTransform).process()))
                else None,

                if (config.destination.pgvector != null)
                    Some(runLoader("PGVectorLoader")(new PGVectorLoader(jobContextTransform).process()))
                else None,

                if (config.destination.milvus != null)
                    Some(runLoader("MilvusLoader")(new MilvusLoader(jobContextTransform).process()))
                else None,

                if (config.destination.chroma != null)
                    Some(runLoader("ChromaLoader")(new ChromaLoader(jobContextTransform).process()))
                else None

            ).flatten

            Await.result(Future.sequence(destinationFutures), Duration(JobRunner.destinationTimeoutMinutes, "minutes"))

            statusUtil.overrideProcessName(this.getClass.getSimpleName)
            statusUtil.info("end", "Process completed")
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
                val aiExplanation = getAIErrorExplanation(errorMessage)
                if (aiExplanation != null) {
                    logger.info("AI Error Explanation: " + aiExplanation)
                    statusUtil.info("end", "AI Explanation: " + aiExplanation)
                }
                throw new DatrisException("Pipeline error: " + errorMessage)
        }
    }

    private def getAIErrorExplanation(errorMessage: String): String = {
        try {
            if (!DatrisEnvironment.current.aiEnabled || DatrisEnvironment.current.aiConfig == null)
                return null

            val configJson = new Gson().toJson(jobContext.config)
            // Truncate config and error to avoid exceeding context
            val truncatedConfig = if (configJson.length > 2000) configJson.substring(0, 2000) + "..." else configJson
            val truncatedError = if (errorMessage.length > 2000) errorMessage.substring(0, 2000) + "..." else errorMessage

            val prompt =
                s"""You are a data pipeline error analyst. A pipeline job failed with the error below.
                   |Explain in 2-3 concise sentences what went wrong and how to fix it.
                   |Do NOT repeat the error message. Focus on the root cause and actionable fix.
                   |
                   |Pipeline configuration:
                   |$truncatedConfig
                   |
                   |Error:
                   |$truncatedError""".stripMargin

            val responseText = AIUtil.callAI(prompt)
            AIUtil.extractText(responseText).trim
        } catch {
            case _: Exception => null
        }
    }
}