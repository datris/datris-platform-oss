package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.{CallerVisibility, CapabilityScopes, ScopeDenialFixtures, TapRunTokens}
import ai.datris.incident.RecoveryKey
import ai.datris.model.{Capability, ResolvedKey, User}
import ai.datris.util.{APIKeyValidator, CatalogFind, UcHit, UnityCatalogResults}
import ai.datris.util.InMemoryUserDb.grants
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

/** Story: Scoped API keys, `catalog=` / `owner=self` scope holds on every
  * route (plans/stories/scoped-key-read-routes.md), Part C, lead ruling 7:
  * `find_data` stays in a scope-limited caller's tool list, but its Unity
  * Catalog branch (a metastore-wide search over every Databricks secret the
  * caller can read) is skipped for it, with the answer a caller with no
  * readable Databricks secret gets. Everyone else is unchanged. */
class CatalogFindUnityCatalogScopeSpec extends AnyFunSuite with BeforeAndAfterEach {

    private var savedProp: Option[String] = None

    override def beforeEach(): Unit = savedProp = sys.props.get(CapabilityScopes.InertScopesProperty)

    override def afterEach(): Unit = savedProp match {
        case Some(v) => sys.props(CapabilityScopes.InertScopesProperty) = v
        case None => sys.props -= CapabilityScopes.InertScopesProperty
    }

    private def bothModes(body: String => Unit): Unit = {
        sys.props -= CapabilityScopes.InertScopesProperty
        if (sys.env.get(CapabilityScopes.InertScopesEnv).isEmpty) body("refuse")
        sys.props(CapabilityScopes.InertScopesProperty) = "allow"
        body("allow")
    }

    private def stored(label: String, raw: String*): ResolvedKey = {
        val (kept, inert) = CapabilityScopes.partition(Capability.parseList(raw))
        ResolvedKey(None, label, kept, isLegacyFullAccess = false, inertCapabilities = inert)
    }

    private def req(rk: ResolvedKey) = ScopeDenialFixtures.request(rk, "GET", "/api/v1/catalog/find")._1

    private val found = UnityCatalogResults(
        List("dbx_prod"),
        List("dbx_cold" -> "timed out"),
        List(UcHit("dbx_prod", "main", "hr", "payroll", "salaries", List("salary"), Map.empty))
    )

    private val Empty = UnityCatalogResults(Nil, Nil, Nil)

    private def limitedKeys: Seq[ResolvedKey] = Seq(
        stored("cat-sales", "pipeline:read:catalog=Sales", "metadata:read"),
        stored("own-self", "pipeline:read:owner=self", "tap:read:owner=self", "metadata:read"),
        stored("taps-only", "pipeline:read", "tap:run:owner=self", "metadata:read"),
        stored("pipelines-only", "tap:read", "pipeline:read:catalog=Sales", "metadata:read"),
        stored("cat-inert", "pipeline:read:catalog=Sales,collection=docs", "metadata:read"),
        stored("wide-sales", "*:*:catalog=Sales"),
        APIKeyValidator.resolveFromSession(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "view")))
    )

    private def unchanged: Seq[ResolvedKey] = Seq(
        APIKeyValidator.resolveFromSession(User("alice", "h", User.RoleAdmin, null, null, null)),
        APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null)),
        APIKeyValidator.resolveFromSession(User("vic", "h", User.RoleViewer, null, null, null)),
        ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true),
        stored("star", "*:*"),
        stored("monitor", "job:read", "metadata:read"),
        stored("inert-only", "search:vector:collection=docs", "metadata:read"),
        stored(RecoveryKey.Label, RecoveryKey.Capabilities: _*)
    ) ++ KeyTemplatesForSpecs.names.map(n => stored("tpl-" + n, KeyTemplatesForSpecs.capabilities(n): _*))

    test("a scope-limited caller asking for Unity Catalog gets the nothing-searched answer and the search is never run") {
        bothModes { m =>
            for (rk <- limitedKeys) {
                assert(CallerVisibility.limited(rk), s"$m: ${rk.label}")
                var ran = 0
                val out = CatalogFindAPIController.unityCatalogSearch(req(rk), requested = true, () => { ran += 1; found })
                assert(out.contains(Empty), s"$m: ${rk.label} -> $out")
                assert(ran == 0, s"$m: ${rk.label}: the metastore search must not run")
            }
        }
    }

    test("the skipped answer renders exactly as a caller with no readable Databricks secret (no error, nothing that tells them apart)") {
        val skipped =
            CatalogFind.find("payroll", 10, ai = false, Nil, Nil, CatalogFindAPIController.unityCatalogSearch(req(limitedKeys.head), true, () => found))
        val noSecrets = CatalogFind.find("payroll", 10, ai = false, Nil, Nil, Some(UnityCatalogResults(Nil, Nil, Nil)))
        assert(skipped == noSecrets, s"$skipped vs $noSecrets")
        assert(!skipped.toString.contains("dbx_prod") && !skipped.toString.contains("salaries"), skipped.toString)
        assert(!skipped.has("error"), skipped.toString)
    }

    test("not requested is None for everyone, and the search is never run") {
        for (rk <- limitedKeys ++ unchanged :+ null) {
            var ran = 0
            assert(CatalogFindAPIController.unityCatalogSearch(req(rk), requested = false, () => { ran += 1; found }).isEmpty)
            assert(ran == 0)
        }
        assert(CatalogFindAPIController.unityCatalogSearch(null, requested = false, () => found).isEmpty)
    }

    test("unchanged callers, a tap-run token, no identity and a null request get today's search result, run once") {
        val token = TapRunTokens.issue("t_sales_feed", None, 60)
        try {
            val callers = unchanged :+ TapRunTokens.resolve(token).getOrElse(fail("token did not resolve"))
            bothModes { m =>
                for (rk <- callers) {
                    assert(!CallerVisibility.limited(rk), s"$m: ${rk.label}")
                    var ran = 0
                    val out = CatalogFindAPIController.unityCatalogSearch(req(rk), requested = true, () => { ran += 1; found })
                    assert(out.exists(_ eq found), s"$m: ${rk.label} -> $out")
                    assert(ran == 1, s"$m: ${rk.label}")
                }
            }
        } finally TapRunTokens.revoke(token)
        assert(CatalogFindAPIController.unityCatalogSearch(req(null), requested = true, () => found).exists(_ eq found), "no identity (auth off)")
        assert(CatalogFindAPIController.unityCatalogSearch(null, requested = true, () => found).exists(_ eq found), "null request")
    }
}
