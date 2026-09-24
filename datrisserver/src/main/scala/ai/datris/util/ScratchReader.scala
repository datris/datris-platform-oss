package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.{JsonArray, JsonParser}
import org.apache.hadoop.fs.Path
import org.slf4j.{Logger, LoggerFactory}

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import scala.collection.JavaConverters._

/** Carries the HTTP status `GET /api/v1/pipeline/result` answers with: 404 when
  * the job has no resultUri (not a scratch job) or the token is unknown, 410
  * when the object is gone (swept or expired), 400 when a publishertoken
  * resolves to more than one job.
  */
class ScratchResultException(val status: Int, message: String) extends DatrisException(message)

/** Paging a scratch result: resolve a token to its `PipelineJobRollup`, then
  * slice the JSON-lines object the run wrote. The object key is always
  * recomputed server-side from the rollup's pipeline + token; the stored
  * `resultUri` is only the "this was a scratch job" flag and an echoed field.
  */
object ScratchReader {
    private val logger: Logger = LoggerFactory.getLogger(ScratchReader.getClass)

    case class ScratchResultPage(
        records: JsonArray,
        rowCount: Long,
        returnedCount: Int,
        offset: Long,
        truncated: Boolean,
        resultUri: String,
        resultExpiresAt: String
    )

    /** Token → rollup. `publishertoken` wins when both are sent, as
      * `/pipeline/status` does today; it must resolve to exactly one scratch job
      * (zero → 404, more than one → 400 "pass pipelinetoken").
      */
    def resolve(pipelinetoken: String, publishertoken: String): PipelineJobRollup = {
        if (publishertoken != null) {
            val jobs = PipelineStatusUtil.getPipelineStatusByPublisherWithRollup(publishertoken).rollup.jobs.asScala.toList
            val scratchJobs = jobs.filter(_.resultUri != null)
            if (jobs.isEmpty)
                throw new ScratchResultException(404, "No pipeline run found for publishertoken '" + publishertoken + "'")
            if (scratchJobs.isEmpty)
                throw new ScratchResultException(
                    404,
                    "Only Live Read (scratch) pipelines have a result; publishertoken '" + publishertoken + "' has no Live Read result"
                )
            if (scratchJobs.size > 1)
                throw new ScratchResultException(
                    400,
                    "publishertoken '" + publishertoken + "' resolves to " + scratchJobs.size + " scratch jobs; pass pipelinetoken to select one"
                )
            scratchJobs.head
        } else if (pipelinetoken != null) {
            val jobs = PipelineStatusUtil.getPipelineStatusWithRollup(pipelinetoken).rollup.jobs.asScala.toList
            if (jobs.isEmpty)
                throw new ScratchResultException(404, "No pipeline run found for pipelinetoken '" + pipelinetoken + "'")
            jobs.head
        } else
            throw new ScratchResultException(400, "pipelinetoken or publishertoken is required")
    }

    /** Slice the scratch object for `job`. offset: None / negative → 0. limit:
      * None / <= 0 → settings.inlineRows, and never more than settings.inlineRows.
      * Streams the file: skips `offset` lines, reads at most `limit`, closes.
      * Never loads the whole object and never counts lines for rowCount.
      */
    def read(settings: ScratchLoader.Settings, job: PipelineJobRollup, offset: Option[Long], limit: Option[Int]): ScratchResultPage = {
        if (job.resultUri == null)
            throw new ScratchResultException(404, "Only Live Read (scratch) pipelines have a result; job '" + job.pipelineToken + "' did not write one")

        val effectiveOffset = math.max(0L, offset.getOrElse(0L))
        val effectiveLimit = {
            val requested = limit.getOrElse(settings.inlineRows)
            if (requested <= 0) settings.inlineRows else math.min(requested, settings.inlineRows)
        }
        val rowCount: Long = if (job.resultRowCount == null) 0L else job.resultRowCount.longValue()

        // Server-side key: never read off resultUri, never accept a client key.
        val key = ScratchPaths.key(job.pipeline, job.pipelineToken)
        val path = new Path(settings.rootUri.stripSuffix("/") + "/" + key)
        val fs = path.getFileSystem(ScratchLoader.hadoopConfiguration(settings.rootUri))
        if (!fs.exists(path))
            throw new ScratchResultException(
                410,
                "Live Read results expire after " + settings.retentionHours + " hour(s); this one is gone — run the pipeline again"
            )

        val records = new JsonArray()
        if (effectiveOffset < rowCount) {
            val reader = new BufferedReader(new InputStreamReader(fs.open(path), StandardCharsets.UTF_8))
            try {
                var skipped = 0L
                var line = reader.readLine()
                while (line != null && skipped < effectiveOffset) {
                    skipped += 1
                    line = reader.readLine()
                }
                while (line != null && records.size() < effectiveLimit) {
                    if (line.trim.nonEmpty) records.add(JsonParser.parseString(line))
                    line = if (records.size() < effectiveLimit) reader.readLine() else null
                }
            } finally {
                reader.close()
            }
        }

        val returned = records.size()
        logger.info("Scratch result page for " + job.pipelineToken + ": offset=" + effectiveOffset + ", returned=" + returned + ", rowCount=" + rowCount)
        ScratchResultPage(
            records = records,
            rowCount = rowCount,
            returnedCount = returned,
            offset = effectiveOffset,
            truncated = effectiveOffset + returned < rowCount,
            resultUri = job.resultUri,
            resultExpiresAt = job.resultExpiresAt
        )
    }
}
