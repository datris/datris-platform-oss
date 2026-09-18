package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{PipelineStatus, ScratchResult}
import com.google.gson.{Gson, JsonArray, JsonObject, JsonParser}
import org.scalatest.funsuite.AnyFunSuite

/** Rollup of a scratch-destination job (plans/stories/scratch-destination-server.md).
  *
  *  `PipelineStatusUtil.classifyJob` picks the last event carrying a
  *  `scratchResult` and copies `resultUri`, `resultRowCount`, `resultExpiresAt`,
  *  `resultPreview` and `resultTruncated` onto `PipelineJobRollup`. The fields
  *  are null (boxed) on every job that carries no scratch result, so existing
  *  clients see today's payload unchanged.
  */
class ScratchRollupSpec extends AnyFunSuite {

    private val gson = new Gson
    private val uri = "s3a://unit-data/_scratch/scratch_pipe/job-1.jsonl"
    private val expires = "2026-09-18T12:00:00Z"

    private var clock = 1000L
    private def ev(process: String, state: String, code: String, description: String, scratchResult: ScratchResult = null): PipelineStatus = {
        clock += 1
        PipelineStatus(0, "t" + clock, "scratch_pipe", process, "pub-1", "job-1", "rows.csv", state, code, description, clock, scratchResult = scratchResult)
    }

    private def preview(n: Int): JsonArray = {
        val a = new JsonArray()
        (1 to n).foreach { i =>
            val o = new JsonObject()
            o.addProperty("id", i.toString)
            a.add(o)
        }
        a
    }

    private def result(rowCount: Long, previewRows: Int, truncated: Boolean): ScratchResult =
        ScratchResult(
            resultUri = uri,
            resultRowCount = rowCount,
            resultExpiresAt = expires,
            resultPreview = preview(previewRows),
            resultTruncated = truncated
        )

    private def previewOf(job: ai.datris.model.PipelineJobRollup): JsonArray =
        JsonParser.parseString(gson.toJson(job.resultPreview)).getAsJsonArray

    test("a DQ-warning scratch run rolls up as status warning with a resultUri") {
        val events = List(
            ev("JobRunner", "begin", "info", "Process started"),
            ev("DataQuality", "processing", "warning", "2 warning(s) were found while performing data quality rules"),
            ev("ScratchLoader", "end", "info", "Wrote 5 records", scratchResult = result(5, 5, truncated = false)),
            ev("JobRunner", "end", "info", "Process completed")
        )
        val job = PipelineStatusUtil.classifyJob(events)
        assert(job.status == "warning")
        assert(job.lastError == null)
        assert(job.resultUri == uri)
        assert(job.resultRowCount == 5L)
        assert(job.resultExpiresAt == expires)
    }

    test("resultPreview holds the first scratchInlineRows records and resultTruncated is true when rowCount exceeds the cap") {
        val events = List(
            ev("JobRunner", "begin", "info", "Process started"),
            ev("ScratchLoader", "end", "info", "Wrote 500 records", scratchResult = result(500, 200, truncated = true)),
            ev("JobRunner", "end", "info", "Process completed")
        )
        val job = PipelineStatusUtil.classifyJob(events)
        assert(job.status == "success")
        assert(job.resultRowCount == 500L)
        assert(job.resultTruncated == true)
        val p = previewOf(job)
        assert(p.size() == 200)
        assert(p.get(0).getAsJsonObject.get("id").getAsString == "1")
        assert(p.get(199).getAsJsonObject.get("id").getAsString == "200")
    }

    test("resultTruncated is false and the preview is the whole set at or below the cap") {
        val events = List(
            ev("JobRunner", "begin", "info", "Process started"),
            ev("ScratchLoader", "end", "info", "Wrote 3 records", scratchResult = result(3, 3, truncated = false)),
            ev("JobRunner", "end", "info", "Process completed")
        )
        val job = PipelineStatusUtil.classifyJob(events)
        assert(job.resultTruncated == false)
        assert(job.resultRowCount == 3L)
        assert(previewOf(job).size() == 3)
    }

    test("a DQ error aborts before the loader runs: rollup is error with no resultUri") {
        val events = List(
            ev("JobRunner", "begin", "info", "Process started"),
            ev("DataQuality", "begin", "info", "Process started"),
            ev(
                "DataQuality",
                "end",
                "error",
                "Aborting processing this pipeline, 1 error(s) were found while performing data quality rules:\nid must be numeric"
            ),
            ev("JobRunner", "end", "info", "Process completed, error: Aborting processing this pipeline, 1 error(s) were found\nstack")
        )
        val job = PipelineStatusUtil.classifyJob(events)
        assert(job.status == "error")
        assert(job.lastError.processName == "DataQuality")
        assert(job.resultUri == null)
        assert(job.resultRowCount == null)
        assert(job.resultExpiresAt == null)
        assert(job.resultPreview == null)
        assert(job.resultTruncated == null)
    }

    test("non-scratch jobs have null result fields") {
        val events = List(
            ev("JobRunner", "begin", "info", "Process started"),
            ev("PostgresLoader", "end", "info", "Process completed successfully"),
            ev("JobRunner", "end", "info", "Process completed")
        )
        val job = PipelineStatusUtil.classifyJob(events)
        assert(job.status == "success")
        assert(job.resultUri == null)
        assert(job.resultRowCount == null)
        assert(job.resultExpiresAt == null)
        assert(job.resultPreview == null)
        assert(job.resultTruncated == null)
        // Gson must not emit the new fields at all for an ordinary job
        val json = JsonParser.parseString(gson.toJson(job)).getAsJsonObject
        List("resultUri", "resultRowCount", "resultExpiresAt", "resultPreview", "resultTruncated").foreach { f =>
            assert(!json.has(f), s"non-scratch rollup must not carry '$f': $json")
        }
    }
}
