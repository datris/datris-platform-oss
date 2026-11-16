package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.Gson
import ai.datris.model.{DatrisEnvironment, PipelineStatus, PipelineStatusResponse, PipelineStatusSummary}
import ai.datris.util.{APIKeyValidator, NoSQLDbUtil, PipelineConfigIO, PipelineStatusUtil, ScratchLoader, ScratchReader, ScratchResultException}
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

object PipelineStatusAPIController {

    /** The 404 [[ScratchReader.resolve]] answers for a token with no run. */
    def unknownToken(pipelinetoken: String, publishertoken: String): ScratchResultException =
        // Same precedence as ScratchReader.resolve: publishertoken wins.
        if (publishertoken != null) new ScratchResultException(404, "No pipeline run found for publishertoken '" + publishertoken + "'")
        else new ScratchResultException(404, "No pipeline run found for pipelinetoken '" + pipelinetoken + "'")

    /** Pipeline names a restricted user's session may read; None for everyone else. */
    private def visibleNames(request: jakarta.servlet.http.HttpServletRequest): Option[Set[String]] =
        if (!ai.datris.auth.CapabilityCheck.isRestrictedSession(request)) None
        else
            Some(
                PipelineAPIController
                    .visiblePipelines(request, PipelineConfigIO.readAll(DatrisEnvironment.current.pipelineTableName))
                    .map(_.name)
                    .toSet
            )

    /** GET /pipeline/status for a restricted user's session: summary rows of
      * pipelines outside the grants are dropped; a token query that touches
      * any hidden pipeline is 404 (as if the token were unknown). Everyone
      * else: `data` unchanged. */
    def restrictedView(request: jakarta.servlet.http.HttpServletRequest, data: AnyRef): Either[ResponseEntity[String], AnyRef] =
        visibleNames(request) match {
            case None => Right(data)
            case Some(visible) => filterFor(visible, data)
        }

    /** Pure core of [[restrictedView]]. */
    def filterFor(visible: Set[String], data: AnyRef): Either[ResponseEntity[String], AnyRef] = {
        import scala.collection.JavaConverters._
        def hidden(names: Seq[String]): Boolean = names.exists(n => n != null && !visible.contains(n))
        val notFound = ResponseEntity.status(HttpStatus.NOT_FOUND).body[String](ApiErrors.errorBody("Pipeline status not found"))
        data match {
            case r: PipelineStatusResponse =>
                val events = Option(r.events).map(_.asScala.toSeq).getOrElse(Nil)
                if (hidden(events.map(_.pipeline))) Left(notFound) else Right(r)
            case l: java.util.List[_] =>
                val items = l.asScala.toSeq
                if (items.forall(_.isInstanceOf[PipelineStatusSummary]))
                    Right(items.collect { case s: PipelineStatusSummary if s.pipeline != null && visible.contains(s.pipeline) => s }.asJava)
                else if (hidden(items.collect { case e: PipelineStatus => e.pipeline })) Left(notFound)
                else Right(l)
            case other => Right(other)
        }
    }
}

@RestController
@RequestMapping(Array("/api/v1"))
class PipelineStatusAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[PipelineStatusAPIController])

    @GetMapping(path = Array("/pipeline/status"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getPipelineStatus(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam(required = false) pipelinetoken: String,
        @RequestParam(required = false) publishertoken: String,
        @RequestParam(required = false) pipelinename: String,
        @RequestParam(required = false) page: String,
        @RequestParam(required = false) withrollup: String,
        request: jakarta.servlet.http.HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info(
                "API endpoint GET /pipeline/status called with pipelinetoken: " + pipelinetoken + ", publishertoken: " + publishertoken + ", pipelinename: " + pipelinename + ", page: " + page + ", withrollup: " + withrollup
            )
            APIKeyValidator.validate(apiKey)

            val rollup = withrollup != null && withrollup.equalsIgnoreCase("true")

            // publishertoken wins when both are sent — it's the broader query and a
            // single pipelineToken's rows are a strict subset of its publisherToken's rows.
            // withrollup=true is only meaningful for token queries (publisher/pipeline);
            // the paginated summary path returns its own rollup-equivalent shape.
            val data: AnyRef = {
                if (publishertoken != null) {
                    if (rollup) PipelineStatusUtil.getPipelineStatusByPublisherWithRollup(publishertoken)
                    else PipelineStatusUtil.getPipelineStatusByPublisher(publishertoken)
                } else if (pipelinetoken == null) {
                    val pageNbr = {
                        if (page == null)
                            1
                        else
                            page.toInt
                    }
                    PipelineStatusUtil.getPipelineStatusSummary(pipelinename, pageNbr)
                } else {
                    if (rollup) PipelineStatusUtil.getPipelineStatusWithRollup(pipelinetoken)
                    else PipelineStatusUtil.getPipelineStatus(pipelinetoken)
                }
            }
            val gson = new Gson
            PipelineStatusAPIController.restrictedView(request, data) match {
                case Left(hidden) => hidden
                case Right(shown) => new ResponseEntity[String](gson.toJson(shown), HttpStatus.OK)
            }
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](QueryAPIController.errorBody(e))
        }
    }

    /** Page through a scratch pipeline's result (plans/stories/scratch-result-endpoint-retention.md).
      * The object key is recomputed server-side from the resolved job; no
      * client-supplied key or URI is accepted. Paging never re-runs the source.
      */
    @GetMapping(path = Array("/pipeline/result"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getPipelineResult(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam(required = false) pipelinetoken: String,
        @RequestParam(required = false) publishertoken: String,
        @RequestParam(required = false) offset: String,
        @RequestParam(required = false) limit: String,
        request: jakarta.servlet.http.HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info(
                "API endpoint GET /pipeline/result called with pipelinetoken: " + pipelinetoken + ", publishertoken: " + publishertoken + ", offset: " + offset + ", limit: " + limit
            )
            APIKeyValidator.validate(apiKey)

            val offsetOpt = Option(offset).map(_.trim).filter(_.nonEmpty).map(_.toLong)
            val limitOpt = Option(limit).map(_.trim).filter(_.nonEmpty).map(_.toInt)
            val job = ScratchReader.resolve(pipelinetoken, publishertoken)
            // Restricted users: a job of a pipeline outside the grants reads
            // exactly as an unknown token.
            if (PipelineAPIController.restrictedHidden(request, job.pipeline, "read"))
                throw PipelineStatusAPIController.unknownToken(pipelinetoken, publishertoken)
            val page = ScratchReader.read(ScratchLoader.settingsFromEnvironment(), job, offsetOpt, limitOpt)
            new ResponseEntity[String](new Gson().toJson(page), HttpStatus.OK)
        } catch {
            case e: ScratchResultException =>
                logger.warn("GET /pipeline/result " + e.status + ": " + e.getMessage)
                ResponseEntity.status(e.status).body[String](QueryAPIController.errorBody(e))
            case e: NumberFormatException =>
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String](QueryAPIController.errorBody(new Exception("offset and limit must be integers")))
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](QueryAPIController.errorBody(e))
        }
    }

    @DeleteMapping(path = Array("/pipeline/status"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def clearAllStatus(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        request: jakarta.servlet.http.HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint DELETE /pipeline/status called — clearing all ingestion history")
            APIKeyValidator.validate(apiKey)
            // Clearing wipes every catalog's history: never for a restricted user.
            if (ai.datris.auth.CapabilityCheck.isRestrictedSession(request))
                return PipelineAPIController.capabilityDenied(
                    new ai.datris.model.DatrisException("capability denied: only an unrestricted user can clear all ingestion history")
                )

            val tableName = DatrisEnvironment.current.pipelineStatusTableName
            val detailCount = NoSQLDbUtil.deleteAll(tableName)
            val summaryCount = NoSQLDbUtil.deleteAll(tableName + "-summary")

            logger.info("Cleared " + detailCount + " detail entries and " + summaryCount + " summary entries")

            val gson = new Gson
            val response = new java.util.LinkedHashMap[String, Any]()
            response.put("deleted", detailCount + summaryCount)
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](QueryAPIController.errorBody(e))
        }
    }
}
