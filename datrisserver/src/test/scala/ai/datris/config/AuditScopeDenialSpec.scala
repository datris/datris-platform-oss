package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.ScopeDenialFixtures
import ai.datris.model.DatrisEnvironment
import com.google.gson.JsonObject
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.mockito.Mockito.{mock, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._
import scala.collection.mutable

/** Story: scoped API keys, a refusal no longer names the hidden resource's
  * catalog or owner (plans/stories/scoped-key-refusal-hides-scope.md).
  *
  * The full refusal text (with the target's catalog and owner) leaves the 403
  * body and goes to the audit log. `CapabilityCheck.assertScope` notes each
  * denial on the request (`AuditLog.noteScopeDenial`) and
  * `AuditInterceptor.afterCompletion` records it: as `errorMessage` and
  * `metadata.scopeDenied` on an audited route, and as one `security/denied`
  * row on a read route the audit log otherwise skips. Driven the way Spring
  * calls the interceptor, with a request backed by an attribute map and the
  * AuditLog test sink.
  *
  * Pinned seam (does not exist at 162fca76; the implementation adds it):
  * {{{
  * // ai.datris.audit.AuditLog
  * val ScopeDenialsAttr: String
  * def noteScopeDenial(request: HttpServletRequest, required: String, detail: String): Unit
  *   // appends (required, detail) to a list on the request, first 20 kept; null request = no-op
  * // ai.datris.config.AuditInterceptor.afterCompletion — via the pure companion
  * // AuditInterceptor.scopeDenialPlan(status, classified, denials)
  * }}}
  * Routes used: DELETE /api/v1/pipeline (audited write), GET /api/v1/pipeline
  * (pipeline read, not audited with AUDIT_LOG_READS off), POST
  * /api/v1/catalog/{c}/restore (audited, answers 200/207 after filtering). */
class AuditScopeDenialSpec extends AnyFunSuite with BeforeAndAfterEach {

    private val Detail =
        "capability denied: key 'ci_scoped_w' does not hold capability 'pipeline:read' for the targeted resource (scope: catalog=CiHR, owner=vscode-claude)"
    private val Detail2 =
        "capability denied: key 'ci_scoped_w' does not hold capability 'pipeline:read' for the targeted resource (scope: catalog=CiOps, owner=other)"

    private val scoped = ScopeDenialFixtures.key("ci_scoped_w", "pipeline:read:catalog=CiSales", "pipeline:create:catalog=CiSales", "tap:read:owner=self")

    private var saved: DatrisEnvironment = _
    private val captured = mutable.ArrayBuffer[AuditEntry]()
    private val interceptor = new AuditInterceptor

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        captured.clear()
        AuditLog.sinkOverride = (e: AuditEntry) => captured.synchronized { captured += e; () }
        ScopeDenialFixtures.install(ScopeDenialFixtures.auditEnv(logReads = false))
    }

    override def afterEach(): Unit = {
        AuditLog.sinkOverride = null
        ScopeDenialFixtures.install(saved)
    }

    private def request(method: String, uri: String): (HttpServletRequest, mutable.Map[String, AnyRef]) =
        ScopeDenialFixtures.request(scoped, method, uri)

    private def complete(r: HttpServletRequest, status: Int): Seq[AuditEntry] = {
        val resp = mock(classOf[HttpServletResponse])
        when(resp.getStatus).thenReturn(status)
        interceptor.afterCompletion(r, resp, new Object, null)
        captured.synchronized(captured.toList)
    }

    private def scopeDenied(e: AuditEntry): Option[Seq[String]] =
        e.metadata.filter(_.has("scopeDenied")).map(_.getAsJsonArray("scopeDenied").asScala.map(_.getAsString).toList)

    test("noteScopeDenial appends and caps at 20; a null request is a no-op") {
        AuditLog.noteScopeDenial(null, "pipeline:read", Detail)
        val (r, attrs) = request("POST", "/api/v1/catalog/CiSales/restore")
        assert(!attrs.contains(AuditLog.ScopeDenialsAttr))
        val details = (0 until 25).map(i => Detail + " #" + i)
        details.foreach(d => AuditLog.noteScopeDenial(r, "pipeline:create", d))
        assert(attrs.contains(AuditLog.ScopeDenialsAttr), attrs.keySet)
        val entries = complete(r, 207)
        assert(entries.size == 1, entries)
        assert(scopeDenied(entries.head).contains(details.take(20)), entries.head.metadata)
    }

    test("a 403 on an audited route records the detail as errorMessage and metadata.scopeDenied") {
        val (r, _) = request("DELETE", "/api/v1/pipeline")
        AuditLog.noteScopeDenial(r, "pipeline:delete", Detail)
        AuditLog.noteScopeDenial(r, "pipeline:delete", Detail2)
        val entries = complete(r, 403)
        assert(entries.size == 1, entries)
        val e = entries.head
        assert(e.category == "pipeline", e)
        assert(e.outcome == "denied")
        assert(e.httpStatus.contains(403))
        assert(e.errorMessage.contains(Detail), e.errorMessage)
        assert(scopeDenied(e).contains(Seq(Detail, Detail2)), e.metadata)
    }

    test("a 403 on an unaudited read route records one security/denied row with required and the detail") {
        val (r, attrs) = request("GET", "/api/v1/pipeline")
        AuditLog.noteScopeDenial(r, "pipeline:read", Detail)
        AuditLog.noteScopeDenial(r, "pipeline:read", Detail2)
        val entries = complete(r, 403)
        assert(entries.size == 1, entries)
        val e = entries.head
        assert(e.category == "security" && e.action == "denied", e)
        assert(e.outcome == "denied")
        assert(e.httpStatus.contains(403))
        assert(e.errorMessage.contains(Detail), e.errorMessage)
        val md = e.metadata.getOrElse(fail("no metadata"))
        assert(md.get("required").getAsString == "pipeline:read", md)
        assert(md.get("reason").getAsString == Detail, md)
        assert(attrs.get(AuditLog.RecordedAttr).contains(java.lang.Boolean.TRUE), "marked recorded, as AuditLog.denied does")
        // an unaudited read that answered 200 (a filtered list) still records nothing
        captured.clear()
        val (ok, _) = request("GET", "/api/v1/pipeline")
        AuditLog.noteScopeDenial(ok, "pipeline:read", Detail)
        assert(complete(ok, 200).isEmpty)
    }

    test("a 200 that filtered members records scopeDenied in metadata and no errorMessage") {
        val (r, attrs) = request("POST", "/api/v1/catalog/CiSales/restore")
        val md = new JsonObject()
        md.addProperty("restored", 2)
        attrs.put(AuditLog.MetadataAttr, md)
        AuditLog.noteScopeDenial(r, "pipeline:create", Detail)
        val entries = complete(r, 200)
        assert(entries.size == 1, entries)
        val e = entries.head
        assert(e.outcome == "success", e)
        assert(e.errorMessage.isEmpty, e.errorMessage)
        assert(scopeDenied(e).contains(Seq(Detail)), e.metadata)
        assert(e.metadata.get.get("restored").getAsInt == 2, "the controller's metadata is kept: " + e.metadata)
        // 207 (some members failed) is the same
        captured.clear()
        val (p, _) = request("POST", "/api/v1/catalog/CiSales/restore")
        AuditLog.noteScopeDenial(p, "pipeline:create", Detail)
        val partial = complete(p, 207)
        assert(partial.size == 1 && partial.head.errorMessage.isEmpty && scopeDenied(partial.head).contains(Seq(Detail)), partial)
    }

    test("no noted denial leaves the entry exactly as today") {
        val (r, _) = request("DELETE", "/api/v1/pipeline")
        val entries = complete(r, 403)
        assert(entries.size == 1, entries)
        assert(entries.head.errorMessage.contains("HTTP 403"), entries.head.errorMessage)
        assert(scopeDenied(entries.head).isEmpty, entries.head.metadata)
        // a success: no errorMessage, no scopeDenied
        captured.clear()
        val (ok, _) = request("POST", "/api/v1/catalog/CiSales/restore")
        val okEntries = complete(ok, 200)
        assert(okEntries.size == 1 && okEntries.head.errorMessage.isEmpty && scopeDenied(okEntries.head).isEmpty, okEntries)
        // an unaudited read refused 403 without a noted denial: still nothing
        captured.clear()
        val (read, _) = request("GET", "/api/v1/pipeline")
        assert(complete(read, 403).isEmpty)
    }

    test("an explicit ErrorMessageAttr wins over the noted detail") {
        val (r, attrs) = request("DELETE", "/api/v1/pipeline")
        attrs.put(AuditLog.ErrorMessageAttr, "explicit controller message")
        AuditLog.noteScopeDenial(r, "pipeline:delete", Detail)
        val entries = complete(r, 403)
        assert(entries.size == 1, entries)
        assert(entries.head.errorMessage.contains("explicit controller message"), entries.head.errorMessage)
        assert(scopeDenied(entries.head).contains(Seq(Detail)), entries.head.metadata)
    }
}
