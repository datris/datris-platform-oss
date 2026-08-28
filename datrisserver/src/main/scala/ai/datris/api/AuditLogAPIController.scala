package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditLog, AuditLogIO}
import ai.datris.config.RequiresRole
import ai.datris.model.DatrisEnvironment
import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonArray, JsonObject}
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.bson.Document
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import java.time.Instant

/** Admin-only read access to the audit log. No mutation endpoints — entries
  * are immutable from the API; only the retention TTL removes them.
  *
  * Gating: `@RequiresRole(admin)` covers user sessions. For programmatic
  * callers the route is capability-mapped to `audit:read`, which full-access
  * keys hold implicitly and scoped keys must be issued with explicitly.
  * Reading the log is not itself audited (it would double every page view);
  * exporting it is. */
@RestController
@RequestMapping(Array("/api/v1/audit-log"))
@RequiresRole(Array("admin"))
class AuditLogAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[AuditLogAPIController])
    private val gson = new Gson()

    @GetMapping(produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def list(
        @RequestParam(name = "since", required = false) since: String,
        @RequestParam(name = "until", required = false) until: String,
        @RequestParam(name = "category", required = false) category: String,
        @RequestParam(name = "action", required = false) action: String,
        @RequestParam(name = "actor", required = false) actor: String,
        @RequestParam(name = "actorType", required = false) actorType: String,
        @RequestParam(name = "outcome", required = false) outcome: String,
        @RequestParam(name = "resource", required = false) resource: String,
        @RequestParam(name = "limit", required = false) limit: Integer,
        @RequestParam(name = "cursor", required = false) cursor: String
    ): ResponseEntity[String] = {
        try {
            if (!AuditLog.enabled) return disabled()
            val q = buildQuery(since, until, category, action, actor, actorType, outcome, resource, limit, cursor)
            val page = AuditLogIO.query(q)
            val entries = new JsonArray()
            page.entries.foreach(d => entries.add(AuditLogIO.toPublicJson(d)))
            val out = new JsonObject()
            out.add("entries", entries)
            page.nextCursor.foreach(out.addProperty("nextCursor", _))
            out.addProperty("enabled", true)
            new ResponseEntity[String](gson.toJson(out), HttpStatus.OK)
        } catch {
            case e: IllegalArgumentException =>
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String](errorJson(e.getMessage))
            case e: Exception =>
                logger.error("Error listing audit log: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](errorJson(e.getMessage))
        }
    }

    /** Distinct categories / actions / actors seen in the last 30 days, for
      * the UI's filter dropdowns. Cached 60s server-side. */
    @GetMapping(path = Array("/facets"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def facets(): ResponseEntity[String] = {
        try {
            if (!AuditLog.enabled) return disabled()
            new ResponseEntity[String](gson.toJson(AuditLogIO.facets()), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error reading audit facets: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](errorJson(e.getMessage))
        }
    }

    /** Operational status for the UI banner — whether auditing is on and how
      * many entries have been dropped under backpressure since startup. */
    @GetMapping(path = Array("/status"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def status(): ResponseEntity[String] = {
        val out = new JsonObject()
        out.addProperty("enabled", AuditLog.enabled)
        val env = DatrisEnvironment.values
        if (env != null) {
            out.addProperty("retentionDays", env.auditLogRetentionDays)
            out.addProperty("logReads", env.auditLogLogReads)
            out.addProperty("emitLogLine", env.auditLogEmitLogLine)
        }
        out.addProperty("dropped", AuditLog.droppedCount)
        out.addProperty("queueDepth", AuditLog.queueDepth)
        new ResponseEntity[String](gson.toJson(out), HttpStatus.OK)
    }

    /** CSV of the current filter, newest first, capped at 50k rows. The export
      * itself is recorded as an audit event. */
    @GetMapping(path = Array("/export"), produces = Array("text/csv"))
    def export(
        @RequestParam(name = "since", required = false) since: String,
        @RequestParam(name = "until", required = false) until: String,
        @RequestParam(name = "category", required = false) category: String,
        @RequestParam(name = "action", required = false) action: String,
        @RequestParam(name = "actor", required = false) actor: String,
        @RequestParam(name = "actorType", required = false) actorType: String,
        @RequestParam(name = "outcome", required = false) outcome: String,
        @RequestParam(name = "resource", required = false) resource: String,
        request: HttpServletRequest,
        response: HttpServletResponse
    ): Unit = {
        if (!AuditLog.enabled) {
            response.setStatus(HttpStatus.NOT_FOUND.value())
            response.setContentType("application/json")
            response.getWriter.write(disabledBody)
            return
        }
        val q = buildQuery(since, until, category, action, actor, actorType, outcome, resource, null, null)
        response.setStatus(HttpStatus.OK.value())
        response.setContentType("text/csv; charset=utf-8")
        response.setHeader("Content-Disposition", "attachment; filename=\"datris-audit-log.csv\"")
        val w = response.getWriter
        w.write("ts,actorType,actor,keyLabel,keyId,username,category,action,resourceType,resource,outcome,httpStatus,durationMs,ip,method,path,errorMessage\n")
        val n = AuditLogIO.foreachMatching(q, AuditLogIO.ExportMaxRows) { d =>
            w.write(csvRow(d))
            w.write("\n")
        }
        w.flush()

        val md = new JsonObject()
        md.addProperty("rows", n)
        Seq("since" -> since, "until" -> until, "category" -> category, "action" -> action, "actor" -> actor,
            "actorType" -> actorType, "outcome" -> outcome, "resource" -> resource)
            .foreach { case (k, v) => if (v != null && v.nonEmpty) md.addProperty(k, v) }
        AuditLog.record(request, "audit", "export", "audit-log", DatrisEnvironment.current.auditLogTableName, metadata = md)
    }

    // ------------------------------------------------------------------

    private def buildQuery(
        since: String, until: String, category: String, action: String, actor: String,
        actorType: String, outcome: String, resource: String, limit: Integer, cursor: String
    ): AuditLogIO.Query = {
        AuditLogIO.Query(
            since = parseInstant("since", since),
            until = parseInstant("until", until),
            category = opt(category),
            action = opt(action),
            actor = opt(actor),
            actorType = opt(actorType),
            outcome = opt(outcome),
            resource = opt(resource),
            limit = if (limit == null) AuditLogIO.DefaultLimit else math.max(1, math.min(limit.intValue(), AuditLogIO.MaxLimit)),
            cursor = opt(cursor)
        )
    }

    private def opt(s: String): Option[String] = Option(s).map(_.trim).filter(_.nonEmpty)

    private def parseInstant(name: String, s: String): Option[Instant] =
        opt(s).map { v =>
            try Instant.parse(v)
            catch {
                case _: Exception =>
                    // Accept epoch millis too.
                    try Instant.ofEpochMilli(v.toLong)
                    catch { case _: Exception => throw new IllegalArgumentException(name + " must be an ISO-8601 instant or epoch millis") }
            }
        }

    private def csvRow(d: Document): String = {
        val actor = Option(d.get("actor", classOf[Document])).getOrElse(new Document())
        val res = Option(d.get("resource", classOf[Document])).getOrElse(new Document())
        val req = Option(d.get("request", classOf[Document])).getOrElse(new Document())
        def s(doc: Document, k: String): String = Option(doc.get(k)).map(_.toString).getOrElse("")
        Seq(
            s(d, "ts"), s(actor, "type"), s(actor, "label"), s(actor, "keyLabel"), s(actor, "keyId"), s(actor, "username"),
            s(d, "category"), s(d, "action"), s(res, "type"), s(res, "name"), s(d, "outcome"), s(d, "httpStatus"),
            s(d, "durationMs"), s(req, "ip"), s(req, "method"), s(req, "path"), s(d, "errorMessage")
        ).map(csvEscape).mkString(",")
    }

    private def csvEscape(v: String): String = {
        // Neutralize spreadsheet formula injection as well as the usual quoting.
        val safe = if (v.nonEmpty && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) "'" + v else v
        if (safe.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r')) "\"" + safe.replace("\"", "\"\"") + "\""
        else safe
    }

    private val disabledBody: String =
        "{\"enabled\":false,\"error\":\"Audit log is not enabled. Set USE_AUDIT_LOG=true and recreate the datris container.\"}"

    private def disabled(): ResponseEntity[String] =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body[String](disabledBody)

    private def errorJson(msg: String): String =
        "{\"error\":\"" + (if (msg == null) "" else msg.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"}"
}
