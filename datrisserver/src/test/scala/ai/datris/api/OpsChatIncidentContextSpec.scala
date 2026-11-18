package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.{CapabilityRoutes, ChatIdentity, MCPToolRoutes, RouteCheck}
import ai.datris.incident.{Incident, IncidentStep}
import ai.datris.model.{Capability, ResolvedKey, User}
import ai.datris.util.{APIKeyValidator, OpsAgentPrompt}
import com.google.gson.JsonObject
import org.scalatest.funsuite.AnyFunSuite

import java.time.Instant

/** Story: Catalog-scoped user access: the Operations chat never tells a
  * restricted user about open incidents
  * (plans/stories/catalog-scoped-user-access-ops-chat-incidents.md).
  *
  * Story: Chat endpoints act with the caller's own authority
  * (plans/stories/chat-endpoints-caller-identity.md): the decision now takes
  * the request's `ChatIdentity` and uses `ChatCaller.mayReadIncidents`, so an
  * API key without unscoped `incident:read` gets none either.
  *
  * `OpsChatAPIController.incidentsFor(identity, load)` decides which open
  * incidents go into the Ops chat context: a restricted session gets `Nil`
  * and the incident store is not read; everyone else gets `load()` as the
  * same instance. The tool-closure tests at the end are pins: they pass
  * before the change and fail the day a restricted bundle gains
  * `incident:read` (the moment the per-catalog incident view must be decided).
  */
class OpsChatIncidentContextSpec extends AnyFunSuite {

    private def user(role: String, restricted: Boolean, grants: (String, String)*): User = {
        val m = new java.util.LinkedHashMap[String, String]()
        grants.foreach { case (k, v) => m.put(k, v) }
        User(
            username = "bob",
            passwordHash = "x",
            role = role,
            createdAt = "2026-11-01T00:00:00Z",
            updatedAt = "2026-11-01T00:00:00Z",
            lastLoginAt = null,
            restricted = restricted,
            catalogGrants = if (grants.isEmpty) null else m
        )
    }

    private val bob: User = user(User.RoleEditor, restricted = true, "Sales" -> "own")

    /** A session caller as ChatCaller.decide leaves it: the user, its session bundle, the ui key. */
    private def sess(u: User): ChatIdentity =
        ChatIdentity(Some(u), Some(APIKeyValidator.resolveFromSession(u)), ownKey = false)

    private val legacyKey: ResolvedKey =
        ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true)

    /** No session user: a full-access API key, or user auth off. */
    private val noSession: ChatIdentity = ChatIdentity(None, Some(legacyKey), ownKey = false)

    private def keyIdentity(label: String, caps: String*): ChatIdentity =
        ChatIdentity(None, Some(ResolvedKey(None, label, Capability.parseList(caps), isLegacyFullAccess = false)), ownKey = true)

    private val t0: Instant = Instant.parse("2026-11-18T10:00:00Z")

    private val incHr: Incident = Incident(
        id = "inc-hr",
        kind = "pipeline_failure",
        resourceType = "pipeline",
        resourceName = "hr_payroll",
        openedAt = t0,
        state = "diagnosing",
        trigger = new JsonObject(),
        steps = List(
            IncidentStep(t0, "open", "opened"),
            IncidentStep(t0.plusSeconds(60), "verify", "re-ran after upstream tap hr_feed recovered")
        )
    )

    private val incSales: Incident = Incident(
        id = "inc-sales",
        kind = "tap_failure",
        resourceType = "tap",
        resourceName = "t_sales",
        openedAt = t0,
        state = "diagnosing",
        trigger = new JsonObject(),
        steps = List(IncidentStep(t0, "open", "tap t_sales failed"))
    )

    private val failingLoad: () => List[Incident] =
        () => fail("the incident store must not be read for a restricted session")

    private def ctx: JsonObject = {
        val c = new JsonObject()
        c.addProperty("window", "24h")
        c
    }

    test("a restricted user gets no open incidents and the incident store is not read") {
        assert(OpsChatAPIController.incidentsFor(sess(bob), failingLoad) == Nil)
    }

    test("a restricted user gets none even for an incident on a tap or pipeline in a catalog they own") {
        var reads = 0
        val load = () => { reads += 1; List(incSales) }
        assert(OpsChatAPIController.incidentsFor(sess(bob), load) == Nil)
        assert(reads == 0, "the incident store must not be read for a restricted session")
    }

    test("a restricted user with no grants gets none") {
        val noGrants = user(User.RoleViewer, restricted = true)
        assert(noGrants.isRestricted)
        assert(OpsChatAPIController.incidentsFor(sess(noGrants), failingLoad) == Nil)
    }

    test("an unrestricted admin, editor and viewer get the loaded list as the same instance") {
        Seq(User.RoleAdmin, User.RoleEditor, User.RoleViewer).foreach { role =>
            val loaded = List(incHr, incSales)
            val got = OpsChatAPIController.incidentsFor(sess(user(role, restricted = false)), () => loaded)
            assert(got eq loaded, s"$role must get the loaded list itself")
        }
    }

    test("a caller with no session user (API key, user auth off) gets the loaded list as the same instance") {
        val loaded = List(incHr, incSales)
        assert(OpsChatAPIController.incidentsFor(noSession, () => loaded) eq loaded)
    }

    test("an admin flagged restricted is not restricted and gets the loaded list") {
        val admin = user(User.RoleAdmin, restricted = true, "Sales" -> "own")
        assert(!admin.isRestricted)
        val loaded = List(incHr)
        assert(OpsChatAPIController.incidentsFor(sess(admin), () => loaded) eq loaded)
    }

    test("the context message for a restricted user equals the message with no incidents") {
        val msg = OpsAgentPrompt.renderContextMessage(
            ctx,
            OpsChatAPIController.incidentsFor(sess(bob), () => List(incHr, incSales))
        )
        assert(msg == OpsAgentPrompt.renderContextMessage(ctx))
        Seq("Open incidents", "inc-hr", "inc-sales", "hr_payroll", "hr_feed", "t_sales").foreach { s =>
            assert(!msg.contains(s), s"restricted context must not contain '$s'")
        }
    }

    test("the context message for an unrestricted user is unchanged") {
        val ed = user(User.RoleEditor, restricted = false)
        val msg = OpsAgentPrompt.renderContextMessage(
            ctx,
            OpsChatAPIController.incidentsFor(sess(ed), () => List(incHr, incSales))
        )
        val lines = msg.split("\n").toSeq
        assert(lines.exists(_.startsWith("Open incidents (")), msg)
        assert(
            lines.contains(
                "  - inc-hr: pipeline_failure on pipeline `hr_payroll`, state=diagnosing — re-ran after upstream tap hr_feed recovered"
            ),
            msg
        )
        assert(msg == OpsAgentPrompt.renderContextMessage(ctx, List(incHr, incSales)))
    }

    test("a restricted session bundle holds no incident, approval or audit read") {
        Seq(User.RoleEditor, User.RoleViewer).foreach { role =>
            val rk = APIKeyValidator.resolveFromSession(user(role, restricted = true, "Sales" -> "own"))
            assert(rk.restrictedSession, s"$role restricted session")
            Seq("incident" -> "read", "approval" -> "read", "audit" -> "read").foreach { case (r, a) =>
                assert(!rk.matchesResourceAction(r, a), s"restricted $role must not hold $r:$a")
            }
        }
    }

    test("the incident tools are not offered to a restricted session") {
        val seen = MCPToolRoutes.allowedTools(APIKeyValidator.resolveFromSession(bob))
        Seq("list_incidents", "get_incident", "get_incident_evidence").foreach { t =>
            assert(!seen.contains(t), s"$t must not be offered to a restricted session")
        }
        assert(seen.contains("list_taps"))
    }

    test("the incident read routes require incident:read") {
        Seq("/api/v1/incidents", "/api/v1/incidents/x", "/api/v1/incidents/x/evidence").foreach { p =>
            assert(CapabilityRoutes.lookup("GET", p) == RouteCheck.Require("incident", "read"), p)
        }
    }

    test("contextMessage for a restricted user equals the no-incidents message and the incident store is not read") {
        Seq(bob, user(User.RoleViewer, restricted = true)).foreach { u =>
            val msg = OpsChatAPIController.contextMessage(sess(u), ctx, failingLoad)
            assert(msg == OpsAgentPrompt.renderContextMessage(ctx))
            Seq("Open incidents", "inc-hr", "hr_payroll", "hr_feed").foreach { s =>
                assert(!msg.contains(s), s"restricted context must not contain '$s'")
            }
        }
    }

    test("contextMessage for an unrestricted user or no session user is the message with the loaded incidents") {
        val expected = OpsAgentPrompt.renderContextMessage(ctx, List(incHr, incSales))
        val callers: Seq[ChatIdentity] =
            Seq(noSession) ++ Seq(User.RoleAdmin, User.RoleEditor, User.RoleViewer).map(r => sess(user(r, restricted = false))) ++
                Seq(sess(user(User.RoleAdmin, restricted = true, "Sales" -> "own")))
        callers.foreach { u =>
            var reads = 0
            val msg = OpsChatAPIController.contextMessage(u, ctx, () => { reads += 1; List(incHr, incSales) })
            assert(msg == expected, s"caller $u")
            assert(reads == 1, s"caller $u must read the incident store once")
            assert(msg.contains("inc-hr: pipeline_failure on pipeline `hr_payroll`"), msg)
        }
    }

    private val keyFailingLoad: () => List[Incident] =
        () => fail("the incident store must not be read for a key without incident:read")

    test("a key without incident:read gets no incidents and the store is not read") {
        Seq(
            keyIdentity("ops-ro", "pipeline:read", "tap:read"),
            keyIdentity("ci_scoped", "pipeline:read:catalog=Sales", "tap:read:catalog=Sales"),
            keyIdentity("ops-inc-sales", "incident:read:catalog=Sales", "pipeline:read"),
            keyIdentity("scoped-star", "*:*:catalog=Sales")
        ).foreach { id =>
            assert(OpsChatAPIController.incidentsFor(id, keyFailingLoad) == Nil, s"$id")
            val msg = OpsChatAPIController.contextMessage(id, ctx, keyFailingLoad)
            assert(msg == OpsAgentPrompt.renderContextMessage(ctx), s"$id")
            Seq("Open incidents", "inc-hr", "hr_payroll").foreach(t => assert(!msg.contains(t), s"$id must not see '$t'"))
        }
    }

    test("a key holding incident:read gets the loaded list as the same instance") {
        val id = keyIdentity("ops-inc", "incident:read", "pipeline:read")
        val loaded = List(incHr, incSales)
        assert(OpsChatAPIController.incidentsFor(id, () => loaded) eq loaded)
        var reads = 0
        val msg = OpsChatAPIController.contextMessage(id, ctx, () => { reads += 1; loaded })
        assert(reads == 1)
        assert(msg == OpsAgentPrompt.renderContextMessage(ctx, loaded))
    }
}
