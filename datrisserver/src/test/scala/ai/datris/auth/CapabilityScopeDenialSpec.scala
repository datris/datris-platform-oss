package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.api.ScopeDenialBodies
import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.model.{Capability, DatrisEnvironment, ResolvedKey}
import ai.datris.util.CatalogOps
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable

/** Story: scoped API keys, a refusal no longer names the hidden resource's
  * catalog or owner (plans/stories/scoped-key-refusal-hides-scope.md).
  *
  * `CapabilityCheck.assertScope` is the one builder of the scope-refusal
  * text. Its `message` (what every controller sends to the client) names the
  * key, the capability it lacks and the key's OWN capabilities for that
  * resource, never the target's scope. Today's text, with the target scope,
  * moves to the exception's `detail` (server log and audit log only).
  *
  * Pinned seam (does not exist at 162fca76; the implementation adds it):
  * {{{
  * // ai.datris.auth
  * class CapabilityDeniedException(msg: String, val detail: String = null) extends DatrisException(msg)
  * object CapabilityCheck {
  *   def scopeDenialMessage(rk: ResolvedKey, resource: String, action: String): String
  *   def scopeDenialDetail(rk: ResolvedKey, resource: String, action: String, context: Map[String, String]): String
  * }
  * // ai.datris.audit.AuditLog
  * val ScopeDenialsAttr: String       // request attribute, set by noteScopeDenial
  * def noteScopeDenial(request: HttpServletRequest, required: String, detail: String): Unit
  * }}}
  * Exact wording: [[ScopeDenialWording]]. */
class CapabilityScopeDenialSpec extends AnyFunSuite with BeforeAndAfterEach {

    import ScopeDenialFixtures.{key, request}

    private val ScopedCaps = Seq("pipeline:read:catalog=CiSales", "pipeline:create:catalog=CiSales", "tap:read:owner=self")
    private val scoped = key("ci_scoped_w", ScopedCaps: _*)
    private val hr = Map("catalog" -> "CiHR", "owner" -> "vscode-claude")
    private val Hidden = Seq("CiHR", "vscode-claude")

    private val Prefix = "capability denied: key 'ci_scoped_w' does not hold capability 'pipeline:read' for the targeted resource"
    private val CatalogKeyMessage =
        Prefix + ". The key's pipeline capabilities are: pipeline:create:catalog=CiSales, pipeline:read:catalog=CiSales. The target is outside that scope."
    private val OldText = Prefix + " (scope: catalog=CiHR, owner=vscode-claude)"

    private var saved: DatrisEnvironment = _
    private val captured = mutable.ArrayBuffer[AuditEntry]()

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        captured.clear()
        AuditLog.sinkOverride = (e: AuditEntry) => captured.synchronized { captured += e; () }
        ScopeDenialFixtures.install(ScopeDenialFixtures.auditEnv())
    }

    override def afterEach(): Unit = {
        AuditLog.sinkOverride = null
        ScopeDenialFixtures.install(saved)
    }

    private def refuse(rk: ResolvedKey, resource: String, action: String, ctx: Map[String, String]): CapabilityDeniedException = {
        val (r, _) = request(rk)
        intercept[CapabilityDeniedException](CapabilityCheck.assertScope(r, resource, action, ctx))
    }

    test("a catalog-scoped key's refusal names the key and the capability and not the target's catalog or owner") {
        val e = refuse(scoped, "pipeline", "read", hr)
        assert(e.getMessage == CatalogKeyMessage, e.getMessage)
        ScopeDenialWording.assertHides(e.getMessage, Hidden)
        // the shared wording helper and the pure builder agree with the literal
        assert(ScopeDenialWording.message("ci_scoped_w", "pipeline", "read", ScopedCaps) == CatalogKeyMessage)
        assert(CapabilityCheck.scopeDenialMessage(scoped, "pipeline", "read") == CatalogKeyMessage)
        // every action on the same resource lists the same own capabilities
        val del = refuse(scoped, "pipeline", "delete", hr)
        assert(
            del.getMessage ==
                "capability denied: key 'ci_scoped_w' does not hold capability 'pipeline:delete' for the targeted resource. " +
                "The key's pipeline capabilities are: pipeline:create:catalog=CiSales, pipeline:read:catalog=CiSales. The target is outside that scope.",
            del.getMessage
        )
    }

    test("an owner=self key's refusal does not name the other owner") {
        val e = refuse(scoped, "tap", "read", Map("owner" -> "other-key"))
        assert(
            e.getMessage ==
                "capability denied: key 'ci_scoped_w' does not hold capability 'tap:read' for the targeted resource. " +
                "The key's tap capabilities are: tap:read:owner=self. The target is outside that scope.",
            e.getMessage
        )
        ScopeDenialWording.assertHides(e.getMessage, Seq("other-key"))
        // a tap with both a catalog and an owner
        val both = refuse(scoped, "tap", "update", hr)
        ScopeDenialWording.assertHides(both.getMessage, Hidden)
        assert(both.getMessage == ScopeDenialWording.message("ci_scoped_w", "tap", "update", ScopedCaps), both.getMessage)
    }

    test("a key holding nothing for the resource is told so") {
        val pipes = key("ci_pipes", "pipeline:read:catalog=CiSales", "pipeline:create:catalog=CiSales")
        val e = refuse(pipes, "secret", "read", Map("_type" -> "platform"))
        assert(
            e.getMessage == "capability denied: key 'ci_pipes' does not hold capability 'secret:read' for the targeted resource. The key holds no secret capability.",
            e.getMessage
        )
        ScopeDenialWording.assertHides(e.getMessage, Seq("platform", "_type"))
        assert(CapabilityCheck.scopeDenialMessage(pipes, "secret", "read") == e.getMessage)
        // the secrets case today: a tap-scoped secret key reading a platform secret
        val tapSecrets = key("ci_tap_secrets", "secret:read:_type=tap")
        val t = refuse(tapSecrets, "secret", "read", Map("_type" -> "platform"))
        assert(
            t.getMessage ==
                "capability denied: key 'ci_tap_secrets' does not hold capability 'secret:read' for the targeted resource. " +
                "The key's secret capabilities are: secret:read:_type=tap. The target is outside that scope.",
            t.getMessage
        )
        ScopeDenialWording.assertHides(t.getMessage, Seq("platform"))
        // an untyped secret (empty context)
        val u = refuse(tapSecrets, "secret", "write", Map.empty)
        assert(u.getMessage == ScopeDenialWording.message("ci_tap_secrets", "secret", "write", Seq("secret:read:_type=tap")), u.getMessage)
    }

    test("a wildcard-resource capability is listed as the key's own") {
        val caps = Seq("*:read:catalog=CiSales", "pipeline:create:catalog=CiSales", "tap:read:owner=self")
        val wild = key("ci_wild", caps: _*)
        val e = refuse(wild, "pipeline", "read", hr)
        assert(
            e.getMessage ==
                "capability denied: key 'ci_wild' does not hold capability 'pipeline:read' for the targeted resource. " +
                "The key's pipeline capabilities are: *:read:catalog=CiSales, pipeline:create:catalog=CiSales. The target is outside that scope.",
            e.getMessage
        )
        ScopeDenialWording.assertHides(e.getMessage, Hidden)
        val t = refuse(wild, "tap", "read", Map("owner" -> "other-key"))
        assert(
            t.getMessage ==
                "capability denied: key 'ci_wild' does not hold capability 'tap:read' for the targeted resource. " +
                "The key's tap capabilities are: *:read:catalog=CiSales, tap:read:owner=self. The target is outside that scope.",
            t.getMessage
        )
        // a resource only the wildcard covers
        val s = refuse(wild, "secret", "read", Map("_type" -> "platform"))
        assert(
            s.getMessage ==
                "capability denied: key 'ci_wild' does not hold capability 'secret:read' for the targeted resource. " +
                "The key's secret capabilities are: *:read:catalog=CiSales. The target is outside that scope.",
            s.getMessage
        )
    }

    test("more than 8 own capabilities are cut with an ellipsis") {
        val ten = (0 until 10).map(i => f"pipeline:read:catalog=C$i%02d")
        // shuffled, with a duplicate: the list is distinct and sorted
        val manyCaps = (ten.reverse :+ "pipeline:read:catalog=C00") :+ "tap:read:owner=self"
        val many = key("ci_many", manyCaps: _*)
        val e = refuse(many, "pipeline", "read", hr)
        val listed = (0 until 8).map(i => f"pipeline:read:catalog=C$i%02d").mkString(", ")
        assert(
            e.getMessage ==
                "capability denied: key 'ci_many' does not hold capability 'pipeline:read' for the targeted resource. " +
                "The key's pipeline capabilities are: " + listed + ", .... The target is outside that scope.",
            e.getMessage
        )
        assert(!e.getMessage.contains("C08") && !e.getMessage.contains("C09"), e.getMessage)
        // exactly 8: no ellipsis
        val eight = key("ci_eight", ten.take(8): _*)
        val e8 = refuse(eight, "pipeline", "read", hr)
        assert(
            e8.getMessage ==
                "capability denied: key 'ci_eight' does not hold capability 'pipeline:read' for the targeted resource. " +
                "The key's pipeline capabilities are: " + listed + ". The target is outside that scope.",
            e8.getMessage
        )
    }

    test("the message keeps the prefix through 'for the targeted resource'") {
        val e = refuse(scoped, "pipeline", "read", hr)
        assert(e.getMessage.startsWith(Prefix + ". The key"), e.getMessage)
        val none = refuse(scoped, "secret", "read", Map("_type" -> "platform"))
        assert(
            none.getMessage.startsWith("capability denied: key 'ci_scoped_w' does not hold capability 'secret:read' for the targeted resource. The key"),
            none.getMessage
        )
        assert(none.getMessage.startsWith(ScopeDenialWording.prefix("ci_scoped_w", "secret", "read")))
    }

    test("detail carries today's text with the target scope") {
        val e = refuse(scoped, "pipeline", "read", hr)
        assert(e.detail == OldText, e.detail)
        assert(CapabilityCheck.scopeDenialDetail(scoped, "pipeline", "read", hr) == OldText)
        assert(ScopeDenialWording.detail("ci_scoped_w", "pipeline", "read", hr) == OldText)
        // empty context: today's em dash
        val fp = refuse(scoped, "protect", "reveal", Map.empty)
        assert(fp.detail == "capability denied: key 'ci_scoped_w' does not hold capability 'protect:reveal' for the targeted resource (scope: —)", fp.detail)
        assert(
            fp.getMessage == "capability denied: key 'ci_scoped_w' does not hold capability 'protect:reveal' for the targeted resource. The key holds no protect capability."
        )
        // secrets: the target's _type lives in the detail only
        val s = refuse(scoped, "secret", "read", Map("_type" -> "platform"))
        assert(
            s.detail == "capability denied: key 'ci_scoped_w' does not hold capability 'secret:read' for the targeted resource (scope: _type=platform)",
            s.detail
        )
        // assertOwnerScope goes through the same builder
        val (r, _) = request(scoped)
        val o = intercept[CapabilityDeniedException](CapabilityCheck.assertOwnerScope(r, "tap", "read", "other-key"))
        assert(o.detail == "capability denied: key 'ci_scoped_w' does not hold capability 'tap:read' for the targeted resource (scope: owner=other-key)")
        ScopeDenialWording.assertHides(o.getMessage, Seq("other-key"))
    }

    test("a restricted session still gets ResourceHiddenException from assertVisible and no denial is noted") {
        val bob =
            ResolvedKey(None, "session:bob", Capability.parseList(Seq("pipeline:read:catalog=CiSales")), isLegacyFullAccess = false, restrictedSession = true)
        val (r, attrs) = request(bob)
        intercept[ResourceHiddenException](CapabilityCheck.assertVisible(r, "pipeline", "read", hr))
        assert(!attrs.contains(AuditLog.ScopeDenialsAttr), attrs.keySet)
        // the same capabilities on an API key: the 403, and the denial is noted
        val (k, kAttrs) = request(scoped)
        val e = intercept[CapabilityDeniedException](CapabilityCheck.assertVisible(k, "pipeline", "read", hr))
        assert(e.getMessage == CatalogKeyMessage)
        assert(kAttrs.contains(AuditLog.ScopeDenialsAttr), kAttrs.keySet)
        // assertScope itself writes no audit row (the interceptor does, once)
        assert(captured.isEmpty, captured)
    }

    test("a legacy full-access key and a request with no key never throw") {
        val legacy = ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true)
        val (lr, lAttrs) = request(legacy)
        val (nr, nAttrs) = request(null)
        for ((r, attrs) <- Seq(lr -> lAttrs, nr -> nAttrs)) {
            CapabilityCheck.assertScope(r, "pipeline", "read", hr)
            CapabilityCheck.assertScope(r, "secret", "read", Map("_type" -> "platform"))
            CapabilityCheck.assertVisible(r, "tap", "update", hr)
            CapabilityCheck.assertOwnerScope(r, "tap", "read", "other-key")
            assert(!attrs.contains(AuditLog.ScopeDenialsAttr), attrs.keySet)
        }
        CapabilityCheck.assertScope(null, "pipeline", "read", hr)
        CapabilityCheck.assertVisible(null, "pipeline", "read", hr)
        // an in-scope key does not throw or note anything either
        val (ir, iAttrs) = request(scoped)
        CapabilityCheck.assertScope(ir, "pipeline", "read", Map("catalog" -> "CiSales", "owner" -> "vscode-claude"))
        assert(!iAttrs.contains(AuditLog.ScopeDenialsAttr))
    }

    test("the 403 body keeps exactly error, errorKind, message") {
        val e = refuse(scoped, "pipeline", "read", hr)
        val res = ScopeDenialBodies.pipeline(e)
        assert(res.getStatusCode.value == 403)
        ScopeDenialWording.assertBody(res.getBody, CatalogKeyMessage, Hidden)
        ScopeDenialWording.assertBody(CatalogOps.capabilityDeniedBody(e.getMessage), CatalogKeyMessage, Hidden)
        // the bare refusal some paths build keeps the same keys
        val bare = ScopeDenialBodies.pipeline(new CapabilityDeniedException("capability denied"))
        assert(bare.getStatusCode.value == 403)
        ScopeDenialWording.assertBody(bare.getBody, "capability denied", Hidden)
    }
}
