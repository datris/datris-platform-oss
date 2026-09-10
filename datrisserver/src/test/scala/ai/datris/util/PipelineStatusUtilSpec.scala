package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.PipelineStatus
import org.scalatest.funsuite.AnyFunSuite

/** Rollup classification of one job's event stream. Pins the contract agents
  * rely on: `lastError` names the destination loader that failed and carries
  * its own message, not JobRunner plus a stack trace. */
class PipelineStatusUtilSpec extends AnyFunSuite {

    private var clock = 1000L
    private def ev(process: String, state: String, code: String, description: String, aiSummary: String = null): PipelineStatus = {
        clock += 1
        PipelineStatus(0, "t" + clock, "orders", process, "pub-1", "job-1", "orders.csv", state, code, description, clock, aiSummary = aiSummary)
    }

    test("loader failure: lastError names the loader and carries its message, not the JobRunner stack") {
        val events = List(
            ev("JobRunner", "begin", "info", "Process started"),
            ev("PostgresLoader", "processing", "info", "Loading 6 rows"),
            ev("PostgresLoader", "end", "error", "PostgresLoader failed: connection refused: db:5432"),
            ev("JobRunner", "end", "info", "Process completed, error: PostgresLoader failed: connection refused: db:5432\njava.lang.RuntimeException: ...")
        )
        val job = PipelineStatusUtil.classifyJob(events)
        assert(job.status == "error")
        assert(job.lastError.processName == "PostgresLoader")
        assert(job.lastError.description == "PostgresLoader failed: connection refused: db:5432")
        assert(!job.lastError.description.contains("java.lang"))
    }

    test("two destinations, one fails: still one job, lastError is the failing loader") {
        val events = List(
            ev("JobRunner", "begin", "info", "Process started"),
            ev("MongoDBLoader", "end", "info", "Process completed successfully"),
            ev("SnowflakeLoader", "end", "error", "SnowflakeLoader failed: Table ORDERS does not exist"),
            ev("JobRunner", "end", "info", "Process completed, error: SnowflakeLoader failed: Table ORDERS does not exist\nstack")
        )
        val job = PipelineStatusUtil.classifyJob(events)
        assert(job.status == "error")
        assert(job.pipelineToken == "job-1")
        assert(job.lastError.processName == "SnowflakeLoader")
        assert(job.lastError.description == "SnowflakeLoader failed: Table ORDERS does not exist")
    }

    test("all destinations succeed: success with no lastError") {
        val events = List(
            ev("JobRunner", "begin", "info", "Process started"),
            ev("PostgresLoader", "end", "info", "Process completed successfully"),
            ev("QdrantLoader", "end", "info", "Process completed successfully"),
            ev("JobRunner", "end", "info", "Process completed")
        )
        val job = PipelineStatusUtil.classifyJob(events)
        assert(job.status == "success")
        assert(job.lastError == null)
    }

    test("job-thread failure (no loader involved) keeps the JobRunner error event") {
        val events = List(
            ev("JobRunner", "begin", "info", "Process started"),
            ev("DataQuality", "processing", "info", "Validating"),
            ev("JobRunner", "end", "error", "Process completed, error: java.lang.IllegalStateException: bad schema\n\tat ...")
        )
        val job = PipelineStatusUtil.classifyJob(events)
        assert(job.status == "error")
        assert(job.lastError.processName == "JobRunner")
    }

    test("AI suggestion headline is picked up independently of which event carries the error") {
        val events = List(
            ev("JobRunner", "begin", "info", "Process started"),
            ev("PostgresLoader", "end", "error", "PostgresLoader failed: connection refused"),
            ev("JobRunner", "end", "info", "Process completed, error: PostgresLoader failed: connection refused\nstack"),
            ev("JobRunner", "end", "info", "AI Suggested Fix: check the host", aiSummary = "check the host")
        )
        val job = PipelineStatusUtil.classifyJob(events)
        assert(job.status == "error")
        assert(job.lastError.processName == "PostgresLoader")
        assert(job.lastError.aiSummary == "check the host")
    }
}
