package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.{CapabilityCheck, HiddenTapScrub}
import ai.datris.model.DatrisEnvironment
import ai.datris.util.{ActivitySignals, PipelineAliases, PipelineConfigIO, TapConfigIO}
import com.google.common.base.Throwables
import com.google.gson.Gson
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

/** Server-side Activity signals — one definition of failures, stale taps
  * and volume anomalies shared by the dashboard, the Ops chat context and
  * the recovery agent (see [[ActivitySignals]]). */
@RestController
@RequestMapping(Array("/api/v1/activity"))
class ActivityAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[ActivityAPIController])
    private val gson = new Gson()

    @GetMapping(path = Array("/signals"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def signals(@RequestParam(name = "window", required = false) window: String, request: HttpServletRequest): ResponseEntity[String] = {
        try {
            ActivityAPIController.signalsResponse(
                request,
                window,
                ms => ActivitySignals.compute(ms),
                () => PipelineConfigIO.readAll(DatrisEnvironment.current.pipelineTableName),
                () => TapConfigIO.readAll(DatrisEnvironment.current.tapTableName)
            )
        } catch {
            case e: Exception =>
                logger.error("Error computing activity signals: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String]("{\"error\":\"" + Option(e.getMessage).getOrElse("").replace(
                    "\"",
                    "'"
                ) + "\"}")
        }
    }
}

object ActivityAPIController {

    /** GET /activity/signals: 400 for a window other than 24h, 7d or 30d;
      * otherwise `compute(windowMs)` as the caller sees it ([[callerView]]:
      * `pipelines` and `taps` read only for a limited caller). */
    def signalsResponse(
        request: HttpServletRequest,
        window: String,
        compute: Long => ActivitySignals.Signals,
        pipelines: () => Seq[ai.datris.model.PipelineConfig],
        taps: () => Seq[ai.datris.model.TapConfig]
    ): ResponseEntity[String] = {
        val windowMs = Option(window).map(_.trim.toLowerCase).getOrElse("24h") match {
            case "24h" | "" => 24L * 3600000L
            case "7d" => 7L * 86400000L
            case "30d" => 30L * 86400000L
            case other =>
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body[String]("{\"error\":\"window must be 24h, 7d or 30d (got " + other.replace("\"", "") + ")\"}")
        }
        new ResponseEntity[String](new Gson().toJson(callerView(request, compute(windowMs), pipelines, taps).toJson), HttpStatus.OK)
    }

    /** GET /activity/signals: what the caller sees. A limited caller
      * ([[CapabilityCheck.isLimited]]: a restricted user's session, or an API
      * key limited by a `catalog=` / `owner=` scope;
      * plans/stories/scoped-key-read-routes.md, Part B) gets
      * [[restrictedViewWith]] on the stores `pipelines` and `taps` read (each
      * once). Everyone else: `s` itself, neither reader called. */
    def callerView(
        request: HttpServletRequest,
        s: ActivitySignals.Signals,
        pipelines: () => Seq[ai.datris.model.PipelineConfig],
        taps: () => Seq[ai.datris.model.TapConfig]
    ): ActivitySignals.Signals =
        if (!CapabilityCheck.isLimited(request)) s
        else restrictedViewWith(request, s, Option(pipelines()).getOrElse(Nil), Option(taps()).getOrElse(Nil))

    /** [[callerView]] on the live stores. */
    private[api] def restrictedView(request: HttpServletRequest, s: ActivitySignals.Signals): ActivitySignals.Signals = {
        val env = DatrisEnvironment.current
        callerView(request, s, () => PipelineConfigIO.readAll(env.pipelineTableName), () => TapConfigIO.readAll(env.tapTableName))
    }

    /** A limited caller sees only the rows of taps and pipelines it may read,
      * judged on the STORED item's catalog and owner (`createdByKeyLabel`),
      * so an `owner=self` key sees its own: a tap row by the stored tap of
      * that name ([[TapAPIController.mayRead]]; a tap no longer stored is
      * dropped), a pipeline row by [[recordReadable]] with
      * [[PipelineAPIController.mayRead]] (a pipeline no longer stored is
      * dropped). A visible pipeline's related tap name is cleared when that
      * tap is hidden, and a pipeline row's reason has any hidden feeding
      * tap's name replaced. Anomalies are derived from the kept volumes. */
    def restrictedViewWith(
        request: HttpServletRequest,
        s: ActivitySignals.Signals,
        pipelines: Seq[ai.datris.model.PipelineConfig],
        taps: Seq[ai.datris.model.TapConfig]
    ): ActivitySignals.Signals = {
        val configs = pipelines.filter(_ != null)
        val pipelineCatalogs: Map[String, String] = ActivityAPIController.recordCatalogs(configs)
        val stored = taps.filter(t => t != null && t.name != null)
        val tapByName = stored.map(t => t.name -> t).toMap
        val tapReadable: String => Boolean = n => tapByName.get(n).exists(t => TapAPIController.mayRead(request, t))
        val keepTap: (String, Option[String]) => Boolean = (n, _) => tapReadable(n)
        val keepPipeline: (String, Option[String]) => Boolean =
            (n, _) => recordReadable(n, configs, c => PipelineAPIController.mayRead(request, c))
        val v = ActivitySignals.visibleTo(s, keepTap, keepPipeline, pipelineCatalogs.get)
        val visiblePipelines = pipelineCatalogs.keys.filter(n => keepPipeline(n, None)).toSet
        val tapHidden: String => Boolean = t => !tapReadable(t)
        v.copy(failing = scrubFailing(v.failing, tapHidden, HiddenTapScrub.hiddenFeedingTaps(request, stored, visiblePipelines)))
    }

    /** Record name -> catalog for activity rows: every pipeline's name, plus
      * each previous name that resolves to it ([[PipelineAliases]]), so a
      * renamed pipeline's earlier rows are judged on its catalog. */
    def recordCatalogs(configs: Seq[ai.datris.model.PipelineConfig]): Map[String, String] = {
        val live = configs.filter(_ != null)
        live.map(p => p.name -> Option(p.catalog).getOrElse("")).toMap ++
            PipelineAliases.aliasOwners(live).map { case (n, c) => n -> Option(c.catalog).getOrElse("") }
    }

    /** Whether a limited caller sees activity rows filed under `name`:
      * [[PipelineAliases.recordShown]] with `catalogReadable` deciding which
      * pipelines it can see, so a name reused after a rename needs every
      * pipeline it may belong to readable. A name no pipeline carries or
      * lists is hidden. */
    def recordReadable(name: String, configs: Seq[ai.datris.model.PipelineConfig], catalogReadable: ai.datris.model.PipelineConfig => Boolean): Boolean =
        PipelineAliases.recordShown(name, configs.filter(_ != null), catalogReadable)

    /** Failing rows a limited caller sees, with hidden tap names gone:
      * `relatedTapName` is cleared when `tapHidden` (a tap no longer stored
      * counts as hidden), and a pipeline row's `reason` (the run's message)
      * has `hiddenFeeding` and any hidden tap named by a `tap-<name>.csv|json`
      * token replaced in tap contexts. Tap rows are visible taps (already
      * filtered): their reason is untouched. */
    def scrubFailing(
        failing: List[ActivitySignals.FailingItem],
        tapHidden: String => Boolean,
        hiddenFeeding: Set[String]
    ): List[ActivitySignals.FailingItem] =
        failing.map { f =>
            val related = if (f.relatedTapName.exists(tapHidden)) f.copy(relatedTapName = None) else f
            if (f.kind != "pipeline") related
            else {
                val hidden = hiddenFeeding ++ HiddenTapScrub.feedFileNames(Option(f.reason).toSeq).filter(tapHidden)
                related.copy(reason = HiddenTapScrub.scrubText(related.reason, hidden))
            }
        }
}
