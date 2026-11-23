package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.api.KeyTemplatesForSpecs
import ai.datris.model.{Capability, ResolvedKey, User}
import ai.datris.util.APIKeyValidator
import ai.datris.util.InMemoryUserDb.grants
import org.scalatest.funsuite.AnyFunSuite

/** Story: Scoped API keys, `catalog=` / `owner=self` scope holds on every
  * route (plans/stories/scoped-key-read-routes.md), Part A, Acceptance
  * `CallerVisibilitySpec` (Design 1, Decision D1).
  *
  * One decision, pure on [[ResolvedKey]], says whether a caller sees only part
  * of the install. A restricted user's session always does. An API key does
  * when it is not full access, holds at least one `pipeline:*` / `tap:*`
  * capability carrying `catalog=` or `owner=`, and lacks unscoped read on the
  * resource asked about (D1). A key with no scoped pipeline or tap capability
  * (a `job:read`-only monitor key) is not limited, which is where this
  * differs from `PipelineAPIController.limitedVisibility` (write side, kept).
  *
  * Pinned seam (does not exist at 68c7316d; the implementation adds it):
  * {{{
  * // datrisserver/src/main/scala/ai/datris/auth/CallerVisibility.scala
  * object CallerVisibility {
  *   def limitedPipelines(rk: ResolvedKey): Boolean
  *   def limitedTaps(rk: ResolvedKey): Boolean
  *   def limited(rk: ResolvedKey): Boolean          // limitedPipelines || limitedTaps
  * }
  * object CapabilityCheck {
  *   // CallerVisibility.limited of the request's identity; false with none (auth off)
  *   def isLimited(request: HttpServletRequest): Boolean
  * }
  * }}}
  */
class CallerVisibilitySpec extends AnyFunSuite {

    private def key(label: String, caps: String*): ResolvedKey =
        ResolvedKey(None, label, Capability.parseList(caps), isLegacyFullAccess = false)

    private def session(user: User): ResolvedKey = APIKeyValidator.resolveFromSession(user)

    private def restricted(g: (String, String)*): ResolvedKey =
        session(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants(g: _*)))

    private def requestOf(rk: ResolvedKey) = ScopeDenialFixtures.request(rk)._1

    private def assertLimited(rk: ResolvedKey, pipelines: Boolean, taps: Boolean): Unit = {
        assert(CallerVisibility.limitedPipelines(rk) == pipelines, rk.label + " limitedPipelines")
        assert(CallerVisibility.limitedTaps(rk) == taps, rk.label + " limitedTaps")
        assert(CallerVisibility.limited(rk) == (pipelines || taps), rk.label + " limited")
        assert(CapabilityCheck.isLimited(requestOf(rk)) == (pipelines || taps), rk.label + " CapabilityCheck.isLimited")
    }

    test("a restricted session is limited") {
        // Whatever it is granted, a restricted user's session is always limited.
        assertLimited(restricted("Sales" -> "edit"), pipelines = true, taps = true)
        assertLimited(restricted("Sales" -> "view", "Ops" -> "own"), pipelines = true, taps = true)
        assertLimited(restricted(), pipelines = true, taps = true)
    }

    test("a key with pipeline:read:catalog=Sales is limited for pipelines and taps") {
        assertLimited(key("sales-reader", "pipeline:read:catalog=Sales"), pipelines = true, taps = true)
        // the story's `sales` and `own` write keys, and the Verify block's key
        assertLimited(
            key(
                "sales",
                "pipeline:read:catalog=Sales",
                "pipeline:create:catalog=Sales",
                "pipeline:update:catalog=Sales",
                "tap:read:catalog=Sales",
                "tap:create:catalog=Sales",
                "tap:update:catalog=Sales"
            ),
            pipelines = true,
            taps = true
        )
        assertLimited(
            key("own", "pipeline:read:owner=self", "pipeline:create:owner=self", "pipeline:update:owner=self", "tap:read:owner=self"),
            pipelines = true,
            taps = true
        )
        assertLimited(
            key(
                "sk_sales",
                "pipeline:read:catalog=SkSales",
                "pipeline:create:catalog=SkSales",
                "tap:read:catalog=SkSales",
                "tap:update:catalog=SkSales",
                "job:read",
                "job:kill",
                "metadata:read",
                "query:objectstore",
                "query:postgres",
                "search:vector",
                "document:upload"
            ),
            pipelines = true,
            taps = true
        )
    }

    test("a key with unscoped pipeline:read and tap:run:owner=self is not limited for reading") {
        // OPEN QUESTION (see report): D1 reads "lacks unscoped read on that
        // resource"; this key holds no tap:read at all, so whether
        // limitedTaps is true is left to the owner. Pipelines: not limited.
        val k = key("runner", "pipeline:read", "tap:run:owner=self")
        assert(!CallerVisibility.limitedPipelines(k), "unscoped pipeline:read: every pipeline is readable")
    }

    test("read-only, reporting, ops and rag-builder template keys are not limited") {
        Seq("read-only", "reporting", "ops", "rag-builder").foreach { t =>
            val k = key(t, KeyTemplatesForSpecs.capabilities(t): _*)
            assertLimited(k, pipelines = false, taps = false)
        }
        // rag-builder holds pipeline:run:owner=self and tap:run:owner=self, but
        // unscoped pipeline:read and tap:read: not limited.
        assert(KeyTemplatesForSpecs.capabilities("rag-builder").contains("pipeline:run:owner=self"))
    }

    test("a key with job:read alone is not limited (D1)") {
        assertLimited(key("monitor", "job:read"), pipelines = false, taps = false)
        // nor one with only install-wide capabilities and no pipeline or tap capability
        assertLimited(key("monitor2", "job:read", "metadata:read", "query:objectstore"), pipelines = false, taps = false)
        // today's write-side rule would call the job:read key limited; the read
        // decision does not (D1, recommended option).
    }

    test("legacy, `*:*` and no key are never limited") {
        val legacy = ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true)
        assertLimited(legacy, pipelines = false, taps = false)
        assertLimited(key("full", "*:*"), pipelines = false, taps = false)
        assertLimited(key("full-access", KeyTemplatesForSpecs.capabilities("full-access"): _*), pipelines = false, taps = false)
        // no identity on the request (auth off): never limited
        assert(!CapabilityCheck.isLimited(ScopeDenialFixtures.request(null)._1))
        assert(!CapabilityCheck.isLimited(null))
    }

    test("unrestricted sessions are never limited (Backward compat: unrestricted sessions unchanged)") {
        Seq(User.RoleAdmin, User.RoleEditor, User.RoleViewer).foreach { role =>
            assertLimited(session(User("u-" + role, "h", role, null, null, null)), pipelines = false, taps = false)
        }
        // a restricted flag on an admin never narrows it
        assertLimited(
            session(User("root", "h", User.RoleAdmin, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "view"))),
            pipelines = false,
            taps = false
        )
    }

    // ================================================================ held to its own tap's script file
    //
    // Story tap-script-store-writes-and-deletes-in-scope, rule S4: one
    // decision says which callers are held to their own tap's script file.

    test("held to its own tap's script file: a restricted session, a key limited for taps, a key whose tap update carries a scope") {
        val held = Seq(
            restricted("Sales" -> "edit"),
            restricted(),
            key("sales", "pipeline:read:catalog=Sales", "tap:read:catalog=Sales", "tap:create:catalog=Sales", "tap:update:catalog=Sales"),
            key("builder", "pipeline:read", "tap:read:owner=self", "tap:create", "tap:update:owner=self"),
            // unscoped tap and pipeline read, so not limited for taps, but its tap update carries a scope
            key("scoped-updater", "pipeline:read", "tap:read", "tap:create:catalog=Sales", "tap:update:catalog=Sales"),
            key("own-updater", "pipeline:read", "tap:read", "tap:update:owner=self")
        )
        for (rk <- held) assert(CallerVisibility.heldToOwnTapScriptFile(rk), rk.label)
    }

    test("not held to its own tap's script file: no identity, full access, unrestricted sessions, a key limited only for pipelines") {
        val legacy = ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true)
        val notHeld = Seq(legacy, key("full", "*:*")) ++
            Seq(User.RoleAdmin, User.RoleEditor, User.RoleViewer).map(role => session(User("u-" + role, "h", role, null, null, null))) ++
            Seq(
                // scoped on pipelines only; every tap capability unscoped
                key("pipe-limited", "pipeline:read:catalog=Sales", "tap:read", "tap:create", "tap:update", "tap:run"),
                // no tap update at all and not limited for taps
                key("pipe-reader", "pipeline:read:catalog=Sales", "tap:read"),
                key("monitor", "job:read")
            )
        for (rk <- notHeld) assert(!CallerVisibility.heldToOwnTapScriptFile(rk), rk.label)
        assert(!CallerVisibility.heldToOwnTapScriptFile(null))
    }
}
