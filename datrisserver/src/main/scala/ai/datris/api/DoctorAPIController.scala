package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.{CallerVisibility, CapabilityCheck}
import ai.datris.model.ResolvedKey
import ai.datris.util.{APIKeyValidator, DoctorService}
import com.google.common.base.Throwables
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

/** `GET /api/v1/doctor` — the operational self-check report.
  *
  * Query parameters:
  *   - `mode=quick|full` (default full): quick runs only the cheap
  *     startup-safe subset.
  *   - `probes=ai` opts into the AI reachability probe (spends a few tokens).
  *   - `cli=`, `mcp=`, `ui=`: versions the calling clients report, for the
  *     version-skew check.
  *
  * Not public — the report names env keys, Vault paths and model ids — so it
  * is capability-mapped to `config:read` (CapabilityRoutes) on top of the
  * usual API-key validation. Host-side checks (Docker volumes, container
  * env drift) live in `datris doctor` on the machine running Docker.
  *
  * A row that names taps, pipelines, users or keys (`tap.owner_scope`) shows
  * a caller that does not see the whole installation counts only
  * ([[DoctorAPIController.forCaller]]).
  */
@RestController
@RequestMapping(Array("/api/v1"))
class DoctorAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[DoctorAPIController])

    @GetMapping(path = Array("/doctor"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def doctor(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam(name = "mode", required = false, defaultValue = "full") mode: String,
        @RequestParam(name = "probes", required = false) probes: String,
        @RequestParam(name = "cli", required = false) cli: String,
        @RequestParam(name = "mcp", required = false) mcp: String,
        @RequestParam(name = "ui", required = false) ui: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /api/v1/doctor called, mode=" + mode + ", probes=" + probes)
            APIKeyValidator.validate(apiKey)
            val groups = Option(probes).map(_.split(",").map(_.trim.toLowerCase).filter(_.nonEmpty).toSet).getOrElse(Set.empty[String])
            val clients = Seq("cli" -> cli, "mcp" -> mcp, "ui" -> ui).collect { case (k, v) if v != null && v.trim.nonEmpty => k -> v.trim }.toMap
            val report = DoctorAPIController.forCaller(DoctorService.runLive(mode, groups, clients), CapabilityCheck.resolvedKey(request))
            new ResponseEntity[String](report.toJson, HttpStatus.OK)
        } catch {
            case e: ai.datris.model.DatrisException =>
                logger.warn("Doctor request rejected: " + e.getMessage)
                ResponseEntity.status(HttpStatus.UNAUTHORIZED).body[String]("{\"error\": \"" + e.getMessage.replace("\"", "'") + "\"}")
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }
}

object DoctorAPIController {

    /** True when `caller` sees the whole installation, so doctor rows may name
      * any tap, pipeline, user or key: no identity on the request (API keys
      * off), full access, or an identity that is not limited
      * ([[CallerVisibility.limited]]) and holds unscoped pipeline and tap
      * read (an administrator, an unrestricted editor or viewer, a full or
      * unscoped key). False for a restricted session, a key limited to a
      * catalog or to what it created, and a key that cannot read pipelines
      * or taps. */
    def seesWholeInstall(caller: Option[ResolvedKey]): Boolean =
        caller.forall { rk =>
            rk == null || rk.isFullAccess ||
            (!CallerVisibility.limited(rk) && CallerVisibility.holdsUnscoped(rk, "pipeline", "read") && CallerVisibility.holdsUnscoped(rk, "tap", "read"))
        }

    /** The report as `caller` may see it: unchanged when it sees the whole
      * installation, else every row's nameless detail
      * ([[DoctorService.Report.withoutNames]]). */
    def forCaller(report: DoctorService.Report, caller: Option[ResolvedKey]): DoctorService.Report =
        if (seesWholeInstall(caller)) report else report.withoutNames
}
