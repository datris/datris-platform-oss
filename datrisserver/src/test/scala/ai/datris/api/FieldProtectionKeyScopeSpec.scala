package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.{CapabilityDeniedException, CapabilityScopes, ScopeDenialFixtures, ScopeDenialWording}
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, CatalogOps, FieldCipher}
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.JsonParser
import jakarta.servlet.http.HttpServletRequest
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._
import scala.collection.mutable

/** Story: Field protection, reveal and per-pipeline protection reads are held
  * to the caller's catalog / owner scope
  * (plans/stories/field-protection-key-scope.md), Acceptance
  * `FieldProtectionKeyScopeSpec`. Pipelines: `orders` in catalog `Sales`
  * owned by `sales-key`, `payroll` in `HR` owned by `hr-key`.
  *
  * No Spring, no Mongo, no Vault: `reveal`, `suggest` and `preset` read the
  * stored config through `PipelineConfigIO` and the key through
  * `FieldProtectionKey`, so the decisions are pinned on the pure seams the
  * story names, and the route bodies only where they refuse before any read.
  * Keys are resolved as `APIKeyValidator.resolveAgainst` does
  * (`CapabilityScopes.partition`), so a scope the platform does not apply on
  * a capability is inert here exactly as on a stored key.
  *
  * Pinned seams (the story's Files and Design; `targetRefusal` does not exist
  * at 8babd475, it replaces `hideFromRestricted`):
  * {{{
  * object FieldProtectionAPIController {
  *   // Design 1-3. Throws DatrisException("Pipeline: <pipeline> is not configured in the NoSQL database")
  *   // for a restricted session outside its grants; CapabilityDeniedException (shared wording,
  *   // AuditLog.noteScopeDenial) for a key refused pipeline:read on the target and then, when
  *   // protectAction = Some("reveal"), for a key refused protect:reveal on the target
  *   // (context = CatalogOps.scopeContext(config.catalog, config.createdByKeyLabel)). Else no-op.
  *   private[api] def targetRefusal(request: HttpServletRequest, pipeline: String,
  *                                  config: PipelineConfig, protectAction: Option[String]): Unit
  *   // unchanged signature; "reveal" becomes scope-agnostic (Design 3), "admin" keeps Map.empty
  *   private[api] def requireCapability(request: HttpServletRequest, action: String): Unit
  *   private[api] def holdsCapability(request: HttpServletRequest, action: String): Boolean
  *   // the 403 plus one security/denied audit row carrying e.detail and the pipeline and field names;
  *   // `required` is the protect action as today's `action` parameter ("reveal")
  *   private[api] def denied(request: HttpServletRequest, e: CapabilityDeniedException,
  *                           required: String, pipeline: String, field: String): ResponseEntity[String]
  * }
  * object CapabilityScopes {   // D1 / D3: catalog and owner applied on protect:reveal only
  *   def supported(resource: String, action: String): Seq[String]
  * }
  * }}}
  */
class FieldProtectionKeyScopeSpec extends AnyFunSuite with BeforeAndAfterEach {

    private val orders = PipelineConfig(name = "orders", catalog = "Sales", createdByKeyLabel = "sales-key")
    private val payroll = PipelineConfig(name = "payroll", catalog = "HR", createdByKeyLabel = "hr-key")
    private val Hidden = Seq("HR", "hr-key")

    /** As key resolution builds it: applied and inert capabilities split. */
    private def resolved(label: String, caps: String*): ResolvedKey = {
        val (applied, inert) = CapabilityScopes.partition(Capability.parseList(caps))
        ResolvedKey(None, label, applied, isLegacyFullAccess = false, inertCapabilities = inert)
    }

    private def req(rk: ResolvedKey, uri: String = "/api/v1/protect/reveal"): HttpServletRequest =
        ScopeDenialFixtures.request(rk, "POST", uri)._1

    private def session(role: String, restricted: Boolean, g: java.util.Map[String, String] = null): ResolvedKey =
        APIKeyValidator.resolveFromSession(User("bob", "h", role, null, null, null, restricted = restricted, catalogGrants = g))

    private val Reveal = Some("reveal")

    private def refusedWith(r: HttpServletRequest, config: PipelineConfig, action: Option[String]): CapabilityDeniedException =
        intercept[CapabilityDeniedException](FieldProtectionAPIController.targetRefusal(r, config.name, config, action))

    /** The refusal is the shared wording for (resource, action), hides the
      * target's catalog and owner, and is noted for the audit entry. */
    private def assertScopeRefusal(
        r: HttpServletRequest,
        e: CapabilityDeniedException,
        label: String,
        resource: String,
        action: String,
        caps: Seq[String]
    ): Unit = {
        assert(e.getMessage == ScopeDenialWording.message(label, resource, action, caps), e.getMessage)
        ScopeDenialWording.assertHides(e.getMessage, Hidden)
        assert(AuditLog.scopeDenials(r).map(_._1).contains(resource + ":" + action), AuditLog.scopeDenials(r))
    }

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
        sys.props.remove(CapabilityScopes.InertScopesProperty)
    }

    // ------------------------------------------------------ Acceptance bullets 1-2

    test("a catalog-scoped key holding protect:reveal is refused reveal for a pipeline in another catalog") {
        val caps = Seq("pipeline:read:catalog=Sales", "protect:reveal")
        val r = req(resolved("sales-reader", caps: _*))
        val e = refusedWith(r, payroll, Reveal)
        assertScopeRefusal(r, e, "sales-reader", "pipeline", "read", caps)
        // the detail (log and audit only) keeps the target's scope
        assert(e.detail != null && e.detail.contains("catalog=HR"), e.detail)
    }

    test("the same key passes for a pipeline in its catalog") {
        val r = req(resolved("sales-reader", "pipeline:read:catalog=Sales", "protect:reveal"))
        FieldProtectionAPIController.targetRefusal(r, "orders", orders, Reveal)
        FieldProtectionAPIController.targetRefusal(r, "orders", orders, None)
        assert(AuditLog.scopeDenials(r).isEmpty)
    }

    // ------------------------------------------------------ Acceptance bullet 3

    test("an owner=self key is refused another key's pipeline and passes its own") {
        val caps = Seq("pipeline:read:owner=self", "protect:reveal")
        val r = req(resolved("sales-key", caps: _*))
        val e = refusedWith(r, payroll, Reveal)
        assertScopeRefusal(r, e, "sales-key", "pipeline", "read", caps)
        FieldProtectionAPIController.targetRefusal(req(resolved("sales-key", caps: _*)), "orders", orders, Reveal)
        // suggest / preset path likewise
        intercept[CapabilityDeniedException](FieldProtectionAPIController.targetRefusal(req(resolved("sales-key", caps: _*)), "payroll", payroll, None))
        FieldProtectionAPIController.targetRefusal(req(resolved("sales-key", caps: _*)), "orders", orders, None)
    }

    // ------------------------------------------------------ Acceptance bullet 4 (D1)

    test("a scoped reveal capability is evaluated against the target") {
        // protect:reveal:catalog=Sales + unscoped pipeline:read
        val catCaps = Seq("pipeline:read", "protect:reveal:catalog=Sales")
        FieldProtectionAPIController.targetRefusal(req(resolved("reveal-sales", catCaps: _*)), "orders", orders, Reveal)
        val rc = req(resolved("reveal-sales", catCaps: _*))
        assertScopeRefusal(rc, refusedWith(rc, payroll, Reveal), "reveal-sales", "protect", "reveal", catCaps)

        // protect:reveal:owner=self + unscoped pipeline:read
        val ownCaps = Seq("pipeline:read", "protect:reveal:owner=self")
        FieldProtectionAPIController.targetRefusal(req(resolved("sales-key", ownCaps: _*)), "orders", orders, Reveal)
        val ro = req(resolved("sales-key", ownCaps: _*))
        assertScopeRefusal(ro, refusedWith(ro, payroll, Reveal), "sales-key", "protect", "reveal", ownCaps)

        // the reveal scope is not applied on the suggest / preset path (pipeline:read only)
        FieldProtectionAPIController.targetRefusal(req(resolved("reveal-sales", catCaps: _*)), "payroll", payroll, None)
    }

    test("a scoped reveal key must also read the pipeline in scope; pipeline read is checked before the reveal scope") {
        // reveal in scope (Sales) but pipeline read out of scope (owner=self, orders is sales-key's)
        val caps = Seq("pipeline:read:owner=self", "protect:reveal:catalog=Sales")
        val r = req(resolved("other-key", caps: _*))
        assertScopeRefusal(r, refusedWith(r, orders, Reveal), "other-key", "pipeline", "read", caps)
        // the owner of orders passes both
        FieldProtectionAPIController.targetRefusal(req(resolved("sales-key", caps: _*)), "orders", orders, Reveal)
        // both out of scope: the 403 names pipeline:read (Design 2 before 3)
        val both = req(resolved("sales-key", caps: _*))
        assertScopeRefusal(both, refusedWith(both, payroll, Reveal), "sales-key", "pipeline", "read", caps)
    }

    test("D2 reading: a scoped reveal key with no pipeline capability is held to its reveal scope only") {
        val caps = Seq("protect:reveal:catalog=Sales")
        FieldProtectionAPIController.targetRefusal(req(resolved("reveal-only-sales", caps: _*)), "orders", orders, Reveal)
        val r = req(resolved("reveal-only-sales", caps: _*))
        assertScopeRefusal(r, refusedWith(r, payroll, Reveal), "reveal-only-sales", "protect", "reveal", caps)
    }

    // ------------------------------------------------------ Acceptance bullet 5

    test("a scoped reveal capability passes the pre-read gate; a key with no reveal capability is refused before any read") {
        Seq(
            Seq("protect:reveal:catalog=Sales"),
            Seq("protect:reveal:owner=self"),
            Seq("protect:reveal:catalog=Sales,owner=self"),
            Seq("pipeline:read:catalog=Sales", "protect:reveal:catalog=Sales")
        ).foreach { caps =>
            FieldProtectionAPIController.requireCapability(req(resolved("rk", caps: _*)), "reveal")
        }
        // unscoped reveal is unchanged
        FieldProtectionAPIController.requireCapability(req(resolved("rk", "protect:reveal")), "reveal")

        // no reveal capability: the gate refuses with the shared wording
        Seq(Seq("pipeline:read:catalog=Sales"), Seq("pipeline:read", "protect:admin"), Seq("protect:reveal:database=x")).foreach { caps =>
            val r = req(resolved("rk", caps: _*))
            val e = intercept[CapabilityDeniedException](FieldProtectionAPIController.requireCapability(r, "reveal"))
            assert(
                e.getMessage == ScopeDenialWording.message("rk", "protect", "reveal", resolved("rk", caps: _*).effectiveCapabilities.map(_.raw)),
                e.getMessage
            )
        }
        // and the route answers 403 before the body, the config or a key is read
        // (no Mongo and no secret store is reachable from this spec)
        val c = new FieldProtectionAPIController
        val res = c.reveal(
            "any",
            RevealRequest("payroll", "ssn", java.util.Arrays.asList("enc:v1:AAAA")),
            req(resolved("rk", "pipeline:read:catalog=Sales"))
        )
        assert(res.getStatusCode.value == 403, res.getBody)
        ScopeDenialWording.assertBody(res.getBody, ScopeDenialWording.message("rk", "protect", "reveal", Nil), Hidden)
    }

    // ------------------------------------------------------ Acceptance bullet 6

    test("a scoped protect:admin is still refused") {
        def check(): Unit =
            Seq("protect:admin:catalog=Sales", "protect:admin:owner=self").foreach { raw =>
                // as key resolution builds it, and as stored before inert scopes were split
                Seq(resolved("adm", raw), ScopeDenialFixtures.key("adm", raw)).foreach { rk =>
                    intercept[CapabilityDeniedException](FieldProtectionAPIController.requireCapability(req(rk, "/api/v1/protect/keys/rotate"), "admin"))
                    assert(!FieldProtectionAPIController.holdsCapability(req(rk), "admin"), raw)
                }
            }
        check()
        sys.props.put(CapabilityScopes.InertScopesProperty, CapabilityScopes.InertScopesAllow)
        check()
        // the rotate route answers 403 before any key material is touched
        sys.props.remove(CapabilityScopes.InertScopesProperty)
        val res = new FieldProtectionAPIController().rotateKey("any", req(resolved("adm", "protect:admin:catalog=Sales"), "/api/v1/protect/keys/rotate"))
        assert(res.getStatusCode.value == 403, res.getBody)
        // unscoped admin keeps working
        FieldProtectionAPIController.requireCapability(req(resolved("adm", "protect:admin")), "admin")
        assert(FieldProtectionAPIController.holdsCapability(req(resolved("adm", "protect:admin")), "admin"))
    }

    // ------------------------------------------------------ Acceptance bullet 7 (D2)

    test("a reveal-only key with no pipeline capability is unchanged") {
        val r = req(resolved("reveal-op", "protect:reveal"))
        FieldProtectionAPIController.targetRefusal(r, "orders", orders, Reveal)
        FieldProtectionAPIController.targetRefusal(r, "payroll", payroll, Reveal)
        // and an unscoped reveal key with unscoped pipeline:read
        val wide = req(resolved("reveal-wide", "pipeline:read", "protect:reveal"))
        FieldProtectionAPIController.targetRefusal(wide, "orders", orders, Reveal)
        FieldProtectionAPIController.targetRefusal(wide, "payroll", payroll, Reveal)
        assert(AuditLog.scopeDenials(r).isEmpty && AuditLog.scopeDenials(wide).isEmpty)
    }

    // ------------------------------------------------------ Acceptance bullet 8

    test("full-access, legacy keys, an unrestricted editor session and a request with no key take no refusal") {
        val full = req(resolved("full", "*:*"))
        val legacy = req(ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true))
        val noKey = req(null)
        val admin = req(session(User.RoleAdmin, restricted = false))
        for (r <- Seq(full, legacy, noKey, admin); c <- Seq(orders, payroll); a <- Seq(None, Reveal))
            FieldProtectionAPIController.targetRefusal(r, c.name, c, a)
        // an unrestricted editor session holds no protect capability (refused at the gate);
        // on the suggest / preset path it sees every pipeline
        val editor = req(session(User.RoleEditor, restricted = false))
        Seq(orders, payroll).foreach(c => FieldProtectionAPIController.targetRefusal(editor, c.name, c, None))
        intercept[CapabilityDeniedException](FieldProtectionAPIController.requireCapability(editor, "reveal"))
    }

    // ------------------------------------------------------ Acceptance bullet 9

    test("a restricted session gets the missing-pipeline error, byte-identical to an unknown name") {
        val missing = "Pipeline: payroll is not configured in the NoSQL database"
        val s = req(session(User.RoleEditor, restricted = true, grants("Sales" -> "view")), "/api/v1/pipeline/protect/preset")
        Seq(None, Reveal).foreach { a =>
            val e = intercept[DatrisException](FieldProtectionAPIController.targetRefusal(s, "payroll", payroll, a))
            assert(!e.isInstanceOf[CapabilityDeniedException], e.getClass)
            assert(e.getMessage == missing, e.getMessage)
        }
        FieldProtectionAPIController.targetRefusal(s, "orders", orders, None)
        // reveal stays refused at the gate for a restricted session (no role carries protect:*)
        intercept[CapabilityDeniedException](FieldProtectionAPIController.requireCapability(s, "reveal"))
    }

    // ------------------------------------------------------ Acceptance bullet 10

    test("suggest and preset refuse a scope-limited key for a pipeline outside its scope with the 403 body shape") {
        val caps = Seq("pipeline:read:catalog=Sales")
        val r = req(resolved("sales-reader", caps: _*), "/api/v1/pipeline/protect/suggest")
        val e = refusedWith(r, payroll, None)
        val expected = ScopeDenialWording.message("sales-reader", "pipeline", "read", caps)
        assertScopeRefusal(r, e, "sales-reader", "pipeline", "read", caps)
        ScopeDenialWording.assertBody(FieldProtectionAPIController.denied(r, e, "reveal", "payroll", null).getBody, expected, Hidden)
        ScopeDenialWording.assertBody(PipelineAPIController.capabilityDenied(e).getBody, expected, Hidden)
        assert(FieldProtectionAPIController.denied(r, e, "reveal", "payroll", null).getStatusCode.value == 403)

        // both routes run targetRefusal on the stored config and map its
        // CapabilityDeniedException to the 403 before their DatrisException 400
        val src = source("src/main/scala/ai/datris/api/FieldProtectionAPIController.scala")
        Seq("def suggest(", "def preset(").foreach { sig =>
            val body = methodBody(src, sig)
            assert(body.contains("targetRefusal("), sig + " must call targetRefusal")
            assert(!body.contains("hideFromRestricted("), sig + " still calls hideFromRestricted")
            val denied = "case \\w+: CapabilityDeniedException".r.findFirstMatchIn(body).map(_.start)
            val datris = "case \\w+: DatrisException".r.findFirstMatchIn(body).map(_.start)
            assert(denied.isDefined && datris.forall(_ > denied.get), sig + " must map CapabilityDeniedException to its 403 before the DatrisException 400")
        }
    }

    // ------------------------------------------------------ Acceptance bullet 11

    test("the scope refusal comes before the field check") {
        // payroll has no encrypt field at all (no source schema): still the 403
        val caps = Seq("pipeline:read:catalog=Sales", "protect:reveal")
        val r = req(resolved("sales-reader", caps: _*))
        val e = refusedWith(r, payroll, Reveal)
        assert(!e.getMessage.contains("is not protected with method 'encrypt'"), e.getMessage)
        assert(!e.getMessage.contains("payroll") && !e.getMessage.contains("ssn"), e.getMessage)
        // a scoped reveal key likewise
        val rs = req(resolved("reveal-sales", "pipeline:read", "protect:reveal:catalog=Sales"))
        assert(!refusedWith(rs, payroll, Reveal).getMessage.contains("is not protected"))

        // reveal's order: gate, body validation, config read, targetRefusal,
        // field check, keys, decrypt
        val body = methodBody(source("src/main/scala/ai/datris/api/FieldProtectionAPIController.scala"), "def reveal(")
        def at(s: String): Int = { val i = body.indexOf(s); assert(i >= 0, "reveal has no '" + s + "'"); i }
        val gate = at("requireCapability(request, \"reveal\")")
        val read = at("PipelineConfigIO.read(")
        val refusal = at("targetRefusal(")
        assert(gate < read && read < refusal, "the gate runs before the config read, targetRefusal after it")
        Seq("isEncrypt", "is not protected with method 'encrypt'", "FieldProtectionKey.encryptionKey", "decryptAll(").foreach { s =>
            assert(refusal < at(s), "targetRefusal must run before '" + s + "'")
        }
    }

    // ------------------------------------------------------ Acceptance bullet 12

    test("a ciphertext from another pipeline or field does not reveal under an authorised pipeline") {
        val k: Array[Byte] = Array.tabulate[Byte](32)(i => (i * 7).toByte)
        val lookup: Int => Array[Byte] = v => if (v == 1) k else null
        val hr = FieldCipher.encrypt(k, 1, "payroll", "ssn", "123-45-6789")
        val phone = FieldCipher.encrypt(k, 1, "orders", "phone", "555-0100")
        val mine = FieldCipher.encrypt(k, 1, "orders", "email", "jane@example.com")
        val (values, errors, revealed, failed) = FieldProtectionAPIController.decryptAll(lookup, "orders", "email", Seq(hr, phone, mine))
        assert(values.get(0).isJsonNull && values.get(1).isJsonNull, values)
        assert(values.get(2).getAsString == "jane@example.com", values)
        assert(failed == 2 && revealed == 1, s"failed=$failed revealed=$revealed")
        assert(errors.asScala.map(_.getAsJsonObject.get("index").getAsInt).toList == List(0, 1), errors)
        assert(!errors.toString.contains("123-45-6789") && !errors.toString.contains("555-0100"), errors)
        // only the authorised two in isolation
        val (_, _, r2, f2) = FieldProtectionAPIController.decryptAll(lookup, "orders", "email", Seq(hr, phone))
        assert(r2 == 0 && f2 == 2)
        // reveal binds the stored config's name, never the caller's spelling
        val body = methodBody(source("src/main/scala/ai/datris/api/FieldProtectionAPIController.scala"), "def reveal(")
        assert(body.contains("Option(config.name).getOrElse(pipeline)"), "reveal must decrypt under the authorised config's stored name")
    }

    // ------------------------------------------------------ Acceptance bullet 13

    test("the denied audit detail names the pipeline and field and never a value or ciphertext") {
        val token = "enc:v1:QUJDREVGR0hJSktMTU5PUA"
        val caps = Seq("pipeline:read:catalog=Sales", "protect:reveal")
        val r = req(resolved("sales-reader", caps: _*))
        val e = refusedWith(r, payroll, Reveal)
        val res = FieldProtectionAPIController.denied(r, e, "reveal", "payroll", "ssn")
        assert(res.getStatusCode.value == 403, res.getBody)
        // the body: shared wording, nothing about the target, no names
        ScopeDenialWording.assertBody(res.getBody, ScopeDenialWording.message("sales-reader", "pipeline", "read", caps), Hidden)
        assert(!res.getBody.contains("payroll") && !res.getBody.contains("ssn"), res.getBody)

        val rows = captured.synchronized(captured.toList).filter(x => x.category == "security" && x.action == "denied")
        assert(rows.size == 1, captured)
        val row = rows.head.toJson.toString
        assert(rows.head.errorMessage.exists(_.contains(e.detail)), "denied row must keep the detail: " + rows.head.errorMessage)
        assert(row.contains("payroll"), "denied row must name the pipeline: " + row)
        assert(row.contains("ssn"), "denied row must name the field: " + row)
        assert(!row.contains(token) && !row.contains("enc:v1:"), row)
        // no protect/reveal success or failure row for a denied call
        assert(!captured.exists(x => x.category == "protect"), captured)
    }

    // ------------------------------------------------------ D1 / D3: the inert-scope table for protect

    test("D1/D3: catalog and owner are applied on protect:reveal and nothing on protect:admin") {
        assert(CapabilityScopes.supported("protect", "reveal").toSet == Set("catalog", "owner"), CapabilityScopes.supported("protect", "reveal"))
        assert(CapabilityScopes.supported("protect", "admin").isEmpty, CapabilityScopes.supported("protect", "admin"))
        assert(CapabilityScopes.supportedOnResource("protect").toSet == Set("catalog", "owner"))
    }

    test("D1: issueProblem accepts catalog and owner=self on protect:reveal and still refuses every other protect scope") {
        Seq(
            "protect:reveal",
            "protect:admin",
            "protect:reveal:catalog=Sales",
            "protect:reveal:owner=self",
            "protect:reveal:catalog=Sales,owner=self",
            "protect:reveal:catalog=*"
        ).foreach(raw => assert(CapabilityScopes.issueProblem(raw).isEmpty, raw + " -> " + CapabilityScopes.issueProblem(raw)))

        def refused(raw: String): String = CapabilityScopes.issueProblem(raw).getOrElse(fail("'" + raw + "' must be refused at issue"))
        Seq("database=x", "collection=docs", "destination_kind=qdrant", "_type=tap").foreach { s =>
            val m = refused("protect:reveal:" + s)
            assert(m.contains("Supported scopes for protect:reveal: catalog, owner."), m)
        }
        Seq("catalog=Sales", "owner=self", "database=x", "collection=docs", "destination_kind=qdrant", "_type=tap").foreach { s =>
            val m = refused("protect:admin:" + s)
            assert(m.contains("scope '" + s.takeWhile(_ != '=') + "' is not applied on protect:admin"), m)
            assert(m.contains("Supported scopes for protect:admin: none."), m)
        }
        // a scoped wildcard over protect covers admin, which applies no scope
        refused("protect:*:catalog=Sales")
    }

    test("D1: partition classifies a stored protect:reveal:catalog=Sales as applied and protect:admin:catalog=Sales as inert") {
        val applied = Capability.parseList(Seq("protect:reveal:catalog=Sales", "protect:reveal:owner=self", "protect:reveal:catalog=Sales,owner=self"))
        val inert = Capability.parseList(Seq("protect:admin:catalog=Sales", "protect:admin:owner=self", "protect:reveal:database=x"))
        val (kept, moved) = CapabilityScopes.partition(applied ++ inert)
        applied.foreach(c => assert(kept.exists(_ eq c), "same instance kept: " + c.raw))
        assert(kept.size == applied.size, kept.map(_.raw))
        assert(moved.map(_.raw).toSet == inert.map(_.raw).toSet, moved.map(_.raw))
        assert(
            CapabilityScopes.inertOf(Seq("protect:reveal:catalog=Sales", "protect:admin:catalog=Sales")) == Seq("protect:admin:catalog=Sales")
        )
        // the Keys list no longer flags a scoped reveal capability
        val meta = """{"capabilities":["pipeline:read","protect:reveal:catalog=Sales"],"revoked":false,"keyId":"k_1"}"""
        val row = KeysAPIController.buildListRow("reveal-sales", Some(meta))
        assert(row.getAsJsonArray("inertCapabilities").size == 0, row)
        // and the resolved key grants reveal in its scope only
        val rk = resolved("reveal-sales", "protect:reveal:catalog=Sales")
        assert(rk.inertCapabilities.isEmpty, rk.inertCapabilities.map(_.raw))
        assert(rk.grants("protect", "reveal", CatalogOps.scopeContext("Sales", "x")))
        assert(!rk.grants("protect", "reveal", CatalogOps.scopeContext("HR", "x")))
    }

    test("D3: the capability catalogue offers exactly catalog and owner for protect") {
        val body = new KeysAPIController().capabilitiesCatalog().getBody
        val protect = JsonParser.parseString(body).getAsJsonObject.getAsJsonArray("resources").asScala
            .map(_.getAsJsonObject).find(_.get("resource").getAsString == "protect")
            .getOrElse(fail("no protect entry: " + body))
        assert(protect.keySet().asScala.toSet == Set("resource", "actions", "scopeKeys"), protect)
        assert(protect.getAsJsonArray("actions").asScala.map(_.getAsString).toList == List("reveal", "admin"), protect)
        assert(protect.getAsJsonArray("scopeKeys").asScala.map(_.getAsString).toList == List("catalog", "owner"), protect)
    }

    // ------------------------------------------------------ helpers

    private def source(rel: String): String = {
        val file = Seq(java.nio.file.Paths.get(rel), java.nio.file.Paths.get("datrisserver", rel))
            .find(java.nio.file.Files.isRegularFile(_)).getOrElse(fail(rel + " not found"))
        new String(java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8)
    }

    /** From `signature` to the next method of the same indentation. */
    private def methodBody(src: String, signature: String): String = {
        val i = src.indexOf(signature)
        assert(i >= 0, signature)
        val j = src.indexOf("\n    def ", i + signature.length)
        if (j < 0) src.substring(i) else src.substring(i, j)
    }
}
