package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.ActivitySignals
import com.google.common.base.Throwables
import com.google.gson.Gson
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
    def signals(@RequestParam(name = "window", required = false) window: String): ResponseEntity[String] = {
        try {
            val windowMs = Option(window).map(_.trim.toLowerCase).getOrElse("24h") match {
                case "24h" | "" => 24L * 3600000L
                case "7d" => 7L * 86400000L
                case "30d" => 30L * 86400000L
                case other =>
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body[String]("{\"error\":\"window must be 24h, 7d or 30d (got " + other.replace("\"", "") + ")\"}")
            }
            new ResponseEntity[String](gson.toJson(ActivitySignals.compute(windowMs).toJson), HttpStatus.OK)
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
