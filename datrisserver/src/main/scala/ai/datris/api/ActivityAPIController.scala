package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.{CapabilityCheck, HiddenTapScrub}
import ai.datris.model.DatrisEnvironment
import ai.datris.util.{ActivitySignals, CatalogOps, PipelineConfigIO, TapConfigIO}
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
            val windowMs = Option(window).map(_.trim.toLowerCase).getOrElse("24h") match {
                case "24h" | "" => 24L * 3600000L
                case "7d" => 7L * 86400000L
                case "30d" => 30L * 86400000L
                case other =>
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body[String]("{\"error\":\"window must be 24h, 7d or 30d (got " + other.replace("\"", "") + ")\"}")
            }
            val computed = ActivitySignals.compute(windowMs)
            val visible = if (CapabilityCheck.isRestrictedSession(request)) ActivityAPIController.restrictedView(request, computed) else computed
            new ResponseEntity[String](gson.toJson(visible.toJson), HttpStatus.OK)
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

    /** A restricted user's session sees only the rows of taps and pipelines
      * its grants allow it to read. Pipeline rows are judged on the stored
      * pipeline's catalog (failing pipeline rows carry none); a pipeline no
      * longer stored is dropped. A visible pipeline's related tap name is
      * cleared when that tap is hidden, and a pipeline row's reason has any
      * hidden feeding tap's name replaced. */
    private[api] def restrictedView(request: HttpServletRequest, s: ActivitySignals.Signals): ActivitySignals.Signals = {
        val env = DatrisEnvironment.current
        val pipelineCatalogs: Map[String, String] =
            PipelineConfigIO.readAll(env.pipelineTableName).filter(_ != null).map(p => p.name -> Option(p.catalog).getOrElse("")).toMap
        val taps = TapConfigIO.readAll(env.tapTableName).filter(_ != null)
        val tapCatalogs: Map[String, String] = taps.map(t => t.name -> Option(t.catalog).getOrElse("")).toMap
        def readable(resource: String, catalog: Option[String]): Boolean =
            CapabilityCheck.grants(request, resource, "read", CatalogOps.scopeContext(catalog.orNull, null))
        val keepTap: (String, Option[String]) => Boolean = (_, c) => readable("tap", c)
        val keepPipeline: (String, Option[String]) => Boolean = (n, _) => pipelineCatalogs.get(n).exists(c => readable("pipeline", Some(c)))
        val v = ActivitySignals.visibleTo(s, keepTap, keepPipeline, pipelineCatalogs.get)
        val visiblePipelines = pipelineCatalogs.collect { case (n, c) if readable("pipeline", Some(c)) => n }.toSet
        val tapHidden: String => Boolean = t => !tapCatalogs.get(t).exists(c => readable("tap", Some(c)))
        v.copy(failing = scrubFailing(v.failing, tapHidden, HiddenTapScrub.hiddenFeedingTaps(request, taps, visiblePipelines)))
    }

    /** Failing rows a restricted session sees, with hidden tap names gone:
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
