package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.{CapabilityDeniedException, CapabilityScopes, ScopeDenialFixtures, ScopeDenialWording}
import ai.datris.model._
import ai.datris.util.CatalogOps
import jakarta.servlet.http.HttpServletRequest
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

/** Lead ruling 1 on plans/stories/field-protection-key-scope.md: only an
  * explicit `protect:reveal:<scope>` carries an applied `catalog` / `owner`
  * scope on reveal. A stored scoped wildcard (`*:*:catalog=Sales`,
  * `protect:*:catalog=Sales`, `*:reveal:catalog=Sales`) grants no reveal, as
  * before scoped reveal existed, in both `DATRIS_KEY_INERT_SCOPES` modes;
  * issue refuses it and says to grant `protect:reveal:<scope>` explicitly;
  * `*:*:catalog=Sales` still narrows to its pipeline and tap capabilities. */
class ProtectWildcardScopeSpec extends AnyFunSuite with BeforeAndAfterEach {

    private val orders = PipelineConfig(name = "orders", catalog = "Sales", createdByKeyLabel = "sales-key")
    private val salesCtx = CatalogOps.scopeContext("Sales", "sales-key")

    private val ScopedWildcards = Seq(
        "*:*:catalog=Sales",
        "protect:*:catalog=Sales",
        "*:reveal:catalog=Sales",
        "*:*:owner=self",
        "protect:*:owner=self",
        "*:reveal:owner=self"
    )

    private def resolved(label: String, caps: String*): ResolvedKey = {
        val (applied, inert) = CapabilityScopes.partition(Capability.parseList(caps))
        ResolvedKey(None, label, applied, isLegacyFullAccess = false, inertCapabilities = inert)
    }

    private def req(rk: ResolvedKey): HttpServletRequest =
        ScopeDenialFixtures.request(rk, "POST", "/api/v1/protect/reveal")._1

    override def afterEach(): Unit = sys.props.remove(CapabilityScopes.InertScopesProperty)

    test("partition: a scoped wildcard narrows to nothing on protect; *:*:catalog=Sales still keeps pipeline:* and tap:*") {
        val (wide, wideInert) = CapabilityScopes.partition(Capability.parseList(Seq("*:*:catalog=Sales")))
        assert(wide.map(_.raw).toSet == Set("pipeline:*:catalog=Sales", "tap:*:catalog=Sales"), wide.map(_.raw))
        assert(wideInert.map(_.raw) == Seq("*:*:catalog=Sales"))
        val (own, _) = CapabilityScopes.partition(Capability.parseList(Seq("*:*:owner=self")))
        assert(own.map(_.raw).toSet == Set("pipeline:*:owner=self", "tap:*:owner=self", "approval:read:owner=self"), own.map(_.raw))

        Seq("protect:*:catalog=Sales", "*:reveal:catalog=Sales", "protect:*:owner=self", "*:reveal:owner=self").foreach { raw =>
            val (kept, inert) = CapabilityScopes.partition(Capability.parseList(Seq(raw)))
            assert(kept.isEmpty, raw + " -> " + kept.map(_.raw))
            assert(inert.map(_.raw) == Seq(raw), raw)
        }
        assert(CapabilityScopes.inertOf(ScopedWildcards) == ScopedWildcards)
    }

    test("issue refuses every scoped wildcard over protect:reveal and says to grant protect:reveal explicitly") {
        def refused(raw: String): String = CapabilityScopes.issueProblem(raw).getOrElse(fail("'" + raw + "' must be refused at issue"))
        Seq(
            "protect:*:catalog=Sales" -> "protect:reveal:catalog=Sales",
            "*:reveal:catalog=Sales" -> "protect:reveal:catalog=Sales",
            "protect:*:owner=self" -> "protect:reveal:owner=self",
            "*:reveal:owner=self" -> "protect:reveal:owner=self"
        ).foreach {
            case (raw, explicit) =>
                val m = refused(raw)
                assert(m.contains("a scoped wildcard does not grant protect:reveal"), m)
                assert(m.contains("grant '" + explicit + "'"), m)
        }
        // *:*:catalog=Sales is refused on its first pair that applies no catalog, as before,
        // and the advice now also names the explicit reveal capability
        val wide = refused("*:*:catalog=Sales")
        assert(wide.contains("'pipeline:*:catalog=Sales' and 'tap:*:catalog=Sales'"), wide)
        assert(wide.contains("grant 'protect:reveal:catalog=Sales' explicitly"), wide)
        // the explicit forms are accepted
        Seq("protect:reveal:catalog=Sales", "protect:reveal:owner=self").foreach(raw => assert(CapabilityScopes.issueProblem(raw).isEmpty, raw))
    }

    test("a stored scoped wildcard grants no reveal in either DATRIS_KEY_INERT_SCOPES mode") {
        def check(mode: String): Unit =
            ScopedWildcards.foreach { raw =>
                val rk = resolved("sales-key", raw)
                assert(!rk.grants("protect", "reveal", salesCtx), mode + ": " + raw)
                intercept[CapabilityDeniedException](FieldProtectionAPIController.requireCapability(req(rk), "reveal"))
                intercept[CapabilityDeniedException](FieldProtectionAPIController.targetRefusal(req(rk), "orders", orders, Some("reveal")))
                // as stored before inert scopes were split
                val raw0 = ScopeDenialFixtures.key("sales-key", raw)
                intercept[CapabilityDeniedException](FieldProtectionAPIController.requireCapability(req(raw0), "reveal"))
                assert(!raw0.grants("protect", "reveal", salesCtx), mode + " unsplit: " + raw)
            }
        check("refuse")
        sys.props.put(CapabilityScopes.InertScopesProperty, CapabilityScopes.InertScopesAllow)
        check("allow")
    }

    test("unscoped wildcards keep reveal everywhere; the explicit scoped form reveals inside its scope") {
        Seq("*:*", "protect:*", "*:reveal", "protect:reveal").foreach { raw =>
            val r = req(resolved("k", raw))
            FieldProtectionAPIController.requireCapability(r, "reveal")
            FieldProtectionAPIController.targetRefusal(r, "orders", orders, Some("reveal"))
        }
        val explicitKey = resolved("k", "protect:reveal:catalog=Sales")
        FieldProtectionAPIController.requireCapability(req(explicitKey), "reveal")
        FieldProtectionAPIController.targetRefusal(req(explicitKey), "orders", orders, Some("reveal"))
    }

    // ------------------------------------------------------ review round 1, findings 3 and 4

    test("an inert explicit reveal capability is refused at the gate before any config read, in both modes") {
        val saved = DatrisEnvironment.values
        ScopeDenialFixtures.install(ScopeDenialFixtures.auditEnv())
        try {
            def check(mode: String): Unit = {
                Seq("protect:reveal:catalog=Sales,collection=docs", "protect:reveal:database=x", "protect:reveal:collection=docs").foreach { raw =>
                    val rk = resolved("rk", raw)
                    val e = intercept[CapabilityDeniedException](FieldProtectionAPIController.requireCapability(req(rk), "reveal"))
                    val expected = ScopeDenialWording.message("rk", "protect", "reveal", rk.effectiveCapabilities.map(_.raw))
                    assert(e.getMessage == expected, mode + ": " + e.getMessage)
                    // the route answers 403 before the config is read (no Mongo is reachable here)
                    val res = new FieldProtectionAPIController().reveal(
                        "any",
                        RevealRequest("orders", "email", java.util.Arrays.asList("enc:v1:AAAA")),
                        req(rk)
                    )
                    assert(res.getStatusCode.value == 403, mode + " " + raw + ": " + res.getBody)
                    ScopeDenialWording.assertBody(res.getBody, expected, Nil)
                }
                // the same answer as a key with no reveal capability
                val none = resolved("rk", "pipeline:read")
                val res = new FieldProtectionAPIController().reveal(
                    "any",
                    RevealRequest("orders", "email", java.util.Arrays.asList("enc:v1:AAAA")),
                    req(none)
                )
                assert(res.getStatusCode.value == 403, res.getBody)
                ScopeDenialWording.assertBody(res.getBody, ScopeDenialWording.message("rk", "protect", "reveal", Nil), Nil)
                // an applied explicit scoped reveal still passes
                FieldProtectionAPIController.requireCapability(req(resolved("rk", "protect:reveal:catalog=Sales")), "reveal")
                FieldProtectionAPIController.requireCapability(req(resolved("rk", "protect:reveal:owner=self")), "reveal")
            }
            check("refuse")
            sys.props.put(CapabilityScopes.InertScopesProperty, CapabilityScopes.InertScopesAllow)
            check("allow")
        } finally ScopeDenialFixtures.install(saved)
    }

    test("Capability.grants: a scoped capability grants on protect only as explicit protect:reveal asked for reveal") {
        def g(raw: String, action: String, ctx: Map[String, String]): Boolean = Capability.parse(raw).grants("protect", action, ctx, "sales-key")
        val hrCtx = CatalogOps.scopeContext("HR", "hr-key")
        // the reviewer's probes
        assert(!g("protect:admin:catalog=Sales", "admin", Map("catalog" -> "Sales")))
        assert(!g("protect:*:catalog=Sales", "*", salesCtx))
        assert(!g("protect:*:catalog=Sales", "reveal", salesCtx))
        assert(!g("protect:admin:owner=self", "admin", salesCtx))
        // explicit scoped reveal
        assert(g("protect:reveal:catalog=Sales", "reveal", salesCtx))
        assert(!g("protect:reveal:catalog=Sales", "reveal", hrCtx))
        assert(g("protect:reveal:owner=self", "reveal", salesCtx))
        assert(!g("protect:reveal:owner=self", "reveal", hrCtx))
        assert(!g("protect:reveal:catalog=Sales", "admin", salesCtx))
        // unscoped capabilities: as before, any context
        for (ctx <- Seq(Map.empty[String, String], salesCtx, hrCtx)) {
            Seq("protect:reveal", "protect:*", "*:reveal", "*:*").foreach(raw => assert(g(raw, "reveal", ctx), raw + " " + ctx))
            Seq("protect:admin", "protect:*", "*:*").foreach(raw => assert(g(raw, "admin", ctx), raw + " " + ctx))
            assert(!g("protect:admin", "reveal", ctx))
            assert(!g("protect:reveal", "admin", ctx))
            assert(!g("*:reveal", "admin", ctx))
        }
        // any scoped protect capability against an empty context: false, as before
        Seq(
            "protect:reveal:catalog=Sales",
            "protect:reveal:owner=self",
            "protect:reveal:catalog=*",
            "protect:admin:catalog=Sales",
            "protect:*:catalog=Sales",
            "*:reveal:catalog=Sales",
            "*:*:catalog=Sales"
        ).foreach { raw =>
            Seq("reveal", "admin").foreach(a => assert(!g(raw, a, Map.empty), raw + " " + a))
        }
    }
}
