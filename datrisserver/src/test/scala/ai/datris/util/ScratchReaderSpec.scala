package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.JsonArray
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path => NioPath}
import scala.collection.JavaConverters._

/** Paging a scratch result (plans/stories/scratch-result-endpoint-retention.md).
  *
  *  `GET /api/v1/pipeline/result` resolves a token to a `PipelineJobRollup` (via
  *  `PipelineStatusUtil`, which needs Mongo and is therefore E2E-only), then
  *  slices the JSON-lines object the run wrote. Everything after resolution is
  *  pure and is what this spec pins:
  *
  *  {{{
  *  object ScratchReader {
  *      case class ScratchResultPage(
  *          records: JsonArray, rowCount: Long, returnedCount: Int, offset: Long,
  *          truncated: Boolean, resultUri: String, resultExpiresAt: String)
  *
  *      // offset: None / negative -> 0. limit: None / <= 0 -> settings.inlineRows,
  *      // and never more than settings.inlineRows. The key is recomputed as
  *      // ScratchPaths.key(job.pipeline, job.pipelineToken) under settings.rootUri;
  *      // job.resultUri is only the "this is a scratch job" flag and an echoed field.
  *      def read(settings: ScratchLoader.Settings, job: PipelineJobRollup,
  *               offset: Option[Long], limit: Option[Int]): ScratchResultPage
  *  }
  *  // Carries the HTTP status the controller answers with: 404 when the job has
  *  // no resultUri (not a scratch job) or the token is unknown, 410 when the
  *  // object is gone, 400 when a publishertoken resolves to more than one job.
  *  class ScratchResultException(val status: Int, message: String) extends DatrisException(message)
  *  }}}
  *
  *  `settings.rootUri` is a `file://` directory here, the same seam
  *  `ScratchLoaderSpec` uses, so no MinIO or SparkSession is involved.
  */
class ScratchReaderSpec extends AnyFunSuite {

    private val PIPELINE = "scratch_pipe"
    private val TOKEN = "job-token-1"

    private def tmpRoot(): NioPath = Files.createTempDirectory("scratch-reader-spec")

    private def settings(root: NioPath, inlineRows: Int = 200): ScratchLoader.Settings =
        ScratchLoader.Settings(root.toUri.toString.stripSuffix("/"), inlineRows, 24)

    /** Writes `{"id":0}` .. `{"id":n-1}` at the key ScratchPaths computes for this job. */
    private def writeFixture(root: NioPath, rows: Int, pipeline: String = PIPELINE, token: String = TOKEN): NioPath = {
        val file = root.resolve(ScratchPaths.key(pipeline, token))
        Files.createDirectories(file.getParent)
        val lines = (0 until rows).map(i => s"""{"id":$i,"name":"row$i"}""").asJava
        Files.write(file, lines, StandardCharsets.UTF_8)
        file
    }

    private def job(rowCount: Long, resultUri: String, pipeline: String = PIPELINE, token: String = TOKEN): PipelineJobRollup =
        PipelineJobRollup(
            pipelineToken = token,
            pipeline = pipeline,
            filename = "rows.csv",
            status = "success",
            startedAt = "2026-09-18T00:00:00Z",
            lastEventAt = "2026-09-18T00:00:01Z",
            elapsed = "1s",
            lastError = null,
            resultUri = resultUri,
            resultRowCount = if (rowCount < 0) null else java.lang.Long.valueOf(rowCount),
            resultExpiresAt = "2026-09-19T00:00:00Z",
            resultPreview = new JsonArray(),
            resultTruncated = java.lang.Boolean.TRUE
        )

    private def scratchJob(root: NioPath, rowCount: Long): PipelineJobRollup =
        job(rowCount, settings(root).rootUri + "/" + ScratchPaths.key(PIPELINE, TOKEN))

    private def ids(page: ScratchReader.ScratchResultPage): Seq[Int] =
        page.records.asScala.map(_.getAsJsonObject.get("id").getAsInt).toSeq

    // --- slicing -----------------------------------------------------------------

    test("a slice returns exactly the requested records") {
        val root = tmpRoot()
        val file = writeFixture(root, 300)
        val mtimeBefore = Files.getLastModifiedTime(file)

        val page = ScratchReader.read(settings(root), scratchJob(root, 300), Some(100L), Some(50))

        assert(page.records.size() == 50, s"expected 50 records, got ${page.records.size()}")
        assert(ids(page) == (100 to 149), "records 100-149 in file order")
        assert(page.records.get(0).getAsJsonObject.get("name").getAsString == "row100")
        assert(page.returnedCount == 50)
        assert(page.offset == 100L)
        assert(page.rowCount == 300L)
        assert(page.truncated, "100 + 50 < 300 must be truncated")
        assert(page.resultUri == scratchJob(root, 300).resultUri, "resultUri is echoed off the rollup")
        assert(page.resultExpiresAt == "2026-09-19T00:00:00Z", "resultExpiresAt is echoed off the rollup")
        assert(Files.getLastModifiedTime(file) == mtimeBefore, "paging must not rewrite the object (nothing re-runs)")
        assert(Files.readAllLines(file).size() == 300, "paging must not rewrite the object (nothing re-runs)")
    }

    test("limit is clamped to scratchInlineRows") {
        val root = tmpRoot()
        writeFixture(root, 300)

        val page = ScratchReader.read(settings(root, inlineRows = 200), scratchJob(root, 300), Some(0L), Some(5000))

        assert(page.returnedCount == 200, s"5000 with inlineRows 200 must clamp to 200, got ${page.returnedCount}")
        assert(page.records.size() == 200)
        assert(ids(page) == (0 to 199))
        assert(page.truncated)
    }

    test("missing or non-positive limit falls back to scratchInlineRows") {
        val root = tmpRoot()
        writeFixture(root, 300)
        val s = settings(root, inlineRows = 25)

        val missing = ScratchReader.read(s, scratchJob(root, 300), None, None)
        assert(missing.returnedCount == 25, s"missing limit must default to inlineRows (25), got ${missing.returnedCount}")
        assert(ids(missing) == (0 to 24))
        assert(missing.offset == 0L, "missing offset defaults to 0")

        val zero = ScratchReader.read(s, scratchJob(root, 300), Some(0L), Some(0))
        assert(zero.returnedCount == 25, s"limit=0 must default to inlineRows (25), got ${zero.returnedCount}")

        val negative = ScratchReader.read(s, scratchJob(root, 300), Some(0L), Some(-7))
        assert(negative.returnedCount == 25, s"limit=-7 must default to inlineRows (25), got ${negative.returnedCount}")
    }

    test("negative offset is treated as 0") {
        val root = tmpRoot()
        writeFixture(root, 300)

        val page = ScratchReader.read(settings(root), scratchJob(root, 300), Some(-40L), Some(10))

        assert(page.offset == 0L, s"negative offset must be reported as 0, got ${page.offset}")
        assert(ids(page) == (0 to 9), "records start at the first line")
        assert(page.returnedCount == 10)
        assert(page.truncated)
    }

    test("offset beyond rowCount returns no records and truncated=false") {
        val root = tmpRoot()
        writeFixture(root, 300)

        val page = ScratchReader.read(settings(root), scratchJob(root, 300), Some(300L), Some(50))
        assert(page.records.size() == 0, s"offset == rowCount must be empty, got ${page.records}")
        assert(page.returnedCount == 0)
        assert(!page.truncated)
        assert(page.offset == 300L)
        assert(page.rowCount == 300L)

        val far = ScratchReader.read(settings(root), scratchJob(root, 300), Some(100000L), Some(50))
        assert(far.records.size() == 0)
        assert(far.returnedCount == 0)
        assert(!far.truncated)
    }

    test("the last page has truncated=false") {
        val root = tmpRoot()
        writeFixture(root, 300)

        // The manual Verify step: offset=250&limit=100 -> 50 records, truncated false
        val page = ScratchReader.read(settings(root), scratchJob(root, 300), Some(250L), Some(100))
        assert(page.returnedCount == 50, s"only 50 rows remain past 250, got ${page.returnedCount}")
        assert(ids(page) == (250 to 299))
        assert(!page.truncated, "250 + 50 == 300 is the end")
        assert(page.rowCount == 300L)

        // An exact-fit final page is also the end
        val exact = ScratchReader.read(settings(root), scratchJob(root, 300), Some(200L), Some(100))
        assert(exact.returnedCount == 100)
        assert(!exact.truncated)
    }

    // --- status codes and key resolution -------------------------------------------

    test("a job with no resultUri is a 404, not an empty page") {
        val root = tmpRoot()
        // Even if a file happens to exist at the computed key, a non-scratch job
        // has no result to page.
        writeFixture(root, 3)

        val e = intercept[ScratchResultException] {
            ScratchReader.read(settings(root), job(-1, resultUri = null), Some(0L), Some(10))
        }
        assert(e.status == 404, s"expected 404, got ${e.status}")
        assert(
            e.getMessage != null && e.getMessage.toLowerCase.contains("scratch"),
            s"message must say only scratch pipelines have a result, got: ${e.getMessage}"
        )
        // Story live-read-naming: the body names Live Read but keeps "scratch"
        // so an old CLI matching on the word still explains it.
        assert(
            e.getMessage.toLowerCase.contains("live read"),
            s"404 message must name Live Read, got: ${e.getMessage}"
        )
    }

    test("a missing object is a 410") {
        val root = tmpRoot()
        // Nothing written under root: the sweep (or a bucket wipe) took it.
        val e = intercept[ScratchResultException] {
            ScratchReader.read(settings(root), scratchJob(root, 300), Some(0L), Some(10))
        }
        assert(e.status == 410, s"expected 410, got ${e.status}")
        assert(
            e.getMessage != null && e.getMessage.toLowerCase.contains("run the pipeline again"),
            s"message must tell the caller to run it again, got: ${e.getMessage}"
        )
        assert(
            e.getMessage.contains("Live Read results expire after"),
            s"410 message must name Live Read, got: ${e.getMessage}"
        )
    }

    test("the key is recomputed from the rollup's pipeline + token, not read off resultUri") {
        val root = tmpRoot()
        // The real object, at the key ScratchPaths computes for this job.
        writeFixture(root, 5)
        // A decoy object somewhere else under the same root, holding different rows.
        val decoyKey = ScratchPaths.key("other_pipe", "other-token")
        val decoy = root.resolve(decoyKey)
        Files.createDirectories(decoy.getParent)
        Files.write(decoy, (0 until 5).map(i => s"""{"id":${900 + i}}""").asJava, StandardCharsets.UTF_8)

        // The stored resultUri points at the decoy; the reader must still open the recomputed key.
        val decoyUri = settings(root).rootUri + "/" + decoyKey
        val page = ScratchReader.read(settings(root), job(5, resultUri = decoyUri), Some(0L), Some(10))
        assert(ids(page) == (0 to 4), s"records must come from ScratchPaths.key(pipeline, token), not resultUri; got ${ids(page)}")
        assert(page.resultUri == decoyUri, "resultUri is echoed as stored, never opened")

        // A resultUri that points nowhere is still fine while the recomputed key exists (200, not 410)
        val nowhere = ScratchReader.read(settings(root), job(5, resultUri = "s3a://elsewhere-data/_scratch/x/y.jsonl"), Some(0L), Some(10))
        assert(ids(nowhere) == (0 to 4))

        // And a client-shaped traversal in the token never resolves to a key at all
        intercept[Exception] {
            ScratchReader.read(settings(root), job(5, resultUri = decoyUri, token = "../other_pipe/other-token"), Some(0L), Some(10))
        }
    }
}
