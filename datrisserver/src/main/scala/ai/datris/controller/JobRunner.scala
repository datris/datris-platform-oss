package ai.datris.controller

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.common.base.Throwables
import com.google.gson.Gson
import ai.datris.model.{DatrisEnvironment, DatrisException, TenantContext}
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

            val destinationFutures = List(
                if (config.destination.objectStore != null)
                    Some(Future(withTenant(new SparkObjectStoreLoader(jobContextTransform).process())))
                else None,

                if (config.destination.database != null && config.destination.database.usePostgres)
                    Some(Future(withTenant(new PostgresLoader(jobContextTransform).process())))
                else None,

                if (config.destination.database != null && config.destination.database.useMongoDB)
                    Some(Future(withTenant(new MongoDBLoader(jobContextTransform).process())))
                else None,

                if (config.destination.restEndpoint != null)
                    Some(Future(withTenant(new RestEndpointRunner(jobContextTransform, config.destination.restEndpoint).process())))
                else None,

                if (config.destination.kafka != null)
                    Some(Future(withTenant(new KafkaLoader(jobContextTransform).process())))
                else None,

                if (config.destination.activeMQ != null)
                    Some(Future(withTenant(new ActiveMQLoader(jobContextTransform).process())))
                else None,

                if (config.destination.qdrant != null)
                    Some(Future(withTenant(new QdrantLoader(jobContextTransform).process())))
                else None,

                if (config.destination.weaviate != null)
                    Some(Future(withTenant(new WeaviateLoader(jobContextTransform).process())))
                else None,

                if (config.destination.pgvector != null)
                    Some(Future(withTenant(new PGVectorLoader(jobContextTransform).process())))
                else None,

                if (config.destination.milvus != null)
                    Some(Future(withTenant(new MilvusLoader(jobContextTransform).process())))
                else None,

                if (config.destination.chroma != null)
                    Some(Future(withTenant(new ChromaLoader(jobContextTransform).process())))
                else None

            ).flatten

            Await.result(Future.sequence(destinationFutures), Duration(JobRunner.destinationTimeoutMinutes, "minutes"))

            statusUtil.overrideProcessName(this.getClass.getSimpleName)
            statusUtil.info("end", "Process completed")
        } catch {
            case e: Exception =>
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