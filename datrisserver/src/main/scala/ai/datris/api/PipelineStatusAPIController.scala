package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.Gson
import ai.datris.model.{DatrisEnvironment, DatrisException, PipelineJobRollup, PipelineStatus, PipelineStatusResponse, PipelineStatusSummary}
import ai.datris.util.{
    APIKeyValidator,
    NoSQLDbUtil,
    PipelineAliases,
    PipelineConfigIO,
    PipelineStatusUtil,
    ScratchLoader,
    ScratchReader,
    ScratchResultException,
    TapConfigIO
}
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

object PipelineStatusAPIController {

    /** DELETE /pipeline/status wipes every catalog's history, so a limited
      * caller is refused before anything is deleted: a restricted user's
      * session with today's 403 text, a scope-limited API key
      * (plans/stories/scoped-key-read-routes.md) with the install-wide 403.
      * None for everyone else (and with no identity). */
    def clearAllRefusal(request: jakarta.servlet.http.HttpServletRequest): Option[ResponseEntity[String]] =
        if (ai.datris.auth.CapabilityCheck.isRestrictedSession(request))
            Some(
                PipelineAPIController.capabilityDenied(
                    new DatrisException("capability denied: only an unrestricted user can clear all ingestion history")
                )
            )
        else if (ai.datris.auth.CapabilityCheck.isLimited(request))
            Some(PipelineAPIController.installWideRefusal(request, "clearing all ingestion history", "job:kill"))
        else None

    /** The 404 [[ScratchReader.resolve]] answers for a token with no run. */
    def unknownToken(pipelinetoken: String, publishertoken: String): ScratchResultException =
        // Same precedence as ScratchReader.resolve: publishertoken wins.
        if (publishertoken != null) new ScratchResultException(404, "No pipeline run found for publishertoken '" + publishertoken + "'")
        else new ScratchResultException(404, "No pipeline run found for pipelinetoken '" + pipelinetoken + "'")

    /** GET /pipeline/result, limited caller ([[ai.datris.auth.CapabilityCheck.isLimited]]:
      * a restricted user's session, or an API key limited by a `catalog=` /
      * `owner=` scope, plans/stories/scoped-key-read-routes.md): true when the
      * scratch result of a job filed under `pipeline` must read as an unknown
      * token, i.e. the name's records are not shown to the caller under
      * [[PipelineAliases.recordShown]] (its owner missing or outside the
      * grants or key scope, or a reused name with a claimant outside them).
      * Always false for everyone else. */
    def resultHidden(
        request: jakarta.servlet.http.HttpServletRequest,
        pipeline: String,
        configs: Seq[ai.datris.model.PipelineConfig]
    ): Boolean =
        ai.datris.auth.CapabilityCheck.isLimited(request) &&
            !PipelineAliases.recordShown(
                pipeline,
                Option(configs).getOrElse(Nil).filter(_ != null),
                c => PipelineAPIController.mayRead(request, c)
            )

    /** GET /pipeline/result: the job the token reads ([[ScratchReader.pick]]
      * on `jobs`, the token's rollup jobs). For a limited caller
      * ([[ai.datris.auth.CapabilityCheck.isLimited]]) the jobs of pipelines
      * whose records it may not see ([[resultHidden]]) are dropped first, so
      * a token whose jobs are all hidden answers exactly what a token with
      * no run answers, and a publisher token spanning a visible and a hidden
      * scratch job reads the visible one. `configs` is called at most once,
      * and only for a limited caller. Throws [[ScratchResultException]]. */
    def resultJob(
        request: jakarta.servlet.http.HttpServletRequest,
        pipelinetoken: String,
        publishertoken: String,
        jobs: List[PipelineJobRollup],
        configs: () => Seq[ai.datris.model.PipelineConfig]
    ): PipelineJobRollup = {
        val shown =
            if (!ai.datris.auth.CapabilityCheck.isLimited(request) || jobs.isEmpty) jobs
            else {
                lazy val loaded = configs()
                jobs.filterNot(j => j != null && resultHidden(request, j.pipeline, loaded))
            }
        ScratchReader.pick(pipelinetoken, publishertoken, shown)
    }

    /** GET /pipeline/status summary path (no token), as the caller sees
      * it. A caller that is not limited: `Right(readPage(page))`, one store
      * read and nothing else read (what [[restrictedView]] answers for it).
      * A limited caller: the pipeline configs listed once, the record names
      * it may see ([[PipelineAPIController.visiblePipelines]] under
      * [[PipelineAliases.shownRecordNames]], the set [[restrictedView]]
      * filters on), the page read with that set as the store filter
      * (`readVisiblePage(page, names)`, one store read, page N being rows
      * [20(N-1), 20N) of the rows it may see, newest first), then
      * [[restrictedViewShown]] on the same set with `taps()` (listed once). */
    def summaryView(
        request: jakarta.servlet.http.HttpServletRequest,
        page: Int,
        readPage: Int => java.util.List[PipelineStatusSummary],
        readVisiblePage: (Int, Set[String]) => java.util.List[PipelineStatusSummary],
        pipelines: () => Seq[ai.datris.model.PipelineConfig],
        taps: () => Seq[ai.datris.model.TapConfig],
        run: String => Option[ai.datris.model.RunLineage]
    ): Either[ResponseEntity[String], AnyRef] =
        if (!ai.datris.auth.CapabilityCheck.isLimited(request)) Right(readPage(page))
        else {
            val visible = visibleRecordNames(request, pipelines())
            restrictedViewShown(request, readVisiblePage(page, visible), visible, taps(), run)
        }

    /** The record names a limited caller may see among `pipelines`: the
      * pipelines it may read, with the previous names that resolve to them
      * ([[PipelineAliases.shownRecordNames]]). */
    def visibleRecordNames(request: jakarta.servlet.http.HttpServletRequest, pipelines: Seq[ai.datris.model.PipelineConfig]): Set[String] = {
        val configs = Option(pipelines).getOrElse(Nil).filter(_ != null)
        PipelineAliases.shownRecordNames(PipelineAPIController.visiblePipelines(request, configs), configs)
    }

    /** GET /pipeline/status for a limited caller (a restricted user's
      * session, or a catalog- / owner-scoped API key): summary rows and
      * events of pipelines it may not read are dropped, so a token whose
      * runs are all hidden answers exactly what a token with no rows answers
      * (D2 (a)); a token spanning visible and hidden pipelines shows the
      * visible runs, its rollup computed from them; tap names the caller
      * cannot see are scrubbed from what remains (see
      * [[restrictedViewWith]]). Everyone else: `data` unchanged, nothing
      * read. */
    def restrictedView(request: jakarta.servlet.http.HttpServletRequest, data: AnyRef): Either[ResponseEntity[String], AnyRef] = {
        val env = DatrisEnvironment.current
        restrictedViewFrom(
            request,
            data,
            () => PipelineConfigIO.readAll(env.pipelineTableName),
            () => TapConfigIO.readAll(env.tapTableName),
            ai.datris.util.RunLineageIO.read
        )
    }

    /** [[restrictedView]] with the stores given: for a limited caller
      * [[restrictedViewWith]] on `pipelines()` and `taps()` (each read once);
      * everyone else `Right(data)`, no reader called. */
    def restrictedViewFrom(
        request: jakarta.servlet.http.HttpServletRequest,
        data: AnyRef,
        pipelines: () => Seq[ai.datris.model.PipelineConfig],
        taps: () => Seq[ai.datris.model.TapConfig],
        run: String => Option[ai.datris.model.RunLineage]
    ): Either[ResponseEntity[String], AnyRef] =
        if (!ai.datris.auth.CapabilityCheck.isLimited(request)) Right(data)
        else restrictedViewWith(request, data, pipelines(), taps(), run)

    /** [[restrictedView]] on loaded configs. A visible pipeline's events can
      * name a tap the session cannot read: one that feeds it from another
      * catalog, or one deleted or retargeted since (seen only as a
      * `tap-<name>.csv|json` feed file in the payload; a tap no longer stored
      * counts as hidden). Those names are scrubbed ([[scrubFor]]). `run`
      * reads a run-lineage record by run id; it ties scratch rows to their
      * feeding tap and is called only for a row carrying a non-empty tap
      * stamp column that names no tap (at most once per run id). */
    def restrictedViewWith(
        request: jakarta.servlet.http.HttpServletRequest,
        data: AnyRef,
        pipelines: Seq[ai.datris.model.PipelineConfig],
        taps: Seq[ai.datris.model.TapConfig],
        run: String => Option[ai.datris.model.RunLineage] = _ => None
    ): Either[ResponseEntity[String], AnyRef] = {
        if (!ai.datris.auth.CapabilityCheck.isLimited(request)) return Right(data)
        // Run records keep the name they were written under: a visible
        // pipeline's previous names count for it, and a name reused after a
        // rename only when every pipeline it may belong to is visible
        // (PipelineAliases).
        restrictedViewShown(request, data, visibleRecordNames(request, pipelines), taps, run)
    }

    /** [[restrictedViewWith]] on the record names the caller may see
      * (`visible`, [[visibleRecordNames]]), already computed. */
    def restrictedViewShown(
        request: jakarta.servlet.http.HttpServletRequest,
        data: AnyRef,
        visible: Set[String],
        taps: Seq[ai.datris.model.TapConfig],
        run: String => Option[ai.datris.model.RunLineage]
    ): Either[ResponseEntity[String], AnyRef] = {
        import ai.datris.auth.HiddenTapScrub
        if (!ai.datris.auth.CapabilityCheck.isLimited(request)) return Right(data)
        filterFor(visible, data).map { shown =>
            val byName = Option(taps).getOrElse(Nil).filter(t => t != null && t.name != null).map(t => t.name -> t).toMap
            val tapHidden: String => Boolean = n => HiddenTapScrub.tapHidden(request, n, byName.getOrElse(_, null))
            val fromPayload = HiddenTapScrub.feedFileNames(payloadTexts(shown)).filter(tapHidden)
            scrubFor(HiddenTapScrub.hiddenFeedingTaps(request, Option(taps).getOrElse(Nil), visible) ++ fromPayload, shown, tapHidden, run)
        }
    }

    /** Every filename and message text in a /pipeline/status payload. */
    private def payloadTexts(data: AnyRef): Seq[String] = {
        import scala.collection.JavaConverters._
        def event(e: PipelineStatus): Seq[String] =
            if (e == null) Nil else Seq(e.filename, e.description, e.aiSummary, e.aiDiagnosis, e.aiSuggestion, e.detail)
        data match {
            case r: PipelineStatusResponse =>
                val jobs = Option(r.rollup).flatMap(ro => Option(ro.jobs)).map(_.asScala.toSeq).getOrElse(Nil).filter(_ != null)
                jobs.flatMap(j => Seq(j.filename) ++ Option(j.lastError).toSeq.flatMap(l => Seq(l.description, l.aiSummary))) ++
                    Option(r.events).map(_.asScala.toSeq).getOrElse(Nil).flatMap(event)
            case l: java.util.List[_] =>
                l.asScala.toSeq.flatMap {
                    case e: PipelineStatus => event(e)
                    case s: PipelineStatusSummary if s != null => Seq(s.aiSummary)
                    case _ => Nil
                }
            case _ => Nil
        }
    }

    /** `data` (a /pipeline/status payload) with every hidden tap name in
      * filenames and message text replaced where it appears in a tap context
      * ([[ai.datris.auth.HiddenTapScrub.scrubText]]), and scratch result
      * preview rows stamped with a tap `rowTapHidden` holds lose their tap
      * stamp columns ([[ai.datris.auth.HiddenTapScrub.scrubStampedRows]]), as
      * do all preview rows of a job or event whose own filename is the
      * `tap-<name>.csv|json` feed of such a tap. Preview rows of a
      * `provenance.fields` subset without `_datris_tap_run` are tied to their
      * tap by the run record `run` returns for their `_datris_run_id`, else
      * by the feed file the job's StreamNotifier begin event names; a row of
      * a stream run (`stream: <pipeline>`) whose tap neither settles loses
      * any non-empty tap stamp column (fail closed;
      * [[ai.datris.auth.HiddenTapScrub.ScratchFeed]]). `run` is memoised for
      * the call. `data` itself when nothing applies. */
    def scrubFor(
        hidden: Set[String],
        data: AnyRef,
        rowTapHidden: String => Boolean = _ => false,
        run: String => Option[ai.datris.model.RunLineage] = _ => None
    ): AnyRef = {
        import ai.datris.auth.HiddenTapScrub.{feedTap, memoRuns, scrubFilename, scrubStampedRows, scrubText, stampsHiddenTap, ScratchFeed}
        import scala.collection.JavaConverters._
        val runOf = memoRuns(run)
        val allEvents: Seq[PipelineStatus] = (data match {
            case r: PipelineStatusResponse => Option(r.events).map(_.asScala.toSeq).getOrElse(Nil)
            case l: java.util.List[_] => l.asScala.toSeq.collect { case e: PipelineStatus => e }
            case _ => Nil
        }).filter(_ != null)
        lazy val descriptionsByToken: Map[String, Seq[String]] =
            allEvents.filter(_.pipelineToken != null).groupBy(_.pipelineToken).map { case (t, es) => t -> es.map(_.description) }
        // Each preview with its job's own filename (read before scrubFilename
        // rewrites it) and the descriptions of the job's events.
        def feedOf(pipeline: String, token: String, filename: String): ScratchFeed =
            new ScratchFeed(pipeline, token, filename, () => if (token == null) Nil else descriptionsByToken.getOrElse(token, Nil))
        def eventRows(e: PipelineStatus): (com.google.gson.JsonArray, ScratchFeed) =
            if (e == null || e.scratchResult == null) (null, null)
            else (e.scratchResult.resultPreview, feedOf(e.pipeline, e.pipelineToken, e.filename))
        val previews: Seq[(com.google.gson.JsonArray, ScratchFeed)] = data match {
            case r: PipelineStatusResponse =>
                Option(r.rollup).flatMap(ro => Option(ro.jobs)).map(_.asScala.toSeq).getOrElse(Nil).filter(_ != null).map(j =>
                    (j.resultPreview, feedOf(j.pipeline, j.pipelineToken, j.filename))
                ) ++
                    Option(r.events).map(_.asScala.toSeq).getOrElse(Nil).map(eventRows)
            case l: java.util.List[_] => l.asScala.toSeq.collect { case e: PipelineStatus => eventRows(e) }
            case _ => Nil
        }
        def rowsFor(rows: com.google.gson.JsonArray, feed: ScratchFeed): com.google.gson.JsonArray =
            scrubStampedRows(rows, rowTapHidden, feedTap(feed.filename), feed, runOf)
        if (
            hidden.isEmpty && !previews.exists { case (rows, feed) =>
                rows != null && stampsHiddenTap(rows, rowTapHidden, feedTap(feed.filename), feed, runOf)
            }
        ) return data
        def text(t: String): String = scrubText(t, hidden)
        def event(e: PipelineStatus): PipelineStatus =
            if (e == null) e
            else
                e.copy(
                    filename = scrubFilename(e.filename, hidden),
                    description = text(e.description),
                    aiSummary = text(e.aiSummary),
                    aiDiagnosis = text(e.aiDiagnosis),
                    aiSuggestion = text(e.aiSuggestion),
                    detail = text(e.detail),
                    scratchResult = Option(e.scratchResult)
                        .map(sr => sr.copy(resultPreview = rowsFor(sr.resultPreview, feedOf(e.pipeline, e.pipelineToken, e.filename))))
                        .orNull
                )
        def job(j: PipelineJobRollup): PipelineJobRollup =
            if (j == null) j
            else
                j.copy(
                    filename = scrubFilename(j.filename, hidden),
                    resultPreview = rowsFor(j.resultPreview, feedOf(j.pipeline, j.pipelineToken, j.filename)),
                    lastError = Option(j.lastError).map(l => l.copy(description = text(l.description), aiSummary = text(l.aiSummary))).orNull
                )
        data match {
            case r: PipelineStatusResponse =>
                val rollup = Option(r.rollup).map(ro => ro.copy(jobs = Option(ro.jobs).map(_.asScala.map(job).asJava).orNull)).orNull
                r.copy(rollup = rollup, events = Option(r.events).map(_.asScala.map(event).asJava).orNull)
            case l: java.util.List[_] =>
                l.asScala.map {
                    case e: PipelineStatus => event(e)
                    case s: PipelineStatusSummary => s.copy(aiSummary = text(s.aiSummary))
                    case other => other
                }.asJava
            case other => other
        }
    }

    /** GET /pipeline/result for a limited caller (restricted session or
      * scoped key): rows stamped with a tap the caller cannot read (or one no longer stored) lose
      * their tap stamp columns, as do all rows when `jobFilename` (the job's
      * own filename) is the `tap-<name>.csv|json` feed of such a tap.
      * Rows of a `provenance.fields` subset are tied to their tap as in
      * [[scrubFor]]: the run record `run` returns for their `_datris_run_id`
      * (trusted when filed under `jobPipeline`), else the feed file named in
      * the job's event descriptions (`jobDescriptions`, read at most once,
      * only when needed); a row of a stream run whose tap neither settles
      * loses any non-empty tap stamp column. Everyone else: `page`
      * unchanged, and neither `readTap`, `run` nor `jobDescriptions` is
      * called. */
    def scrubResultPage(
        request: jakarta.servlet.http.HttpServletRequest,
        page: ScratchReader.ScratchResultPage,
        readTap: String => ai.datris.model.TapConfig,
        jobFilename: String = null,
        jobPipeline: String = null,
        jobToken: String = null,
        run: String => Option[ai.datris.model.RunLineage] = _ => None,
        jobDescriptions: () => Seq[String] = () => Nil
    ): ScratchReader.ScratchResultPage =
        if (page == null || !ai.datris.auth.CapabilityCheck.isLimited(request)) page
        else {
            import ai.datris.auth.HiddenTapScrub
            val memo = scala.collection.mutable.Map[String, Boolean]()
            val hidden: String => Boolean = n => memo.getOrElseUpdate(n, HiddenTapScrub.tapHidden(request, n, readTap))
            val feed = new HiddenTapScrub.ScratchFeed(jobPipeline, jobToken, jobFilename, jobDescriptions)
            val rows = HiddenTapScrub.scrubStampedRows(page.records, hidden, HiddenTapScrub.feedTap(jobFilename), feed, HiddenTapScrub.memoRuns(run))
            if (rows eq page.records) page else page.copy(records = rows)
        }

    /** Pure core of [[restrictedView]]: summary rows, events and rollup
      * jobs of a pipeline outside `visible` are dropped, so they read as
      * missing (a token whose rows are all hidden reads as a token with no
      * rows: an empty list, or the empty rollup). A rollup that loses a job
      * is recomputed from the jobs kept ([[PipelineStatusUtil.rollupOf]]).
      * `data` itself when nothing is dropped. Always Right. */
    def filterFor(visible: Set[String], data: AnyRef): Either[ResponseEntity[String], AnyRef] = {
        import scala.collection.JavaConverters._
        def hidden(name: String): Boolean = name != null && !visible.contains(name)
        data match {
            case r: PipelineStatusResponse =>
                val events = Option(r.events).map(_.asScala.toList).getOrElse(Nil)
                val jobs = Option(r.rollup).flatMap(ro => Option(ro.jobs)).map(_.asScala.toList).getOrElse(Nil)
                val eventHidden = events.exists(e => e != null && hidden(e.pipeline))
                val jobHidden = jobs.exists(j => j != null && hidden(j.pipeline))
                if (!eventHidden && !jobHidden) Right(r)
                else {
                    val keptEvents = events.filterNot(e => e != null && hidden(e.pipeline))
                    val keptJobs = jobs.filterNot(j => j != null && hidden(j.pipeline))
                    val rollup = if (r.rollup == null) null else if (jobHidden) PipelineStatusUtil.rollupOf(keptJobs) else r.rollup
                    Right(
                        r.copy(
                            rollup = rollup,
                            events = if (r.events == null) null else new java.util.ArrayList[PipelineStatus](keptEvents.asJava)
                        )
                    )
                }
            case l: java.util.List[_] =>
                val items = l.asScala.toSeq
                if (items.forall(_.isInstanceOf[PipelineStatusSummary]))
                    Right(items.collect { case s: PipelineStatusSummary if s.pipeline != null && visible.contains(s.pipeline) => s }.asJava)
                else if (items.exists { case e: PipelineStatus => hidden(e.pipeline); case _ => false })
                    Right(new java.util.ArrayList[Any](items.filterNot { case e: PipelineStatus => hidden(e.pipeline); case _ => false }.asJava))
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
            val view: Either[ResponseEntity[String], AnyRef] =
                if (publishertoken == null && pipelinetoken == null) {
                    val pageNbr = {
                        if (page == null)
                            1
                        else
                            page.toInt
                    }
                    // A limited caller's page is read with the store filtered
                    // to the pipelines it may see; everyone else reads the page.
                    PipelineStatusAPIController.summaryView(
                        request,
                        pageNbr,
                        n => PipelineStatusUtil.getPipelineStatusSummary(pipelinename, n),
                        (n, names) => PipelineStatusUtil.getVisiblePipelineStatusSummary(names, n),
                        () => PipelineConfigIO.readAll(DatrisEnvironment.current.pipelineTableName),
                        () => TapConfigIO.readAll(DatrisEnvironment.current.tapTableName),
                        ai.datris.util.RunLineageIO.read
                    )
                } else {
                    val data: AnyRef = {
                        if (publishertoken != null) {
                            if (rollup) PipelineStatusUtil.getPipelineStatusByPublisherWithRollup(publishertoken)
                            else PipelineStatusUtil.getPipelineStatusByPublisher(publishertoken)
                        } else {
                            if (rollup) PipelineStatusUtil.getPipelineStatusWithRollup(pipelinetoken)
                            else PipelineStatusUtil.getPipelineStatus(pipelinetoken)
                        }
                    }
                    PipelineStatusAPIController.restrictedView(request, data)
                }
            val gson = new Gson
            view match {
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
            // Limited callers (restricted users, catalog- or owner-scoped
            // keys): jobs of pipelines they may not read are dropped before
            // the job is picked, so a hidden run reads exactly as an unknown
            // token. The job's name is judged by the previous-name rule
            // (PipelineAliases.recordShown). The configs are read only for them.
            val job = PipelineStatusAPIController.resultJob(
                request,
                pipelinetoken,
                publishertoken,
                ScratchReader.jobsOf(pipelinetoken, publishertoken),
                () => PipelineConfigIO.readAll(DatrisEnvironment.current.pipelineTableName)
            )
            val read = ScratchReader.read(ScratchLoader.settingsFromEnvironment(), job, offsetOpt, limitOpt)
            val page = PipelineStatusAPIController.scrubResultPage(
                request,
                read,
                n => TapConfigIO.read(DatrisEnvironment.current.tapTableName, n),
                job.filename,
                job.pipeline,
                job.pipelineToken,
                ai.datris.util.RunLineageIO.read,
                () => {
                    import scala.collection.JavaConverters._
                    if (job.pipelineToken == null) Nil
                    else PipelineStatusUtil.getPipelineStatus(job.pipelineToken).asScala.toSeq.filter(_ != null).map(_.description)
                }
            )
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
            // Clearing wipes every catalog's history: never for a restricted
            // user or a scope-limited key. Before anything is deleted.
            PipelineStatusAPIController.clearAllRefusal(request).foreach(return _)

            val tableName = DatrisEnvironment.current.pipelineStatusTableName
            val detailCount = NoSQLDbUtil.deleteAll(tableName)
            val summaryCount = NoSQLDbUtil.deleteAll(DatrisEnvironment.summaryTableName(tableName))

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
