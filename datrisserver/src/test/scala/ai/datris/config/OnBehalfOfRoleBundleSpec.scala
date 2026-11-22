package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditActor
import ai.datris.auth.{CapabilityRoutes, MCPToolRoutes, RoleToolSets, RouteCheck, TapRunTokens}
import ai.datris.config.ChatAuthorityFixtures._
import ai.datris.model.{Capability, DatrisEnvironment, ResolvedKey, TenantContext, User, UserContext}
import ai.datris.policy.PolicyReplay
import ai.datris.util.{APIKeyValidator, InMemoryUserDb, UserStore}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.atomic.AtomicReference

/** Story: In-platform chats act with the signed-in user's role, not the
  * platform key's (plans/stories/chat-session-role-authority.md), Acceptance
  * (`OnBehalfOfRoleBundleSpec`), Steps 2, 3 and 6, Decisions 2 and 5.
  *
  * The pure seam is the new companion
  * `TenantInterceptor.onBehalfOfIdentity(carrier: ResolvedKey, user: User, approvalReplay: Boolean): ResolvedKey`:
  * an admin (or an approval replay for a user who is not restricted) is
  * relabelled `session:<user>` and keeps the carrier's bundle; anyone else
  * is narrowed to `APIKeyValidator.resolveFromSession(user)` exactly as a
  * restricted user is today, with `restrictedSession = bundle.restrictedSession`.
  *
  * Carriers: the `ui` key (legacy full access, and with `*:*` metadata) and
  * the keys-off anonymous identity. The second half drives the real
  * `TenantInterceptor.preHandle` (keys off; keys on through the key
  * resolution cache, see ChatAuthorityFixtures) and the real
  * `CapabilityInterceptor.preHandle` on the routes the chat's tools call. */
class OnBehalfOfRoleBundleSpec extends AnyFunSuite with BeforeAndAfterEach {

    private var saved: DatrisEnvironment = _
    private var db: InMemoryUserDb = _

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        DatrisEnvironment.values = InMemoryUserDb.env() // keys off, user auth on
        db = new InMemoryUserDb
        UserStore.dbOverride = db
        allUsers.foreach(db.put)
        APIKeyValidator.invalidateCache()
        UserContext.clear()
        TenantContext.clear()
    }

    override def afterEach(): Unit = {
        UserStore.dbOverride = null
        UserContext.clear()
        TenantContext.clear()
        APIKeyValidator.invalidateCache()
        DatrisEnvironment.values = saved
    }

    private def obo(carrier: ResolvedKey, user: User, replay: Boolean = false): ResolvedKey =
        TenantInterceptor.onBehalfOfIdentity(carrier, user, replay)

    private val carriers: Seq[ResolvedKey] = Seq(uiLegacy, anonymous)

    private def raws(rk: ResolvedKey): Seq[String] = rk.capabilities.map(_.raw)

    // ================================================================ Acceptance

    test("an admin keeps the carrier's bundle and is relabelled session:<user>") {
        for (carrier <- carriers :+ uiStored; admin <- Seq(root, rootFlagged)) {
            val rk = obo(carrier, admin)
            assert(rk == carrier.copy(label = "session:" + admin.username), s"${carrier.label} for ${admin.username}: $rk")
            assert(rk.isFullAccess)
            assert(!rk.restrictedSession)
            assert(MCPToolRoutes.allowedTools(rk) == MCPToolRoutes.allToolNames)
        }
    }

    test("an unrestricted editor gets exactly roleToCapabilities(editor), not full access, not a restricted session") {
        for (carrier <- carriers :+ uiStored) {
            val rk = obo(carrier, ed)
            assert(rk.label == "session:ed", carrier.label)
            assert(raws(rk) == RoleToolSets.bundle(User.RoleEditor), carrier.label)
            assert(!rk.isLegacyFullAccess, carrier.label)
            assert(!rk.isFullAccess, carrier.label)
            assert(!rk.restrictedSession, carrier.label)
            assert(rk.inertCapabilities.isEmpty, carrier.label)
            assert(!rk.capabilities.contains(Capability.FullAccess), carrier.label)
        }
    }

    test("an unrestricted viewer gets exactly roleToCapabilities(viewer)") {
        for (carrier <- carriers :+ uiStored) {
            val rk = obo(carrier, vic)
            assert(rk.label == "session:vic", carrier.label)
            assert(raws(rk) == RoleToolSets.bundle(User.RoleViewer), carrier.label)
            assert(!rk.isLegacyFullAccess && !rk.isFullAccess && !rk.restrictedSession, carrier.label)
        }
    }

    test("an unknown role gets no capabilities") {
        // A role is matched exactly: an upper-case or padded "admin" is not an admin.
        val unknown = Seq(odd, odd.copy(username = "up", role = "ADMIN"), odd.copy(username = "pad", role = " admin"), odd.copy(username = "nul", role = null))
        for (carrier <- carriers; u <- unknown) {
            val rk = obo(carrier, u)
            assert(rk.capabilities.isEmpty, s"${u.role}: $rk")
            assert(!rk.isLegacyFullAccess && !rk.isFullAccess, String.valueOf(u.role))
            assert(rk.label == "session:" + u.username)
            assert(MCPToolRoutes.allowedTools(rk) == Seq("get_version", "check_service_health", "wait_seconds"), String.valueOf(u.role))
        }
    }

    test("a restricted editor is narrowed as before") {
        for (carrier <- carriers) {
            val rk = obo(carrier, bob)
            val expected = APIKeyValidator.resolveFromSession(bob)
            assert(rk.restrictedSession, carrier.label)
            assert(rk.label == "session:bob")
            assert(rk.capabilities == expected.capabilities, carrier.label)
            assert(!rk.isLegacyFullAccess)
            assert(rk.inertCapabilities.isEmpty)
            assert(MCPToolRoutes.allowedTools(rk) == RoleToolSets.RestrictedEditorSales, carrier.label)
        }
    }

    // plans/stories/approval-replay-requester-scope.md: the approval-replay
    // flag no longer widens the approver's identity; a verified replay runs
    // with the requester's access instead (ReplayRequesterIdentitySpec).
    test("the approval-replay flag widens nothing: an editor gets the editor bundle, an admin the carrier's, a restricted editor stays narrowed") {
        for (carrier <- carriers) {
            assert(obo(carrier, ed, replay = true) == obo(carrier, ed), carrier.label)
            assert(!obo(carrier, ed, replay = true).isFullAccess, carrier.label)
            assert(obo(carrier, root, replay = true) == carrier.copy(label = "session:root"), carrier.label)
            val narrowed = obo(carrier, bob, replay = true)
            assert(narrowed == obo(carrier, bob), carrier.label)
            assert(narrowed.restrictedSession && !narrowed.isLegacyFullAccess, carrier.label)
        }
    }

    test("an editor on behalf-of lists 76 tools without the 14") {
        for (carrier <- carriers :+ uiStored) {
            val tools = MCPToolRoutes.allowedTools(obo(carrier, ed))
            assert(tools == RoleToolSets.Editor, carrier.label)
            assert(tools.size == 76, carrier.label)
            assert(tools == MCPToolRoutes.allToolNames.filterNot(RoleToolSets.EditorLoses.contains), carrier.label)
            assert(RoleToolSets.EditorLoses.size == 14)
            Seq("delete_pipeline", "delete_catalog", "delete_tap", "kill_job").foreach(t => assert(tools.contains(t), t))
            Seq("update_secret", "list_incidents", "upload_config", "get_agent_policy").foreach(t => assert(!tools.contains(t), t))
        }
        assert(MCPToolRoutes.allToolNames.size == 90)
    }

    test("a viewer lists 56 and no mutating tool") {
        for (carrier <- carriers :+ uiStored) {
            val tools = MCPToolRoutes.allowedTools(obo(carrier, vic))
            assert(tools == RoleToolSets.Viewer, carrier.label)
            assert(tools.size == 56, carrier.label)
            val writes = tools.filter(RoleToolSets.mutating)
            assert(writes.isEmpty, s"${carrier.label}: viewer sees $writes")
        }
    }

    test("the narrowed editor satisfies no admin role gate") {
        for (carrier <- carriers :+ uiStored; u <- Seq(ed, vic, bob, rita)) {
            val rk = obo(carrier, u)
            assert(!RoleEnforcementInterceptor.programmaticKeySatisfiesRoleGate(rk, "PUT", "/api/v1/secrets/x"), u.username)
            assert(!RoleEnforcementInterceptor.programmaticKeySatisfiesRoleGate(rk, "POST", "/api/v1/keys"), u.username)
            Seq("POST" -> "/api/v1/config-chat/chat", "GET" -> "/api/v1/audit-log", "PUT" -> "/api/v1/policy", "POST" -> "/api/v1/auth/users")
                .foreach { case (m, p) => assert(!RoleEnforcementInterceptor.programmaticKeySatisfiesRoleGate(rk, m, p), s"${u.username} $m $p") }
        }
    }

    // ================================================================ the tool sets

    test("the mutating classifier reads the route's method and capability, not the tool's name") {
        Seq(
            "create_pipeline",
            "delete_pipeline",
            "run_tap",
            "test_tap",
            "upload_data",
            "upload_config",
            "kill_job",
            "update_secret",
            "restore_tap_version",
            "set_catalog",
            "apply_dest_types",
            "enable_catalog_ordered",
            "set_tap_state"
        )
            .foreach(t => assert(RoleToolSets.mutating(t), t))
        Seq(
            "list_pipelines",
            "query_postgres",
            "search_qdrant",
            "profile_data",
            "suggest_field_protection",
            "ai_answer",
            "wait_seconds",
            "get_version",
            "find_data"
        )
            .foreach(t => assert(!RoleToolSets.mutating(t), t))
        // every PUT / DELETE tool is mutating, and every GET tool is not
        MCPToolRoutes.tools.foreach {
            case (t, MCPToolRoutes.Mapped(m, _)) if m == "PUT" || m == "DELETE" => assert(RoleToolSets.mutating(t), t)
            case (t, MCPToolRoutes.Mapped("GET", _)) => assert(!RoleToolSets.mutating(t), t)
            case _ =>
        }
    }

    test("a viewer loses exactly the editor's 14 plus 20 writes, all mutating; the viewer bundle grants no write") {
        val lost = RoleToolSets.Editor.filterNot(RoleToolSets.Viewer.contains)
        assert(lost.size == 20, lost)
        assert(lost.forall(RoleToolSets.mutating), lost.filterNot(RoleToolSets.mutating))
        assert(RoleToolSets.Viewer.forall(RoleToolSets.Editor.contains))
        // by capability: what each of the 20 needs
        val needs = lost.map { t =>
            val MCPToolRoutes.Mapped(m, p) = MCPToolRoutes.tools.toMap.apply(t)
            CapabilityRoutes.lookup(m, p) match {
                case RouteCheck.Require(r, a) => r + ":" + a
                case other => fail(s"$t -> $other")
            }
        }
        assert(needs.groupBy(identity).map { case (k, v) => k -> v.size } == Map(
            "document:upload" -> 1,
            "job:kill" -> 1,
            "pipeline:create" -> 3,
            "pipeline:update" -> 4,
            "pipeline:delete" -> 2,
            "tap:create" -> 2,
            "tap:update" -> 2,
            "tap:delete" -> 1,
            "tap:run" -> 4
        ))
        RoleToolSets.bundle(User.RoleViewer).map(Capability.parse).foreach { c =>
            assert(c.action == "read" || c.resource == "query" || c.resource == "search", c.raw)
        }
    }

    test("a restricted viewer on behalf-of keeps its grants-only read set") {
        for (carrier <- carriers) {
            val rk = obo(carrier, rita)
            assert(rk.restrictedSession && rk.capabilities == APIKeyValidator.resolveFromSession(rita).capabilities, carrier.label)
            val tools = MCPToolRoutes.allowedTools(rk)
            assert(tools == RoleToolSets.RestrictedViewerSales, carrier.label)
            assert(tools.filter(RoleToolSets.mutating).isEmpty)
        }
    }

    test("the narrowed identity never holds what its carrier does not grant, for every role, carrier shape and replay flag") {
        val tap = ResolvedKey(None, "tap:t1", TapRunTokens.Capabilities, isLegacyFullAccess = false)
        val limited = ResolvedKey(None, "ops-ro", Capability.parseList(Seq("pipeline:read", "tap:read", "job:read")), isLegacyFullAccess = false)
        val scopedStar = RoleToolSets.stored("sales_all", "*:*:catalog=Sales")
        for (carrier <- Seq(uiLegacy, uiStored, anonymous, tap, limited, scopedStar); u <- allUsers; replay <- Seq(false, true)) {
            val rk = obo(carrier, u, replay)
            val m = s"${carrier.label} for ${u.username} replay=$replay"
            rk.effectiveCapabilities.foreach(c => assert(carrier.grants(c.resource, c.action, c.scope), s"$m: ${c.raw} is not the carrier's"))
            if (rk.isLegacyFullAccess) assert(carrier.isLegacyFullAccess, m)
            if (rk.isFullAccess) assert(carrier.isFullAccess, m)
            assert(MCPToolRoutes.allowedTools(rk).forall(MCPToolRoutes.allowedTools(carrier).contains), m)
            if (u.isRestricted) assert(rk.restrictedSession, m)
            assert(rk.label == "session:" + u.username, m)
        }
    }

    test("onBehalfOfIdentity reads no thread-local: the same inputs on another thread with another UserContext give the same identity") {
        val here = obo(uiLegacy, ed)
        val there = new AtomicReference[ResolvedKey]()
        val t = new Thread(() => {
            UserContext.set(root)
            try there.set(obo(uiLegacy, ed))
            finally UserContext.clear()
        })
        t.start()
        t.join()
        assert(there.get == here)
        UserContext.set(root)
        assert(obo(uiLegacy, ed) == here)
    }

    // ================================================================ through TenantInterceptor.preHandle

    /** preHandle with the given headers; the attached identity and the attribute map. */
    private def through(headers: (String, String)*) = {
        val (r, attrs) = request("GET", "/api/v1/mcp/tools", headers: _*)
        assert(new TenantInterceptor().preHandle(r, new Captured().response, null))
        (resolved(attrs), attrs, r)
    }

    test("keys off: the anonymous carrier on behalf of each role, through preHandle") {
        val expect = Map(
            "root" -> anonymous.copy(label = "session:root"),
            "ed" -> obo(anonymous, ed),
            "vic" -> obo(anonymous, vic),
            "bob" -> obo(anonymous, bob),
            "rita" -> obo(anonymous, rita)
        )
        for ((name, rk) <- expect) {
            val (got, attrs, _) = through(AuditActor.HeaderOnBehalfOf -> name)
            assert(got.contains(rk), s"$name: $got")
            assert(attrs.get(AuditActor.OnBehalfOfAttr).collect { case u: User => u.username }.contains(name))
            assert(attrs.get(AuditActor.CarrierKeyLabelAttr).contains("anonymous"))
        }
        assert(through(AuditActor.HeaderOnBehalfOf -> "ed")._1.map(MCPToolRoutes.allowedTools).contains(RoleToolSets.Editor))
        assert(through(AuditActor.HeaderOnBehalfOf -> "vic")._1.map(MCPToolRoutes.allowedTools).contains(RoleToolSets.Viewer))
    }

    test("a different case or padded name reaches that same user and never another") {
        for (v <- Seq("ED", "Ed", " ed", "ed ", "\ted\t")) {
            val (got, attrs, _) = through(AuditActor.HeaderOnBehalfOf -> v)
            assert(got.contains(obo(anonymous, ed)), s"'$v': $got")
            assert(attrs.get(AuditActor.OnBehalfOfAttr).collect { case u: User => u.username }.contains("ed"), v)
        }
        for (v <- Seq("VIC", " vic ")) assert(through(AuditActor.HeaderOnBehalfOf -> v)._1.contains(obo(anonymous, vic)), v)
    }

    // Lead ruling (owner Decision 7): was one test, "an empty, blank or
    // malformed header vouches for nobody and changes nothing"; split because
    // a header naming no real user is now refused.
    test("an empty or blank header vouches for nobody and changes nothing") {
        for (v <- Seq("", "   ")) {
            val (got, attrs, _) = through(AuditActor.HeaderOnBehalfOf -> v)
            assert(got.contains(anonymous), s"'$v': $got")
            assert(!attrs.contains(AuditActor.OnBehalfOfAttr), v)
        }
    }

    test("a malformed header naming no real user is refused (401) and never acts with the carrier's identity") {
        for (v <- Seq("ed,root", "ed root", "ed;root", "root;ed", "*", "%72oot", "session:root", "x" * 300)) {
            val (r, attrs) = request("GET", "/api/v1/mcp/tools", AuditActor.HeaderOnBehalfOf -> v)
            val c = new Captured
            assert(!new TenantInterceptor().preHandle(r, c.response, null), v.take(20))
            assert(c.status.contains(401), v.take(20))
            assert(c.body == TenantInterceptor.UnverifiedUserBody, v.take(20))
            assert(!attrs.contains(TenantInterceptor.ResolvedKeyAttr), v.take(20))
            assert(!attrs.contains(AuditActor.OnBehalfOfAttr), v.take(20))
        }
    }

    test("the header sent twice is read once: the first value decides, and naming an admin second gains nothing") {
        val (got, _, _) = through(AuditActor.HeaderOnBehalfOf -> "ed", AuditActor.HeaderOnBehalfOf -> "root")
        assert(got.contains(obo(anonymous, ed)), String.valueOf(got))
        val (got2, _, _) = through(AuditActor.HeaderOnBehalfOf -> "vic", AuditActor.HeaderOnBehalfOf -> "ed")
        assert(got2.contains(obo(anonymous, vic)), String.valueOf(got2))
    }

    test("a second request on the same thread from a different user gets its own identity, and a request without the header gets none of it") {
        assert(through(AuditActor.HeaderOnBehalfOf -> "root")._1.contains(anonymous.copy(label = "session:root")))
        assert(through(AuditActor.HeaderOnBehalfOf -> "ed")._1.contains(obo(anonymous, ed)))
        assert(through(AuditActor.HeaderOnBehalfOf -> "vic")._1.contains(obo(anonymous, vic)))
        assert(through()._1.contains(anonymous))
        // a leftover UserContext on the thread (an earlier SSE request) is not read
        UserContext.set(root)
        assert(through(AuditActor.HeaderOnBehalfOf -> "ed")._1.contains(obo(anonymous, ed)))
        UserContext.set(vic)
        assert(through(AuditActor.HeaderOnBehalfOf -> "root")._1.contains(anonymous.copy(label = "session:root")))
    }

    test("keys on: the ui key on behalf of an editor or viewer is narrowed; on behalf of an admin it is relabelled") {
        DatrisEnvironment.values = InMemoryUserDb.env().copy(useApiKeys = true)
        for (ui <- Seq(uiLegacy, uiStored)) {
            for (u <- Seq(root, ed, vic, bob, rita)) {
                seedKey(UiValue, ui)
                val (got, attrs, _) = through("x-api-key" -> UiValue, AuditActor.HeaderOnBehalfOf -> u.username)
                assert(got.contains(obo(ui, u)), s"${ui.capabilities} ${u.username}: $got")
                assert(attrs.get(AuditActor.CarrierKeyLabelAttr).contains("ui"))
            }
            seedKey(UiValue, ui)
            assert(through("x-api-key" -> UiValue)._1.contains(ui), "no header: the ui key itself")
        }
    }

    test("keys on: a key that is not the ui key carrying the header keeps its own identity and gains nothing") {
        DatrisEnvironment.values = InMemoryUserDb.env().copy(useApiKeys = true)
        val shapes = Seq(
            "sales" -> RoleToolSets.stored("ci_scoped", RoleToolSets.CatalogSalesCaps: _*),
            "owner" -> RoleToolSets.stored("rag-builder", RoleToolSets.OwnerSelfCaps: _*),
            "full" -> RoleToolSets.stored("full", "*:*"),
            "legacy" -> legacy,
            "upper" -> ResolvedKey(None, "UI", Seq(Capability.FullAccess), isLegacyFullAccess = true),
            "padded" -> ResolvedKey(None, "ui ", Seq(Capability.FullAccess), isLegacyFullAccess = true),
            "sessionlike" -> ResolvedKey(None, "session:root", Capability.parseList(Seq("pipeline:read")), isLegacyFullAccess = false)
        )
        for ((value, rk) <- shapes; claimed <- Seq("root", "ed", "vic")) {
            seedKey(value + "-value", rk)
            val (got, attrs, _) =
                through("x-api-key" -> (value + "-value"), AuditActor.HeaderOnBehalfOf -> claimed, PolicyReplay.HeaderApproval -> "pa_1.tok")
            assert(got.contains(rk), s"$value claiming $claimed: $got")
            assert(!attrs.contains(AuditActor.OnBehalfOfAttr), s"$value claiming $claimed")
        }
        // a live tap-run token is never a carrier
        val t = TapRunTokens.issue("ci_probe", None, 60)
        try {
            val (got, attrs, _) = through("x-api-key" -> t, AuditActor.HeaderOnBehalfOf -> "root")
            assert(got.exists(_.label == "tap:ci_probe"), String.valueOf(got))
            assert(!attrs.contains(AuditActor.OnBehalfOfAttr))
        } finally TapRunTokens.revoke(t)
    }

    // ================================================================ every tool call, not only the listing

    private def assumeEnforce(): Unit =
        assume(!sys.env.get("CAPABILITY_ENFORCEMENT").exists(_.equalsIgnoreCase("log-only")), "log-only set in this environment")

    /** A tool's own route, called with the chat's headers, through the real
      * TenantInterceptor and CapabilityInterceptor. */
    private def callTool(tool: String, user: String, extra: (String, String)*): (Boolean, Captured, Option[ResolvedKey]) = {
        val MCPToolRoutes.Mapped(m, p) = MCPToolRoutes.tools.toMap.apply(tool)
        val (r, attrs) = request(m, p, (AuditActor.HeaderOnBehalfOf -> user) +: extra: _*)
        val c = new Captured
        assert(new TenantInterceptor().preHandle(r, c.response, null))
        val ok = new CapabilityInterceptor().preHandle(r, c.response, null)
        (ok, c, resolved(attrs))
    }

    test("a tool hidden from an editor's or viewer's list and called directly by name is refused with the capability-denied 403") {
        assumeEnforce()
        for ((u, visible) <- Seq(ed -> RoleToolSets.Editor, vic -> RoleToolSets.Viewer); tool <- MCPToolRoutes.allToolNames.filterNot(visible.contains)) {
            val MCPToolRoutes.Mapped(m, p) = MCPToolRoutes.tools.toMap.apply(tool)
            val RouteCheck.Require(res, act) = CapabilityRoutes.lookup(m, p)
            val (ok, c, rk) = callTool(tool, u.username)
            assert(!ok, s"${u.username} called $tool")
            assert(c.status.contains(403), s"${u.username} $tool")
            assert(c.body == CapabilityInterceptor.deniedBody("session:" + u.username, res, act, m, p, None), s"${u.username} $tool: ${c.body}")
            assert(rk.exists(_.label == "session:" + u.username))
        }
    }

    test("unchanged: an admin's direct call to any tool's route passes the capability gate") {
        assumeEnforce()
        for (tool <- MCPToolRoutes.allToolNames if MCPToolRoutes.tools.toMap.apply(tool) != MCPToolRoutes.Local) {
            val (ok, c, _) = callTool(tool, "root")
            assert(ok, s"$tool: ${c.body}")
        }
    }

    // ================================================================ Decision 5

    test("keys off + user auth on: chat tools are held to the editor bundle while the editor's own REST session is not (deliberate asymmetry)") {
        assumeEnforce()
        val heldBack = Seq("list_incidents", "get_incident", "list_pending_approvals", "get_agent_policy", "upload_config", "list_tap_secrets")
        // chat half: the ui carrier (keys off: anonymous) on behalf of the editor
        for (tool <- heldBack) {
            val (ok, c, _) = callTool(tool, "ed")
            assert(!ok && c.status.contains(403), s"chat $tool: ${c.body}")
        }
        // REST half: the editor's own cookie request, no header: the anonymous identity is kept
        for (tool <- heldBack) {
            val MCPToolRoutes.Mapped(m, p) = MCPToolRoutes.tools.toMap.apply(tool)
            val (r, attrs) = request(m, p)
            val c = new Captured
            assert(new TenantInterceptor().preHandle(r, c.response, null))
            UserContext.set(ed)
            try assert(new CapabilityInterceptor().preHandle(r, c.response, null), s"REST $tool: ${c.body}")
            finally UserContext.clear()
            assert(resolved(attrs).contains(anonymous), tool)
        }
    }

    // ================================================================ Step 3: approval replay

    // plans/stories/approval-replay-requester-scope.md: an approval header
    // that does not verify (here: the agent policy is off and no such
    // approval exists) grants nothing; the ordinary on-behalf-of identity.
    test("keys off: an unverified approval header on behalf of an editor yields the editor's bundle; a restricted approver stays narrowed") {
        val (got, _, _) = through(AuditActor.HeaderOnBehalfOf -> "ed", PolicyReplay.HeaderApproval -> "pa_1.tok")
        assert(got.contains(obo(anonymous, ed)), String.valueOf(got))
        val (bobGot, _, _) = through(AuditActor.HeaderOnBehalfOf -> "bob", PolicyReplay.HeaderApproval -> "pa_1.tok")
        assert(bobGot.contains(obo(anonymous, bob)), String.valueOf(bobGot))
    }

    // ================================================================ stricter reading (OPEN QUESTION)

    // With keys off every request is a trusted carrier, so a RESTRICTED user's
    // own browser request can carry X-Datris-On-Behalf-Of. TenantInterceptor
    // runs before SessionAuthenticator and relabels the anonymous identity to
    // the named user; CapabilityInterceptor.narrowForRestrictedSession then
    // only replaces an identity labelled `anonymous`, so the restricted user
    // is no longer held to its grants. Fails today and after the story as
    // written; kept to the catalog-scoped-user-access decision (a restricted
    // user is held to its grants on every path).
    test("stricter reading (open question): keys off, a restricted user's own session request naming another user stays held to its own grants") {
        for (claimed <- Seq("root", "ed", "vic")) {
            val (r, attrs) = request("GET", "/api/v1/pipelines", AuditActor.HeaderOnBehalfOf -> claimed)
            assert(new TenantInterceptor().preHandle(r, new Captured().response, null))
            UserContext.set(bob) // SessionAuthenticator: bob's cookie
            try new CapabilityInterceptor().narrowForRestrictedSession(r)
            finally UserContext.clear()
            val rk = resolved(attrs)
            assert(rk.exists(k => k.restrictedSession && k.label == "session:bob"), s"bob claiming $claimed ends up as $rk")
        }
    }
}
