package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.api.PipelineAPIController
import ai.datris.audit.{AuditActor, AuditEntry, AuditLog}
import ai.datris.auth.{CapabilityCheck, ResolvedKeyAccess}
import ai.datris.config.ChatAuthorityFixtures._
import ai.datris.config.ReplayScopeFixtures._
import ai.datris.model.{DatrisEnvironment, PipelineConfig, ResolvedKey, TenantContext, UserContext}
import ai.datris.policy.{AgentPolicy, PendingAction, PolicyActor, PolicyIO, PolicyReplay}
import ai.datris.util.{APIKeyValidator, InMemoryUserDb, PipelineDeletePlan, UserStore}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.JavaConverters._
import scala.collection.mutable

/** Story: an approved agent action runs with the requester's access, and
  * internal headers count only from the platform's own callers
  * (plans/stories/approval-replay-requester-scope.md), Acceptance bullets 2,
  * 3 and 4, Design 2, Steps 2 and 3, owner decisions D1 (a), D2 and D4.
  *
  * Drives the real `TenantInterceptor.preHandle` and
  * `PolicyInterceptor.preHandle` on the replay of an approved action as
  * `PolicyReplay.execute` sends it (the platform's `ui` key, the approver as
  * the on-behalf-of user, the approval header), then the real controller
  * scope check of DELETE /pipeline (`PipelineAPIController.deleteRefusal`)
  * and the real `AuditInterceptor`.
  *
  * Assumed seam (story Files: `APIKeyValidator.resolveByLabel`):
  * `APIKeyValidator.resolveByLabelOverride: String => Option[ResolvedKey]`
  * (`@volatile private[datris] var`, null = the real read), answering a
  * label's current key, None for an unknown or revoked key; a throw is a
  * store failure. */
class ReplayRequesterIdentitySpec extends AnyFunSuite with BeforeAndAfterEach {

    private var saved: DatrisEnvironment = _
    private var db: InMemoryUserDb = _
    private var store: MemoryStore = _
    private val audit = new ConcurrentLinkedQueue[AuditEntry]()
    @volatile private var keys: Map[String, ResolvedKey] = liveKeys

    private val Token = "tok-replay-0001"

    private def keysOnEnv: DatrisEnvironment =
        InMemoryUserDb.env(useAuditLog = true).copy(useApiKeys = true, useAgentPolicy = true)

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        DatrisEnvironment.values = keysOnEnv
        db = new InMemoryUserDb
        users.foreach(db.put)
        UserStore.dbOverride = db
        APIKeyValidator.invalidateCache()
        keys = liveKeys
        APIKeyValidator.resolveByLabelOverride = (l: String) => keys.get(l)
        store = new MemoryStore
        PolicyInterceptor.store = store
        PolicyIO.currentOverride = AgentPolicy()
        audit.clear()
        AuditLog.sinkOverride = (e: AuditEntry) => { audit.add(e); () }
        UserContext.clear()
        TenantContext.clear()
    }

    override def afterEach(): Unit = {
        APIKeyValidator.resolveByLabelOverride = null
        PolicyInterceptor.store = PolicyInterceptor.MongoStore
        PolicyIO.currentOverride = null
        AuditLog.sinkOverride = null
        UserStore.dbOverride = null
        UserContext.clear()
        TenantContext.clear()
        APIKeyValidator.invalidateCache()
        DatrisEnvironment.values = saved
    }

    // ------------------------------------------------------------ the replay

    private case class Outcome(tenant: Boolean, policy: Boolean, resp: Resp, attrs: mutable.Map[String, AnyRef], r: jakarta.servlet.http.HttpServletRequest) {
        def identity: Option[ResolvedKey] = resolved(attrs)
    }

    /** The headers PolicyReplay.execute sends: the ui key (keys on), the
      * approver, `<id>.<token>`. */
    private def replayHeaders(id: String, approver: String, token: String = Token): Seq[(String, String)] =
        (if (DatrisEnvironment.values.useApiKeys) Seq("x-api-key" -> UiValue) else Nil) ++
            Seq(AuditActor.HeaderOnBehalfOf -> approver, PolicyReplay.HeaderApproval -> (id + "." + token))

    private def send(pa: PendingAction, headers: Seq[(String, String)]): Outcome = {
        if (DatrisEnvironment.values.useApiKeys) seedKey(UiValue, uiLegacy)
        val (r, attrs) = call(pa.method, pa.path, pa.query, pa.body, headers)
        val resp = new Resp
        val t = new TenantInterceptor().preHandle(r, resp.response, null)
        val p = t && new PolicyInterceptor().preHandle(r, resp.response, handler)
        Outcome(t, p, resp, attrs, r)
    }

    private def replay(pa: PendingAction, approver: String = "ed"): Outcome = {
        store.put(pa)
        send(pa, replayHeaders(pa.id, approver))
    }

    private def deleteAction(
        id: String,
        actor: ai.datris.audit.AuditActorInfo,
        pipeline: String,
        hashVersion: Option[Int] = Some(PendingAction.CurrentHashVersion)
    ) =
        approved(id, actor, Token, query = Some(deleteQuery(pipeline)), hashVersion = hashVersion)

    private val fin = PipelineConfig(name = "fin_ledger", catalog = "Finance", createdByKeyLabel = "someone")
    private val sales = PipelineConfig(name = "sales_orders", catalog = "Sales", createdByKeyLabel = "someone")
    private val full = PipelineDeletePlan.of("true", "true")

    /** The status DELETE /pipeline answers before deleting anything; 200 when it would delete. */
    private def deleteStatus(o: Outcome, cfg: PipelineConfig): Int =
        PipelineAPIController.deleteRefusal(o.r, cfg, full).map(_.getStatusCode.value).getOrElse(200)

    private def assertRuns(o: Outcome, what: String): Unit = {
        assert(o.tenant, what + ": refused by TenantInterceptor: " + o.resp.body)
        assert(o.policy, what + ": refused by PolicyInterceptor: " + o.resp.body)
    }

    private def assertRefused401(o: Outcome, what: String): Unit = {
        assert(!o.tenant, what + ": the replay must not reach a handler")
        assert(o.resp.status == 401, what + ": " + o.resp.status + " " + o.resp.body)
        assert(o.identity.isEmpty, what + ": no identity is attached: " + o.identity)
        assert(store.consumed.isEmpty, what + ": the token is not used")
    }

    // ================================================================ D1 (a): the requester's scope is the limit

    test(
        "an approved delete by a pipeline:delete:catalog=Sales key of a Finance pipeline answers the key's 403, also for an action queued before the upgrade"
    ) {
        for (hv <- Seq(Some(PendingAction.CurrentHashVersion), None)) {
            store.consumed.clear()
            val o = replay(deleteAction("pa_fin_" + hv.isDefined, keyActor(salesDeleter), fin.name, hv))
            assertRuns(o, "replay")
            assert(o.identity.map(_.label).contains("sales-deleter"), s"hashVersion=$hv: ${o.identity}")
            val refused = PipelineAPIController.deleteRefusal(o.r, fin, full)
            assert(refused.map(_.getStatusCode.value).contains(403), s"hashVersion=$hv: ${refused.map(_.getBody)}")
            // the key's own refusal: names the key and the capability, never the Finance catalog
            val body = refused.get.getBody
            assert(body.contains("sales-deleter") && body.contains("pipeline:delete"), body)
            assert(!body.contains("Finance"), body)
        }
    }

    test("unchanged: the same key's approved delete of a Sales pipeline runs; the audit row names the approver with originalActor the key") {
        val o = replay(deleteAction("pa_sales", keyActor(salesDeleter), sales.name))
        assertRuns(o, "replay")
        assert(deleteStatus(o, sales) == 200)
        new AuditInterceptor().preHandle(o.r, o.resp.response, handler)
        o.resp.status = 200
        new AuditInterceptor().afterCompletion(o.r, o.resp.response, handler, null)
        val rows = audit.asScala.toList.filter(e => e.category != "security")
        assert(rows.size == 1, rows.toString)
        val row = rows.head
        assert(row.actor.actorType == "user" && row.actor.label == "session:ed", row.actor.toString)
        val md = row.metadata.getOrElse(fail("no metadata"))
        assert(md.get("originalActor").getAsString == "sales-deleter", md.toString)
        assert(md.get("approvalId").getAsString == "pa_sales", md.toString)
        assert(md.get("approvedBy").getAsString == "ed", md.toString)
    }

    test("an unscoped key's approved delete runs with that key's own access") {
        val o = replay(deleteAction("pa_ops", keyActor(opsAgent), fin.name))
        assertRuns(o, "replay")
        assert(o.identity.map(_.capabilities).contains(opsAgent.capabilities), String.valueOf(o.identity))
        assert(deleteStatus(o, fin) == 200)
        assert(deleteStatus(o, sales) == 200)
    }

    test("unchanged: a key with no metadata (legacy full access) still runs an approved delete anywhere") {
        val o = replay(deleteAction("pa_old", keyActor(oldAgent), fin.name))
        assertRuns(o, "replay")
        assert(deleteStatus(o, fin) == 200)
    }

    test("a chat acting for an unrestricted editor, approved by an admin, runs with the editor's bundle, not the admin's") {
        val o = replay(deleteAction("pa_chat_ed", chatActor(ed), fin.name), approver = "root")
        assertRuns(o, "replay")
        val expected = TenantInterceptor.onBehalfOfIdentity(uiLegacy, ed, approvalReplay = false)
        assert(o.identity.map(_.capabilities).contains(expected.capabilities), String.valueOf(o.identity))
        assert(o.identity.exists(rk => !rk.isLegacyFullAccess && !rk.isFullAccess), String.valueOf(o.identity))
        // an editor may delete pipelines: the action itself still runs
        assert(deleteStatus(o, fin) == 200)
    }

    test("a chat acting for a restricted user, approved by an admin, is held to that user's catalog grants") {
        val outside = replay(deleteAction("pa_chat_olga_fin", chatActor(olga), fin.name), approver = "root")
        assertRuns(outside, "replay")
        assert(outside.identity.exists(_.restrictedSession), String.valueOf(outside.identity))
        val st = deleteStatus(outside, fin)
        assert(st == 404 || st == 403, "a Finance delete for a Sales-only user must not run: " + st)
        val inside = replay(deleteAction("pa_chat_olga_sales", chatActor(olga), sales.name), approver = "root")
        assertRuns(inside, "replay")
        assert(deleteStatus(inside, sales) == 200)
    }

    test("unchanged: a chat acting for an admin keeps the carrier's full access") {
        val o = replay(deleteAction("pa_chat_root", chatActor(root), fin.name), approver = "ed")
        assertRuns(o, "replay")
        assert(o.identity.exists(_.isFullAccess), String.valueOf(o.identity))
        assert(deleteStatus(o, fin) == 200)
    }

    test("a restricted approver's approval runs with the approver's own identity, as before, and is held to the approver's catalogs") {
        val o = replay(deleteAction("pa_bob", keyActor(oldAgent), fin.name), approver = "bob")
        assertRuns(o, "replay")
        assert(o.identity.contains(TenantInterceptor.onBehalfOfIdentity(uiLegacy, bob)), String.valueOf(o.identity))
        assert(deleteStatus(o, fin) != 200)
    }

    // ================================================================ D4: a requester that no longer exists

    test("a requesting key revoked since the request makes the replay fail with 401 before anything runs") {
        keys = liveKeys - "sales-deleter"
        assertRefused401(replay(deleteAction("pa_revoked", keyActor(salesDeleter), sales.name)), "revoked")
    }

    test("a key re-issued under the same label (new key id) is not the requester: 401") {
        keys = liveKeys + ("sales-deleter" -> salesDeleter.copy(keyId = Some("k_new")))
        assertRefused401(replay(deleteAction("pa_reissued", keyActor(salesDeleter), sales.name)), "re-issued")
    }

    test("a chat whose user was deleted since the request does not run: 401") {
        db.rows.remove("olga")
        assertRefused401(replay(deleteAction("pa_gone", chatActor(olga), sales.name), approver = "root"), "deleted user")
    }

    test("a key store that cannot be read never runs the replay with any identity") {
        APIKeyValidator.resolveByLabelOverride = (_: String) => throw new IllegalStateException("key store unavailable")
        val o = replay(deleteAction("pa_outage", keyActor(salesDeleter), sales.name))
        assert(!o.tenant, "the replay must not reach a handler")
        assert(o.identity.isEmpty, String.valueOf(o.identity))
    }

    // ================================================================ Acceptance bullet 4: only a verified replay changes the identity

    test("an unverifiable approval header with the ui key and an editor on-behalf-of yields the editor's role bundle, policy on or off") {
        for (policyOn <- Seq(true, false); keysOn <- Seq(true, false)) {
            DatrisEnvironment.values = keysOnEnv.copy(useAgentPolicy = policyOn, useApiKeys = keysOn)
            val carrier = if (keysOn) uiLegacy else anonymous
            val pa = deleteAction("pa_x", keyActor(opsAgent), fin.name)
            val o = send(pa, replayHeaders("junk", "ed", token = "junk"))
            val m = s"policy=$policyOn keys=$keysOn"
            assert(o.tenant, m)
            assert(o.identity.contains(TenantInterceptor.onBehalfOfIdentity(carrier, ed, approvalReplay = false)), m + ": " + o.identity)
        }
    }

    test("a valid token presented on a different request yields the ordinary on-behalf-of identity") {
        val pa = deleteAction("pa_other", keyActor(opsAgent), fin.name)
        store.put(pa)
        val other = pa.copy(method = "POST", path = "/api/v1/pipeline", query = None, body = Some("{\"name\":\"x\"}"))
        val o = send(other, replayHeaders(pa.id, "ed"))
        assert(o.tenant)
        assert(o.identity.contains(TenantInterceptor.onBehalfOfIdentity(uiLegacy, ed, approvalReplay = false)), String.valueOf(o.identity))
    }

    test("with the agent policy off a valid approval header grants nothing: the ordinary on-behalf-of identity") {
        DatrisEnvironment.values = keysOnEnv.copy(useAgentPolicy = false)
        val o = replay(deleteAction("pa_off", keyActor(opsAgent), fin.name))
        assert(o.tenant)
        assert(o.identity.contains(TenantInterceptor.onBehalfOfIdentity(uiLegacy, ed, approvalReplay = false)), String.valueOf(o.identity))
    }

    test("unchanged: a key that is not the platform's carrying the approval and on-behalf-of headers keeps its own identity, and the attempt is recorded") {
        seedKey("sales-value", salesDeleter)
        val pa = deleteAction("pa_spoof", keyActor(salesDeleter), fin.name)
        store.put(pa)
        val (r, attrs) = call(
            pa.method,
            pa.path,
            pa.query,
            None,
            Seq("x-api-key" -> "sales-value", AuditActor.HeaderOnBehalfOf -> "root", PolicyReplay.HeaderApproval -> (pa.id + "." + Token))
        )
        assert(new TenantInterceptor().preHandle(r, new Resp().response, null))
        assert(resolved(attrs).contains(salesDeleter))
        assert(audit.asScala.count(e => e.category == "security" && e.action == "spoofed-on-behalf-of") == 1)
        assert(PipelineAPIController.deleteRefusal(r, fin, full).map(_.getStatusCode.value).contains(403))
    }

    // ================================================================ D2: ownership of what an approval creates

    test("an item created by an approved owner=self key request is owned by that key's label; the audit actor is still the approver") {
        val create = approved(
            "pa_create",
            keyActor(ragBuilder),
            Token,
            method = "POST",
            path = CreatePath,
            body = Some("{\"name\":\"rag_docs\",\"catalog\":\"Sales\"}"),
            resourceName = Some("rag_docs"),
            action = "pipeline:create"
        )
        val o = replay(create)
        assertRuns(o, "replay")
        // the label the save paths stamp as createdByKeyLabel
        assert(ResolvedKeyAccess.keyLabel(o.r).contains("rag-builder"), String.valueOf(o.identity))
        // so the key can see what it asked for
        assert(CapabilityCheck.grants(o.r, "pipeline", "read", Map("owner" -> "rag-builder")))
        val actor = AuditActor.resolve(o.r)
        assert(actor.actorType == "user" && actor.label == "session:ed", actor.toString)
    }

    test("an item created through an approved chat request is owned by the chat's user, not the approver") {
        val create = approved(
            "pa_create_chat",
            chatActor(ed),
            Token,
            method = "POST",
            path = CreatePath,
            body = Some("{\"name\":\"ed_docs\",\"catalog\":\"Sales\"}"),
            resourceName = Some("ed_docs"),
            action = "pipeline:create"
        )
        val o = replay(create, approver = "root")
        assertRuns(o, "replay")
        assert(ResolvedKeyAccess.keyLabel(o.r).contains("session:ed"), String.valueOf(o.identity))
        assert(AuditActor.resolve(o.r).label == "session:root")
    }

    // ================================================================ approval flows that work today keep working

    test("unchanged: the replay of an approved action is a person's decision, not an agent's (person-only routes still run)") {
        val pa = approved(
            "pa_prompt",
            keyActor(promptAgent),
            Token,
            method = "POST",
            path = "/api/v1/tap-prompts",
            body = Some("{\"key\":\"suggested-1\",\"enabled\":true}"),
            resourceType = "tap",
            action = "tap:update"
        )
        val o = replay(pa)
        assertRuns(o, "replay")
        assert(!PolicyActor.isAgent(o.r), "a replayed approval would be refused by person-only routes (tap-prompt accept, incident abandon/proceed)")
    }

    test("unchanged: keys off, an approved action requested by the anonymous identity still runs with full access") {
        DatrisEnvironment.values = keysOnEnv.copy(useApiKeys = false)
        val anon = ai.datris.audit.AuditActorInfo("api-key", AuditActor.Anonymous, keyLabel = Some(AuditActor.Anonymous), legacyFullAccess = true)
        val o = replay(deleteAction("pa_anon", anon, fin.name))
        assertRuns(o, "replay")
        assert(deleteStatus(o, fin) == 200)
        assert(o.identity.exists(_.isFullAccess), String.valueOf(o.identity))
    }

    test("unchanged: an approved action requested under the ui key itself (no user) runs with the ui key's access") {
        keys = liveKeys + ("ui" -> uiLegacy)
        val o = replay(deleteAction("pa_ui", keyActor(uiLegacy), fin.name))
        assertRuns(o, "replay")
        assert(deleteStatus(o, fin) == 200)
    }

    // The recovery runner's approved actions: ApprovalReplayRequesterScopeSpec
    // replays the runner's own three calls (script save, tap run, script
    // regenerate) through the capability, policy and role gates.

    test("the same replay sent twice: the second is refused and never holds more than the ordinary on-behalf-of identity") {
        val pa = deleteAction("pa_twice", keyActor(salesDeleter), sales.name)
        assertRuns(replay(pa), "first")
        val second = send(pa, replayHeaders(pa.id, "ed"))
        assert(!(second.tenant && second.policy), "a used token must not run again")
        // whatever the second request was answered with, it never held more than the ordinary on-behalf-of identity
        second.identity.foreach { rk =>
            assert(rk == TenantInterceptor.onBehalfOfIdentity(uiLegacy, ed, approvalReplay = false) || rk.label == "sales-deleter", rk.toString)
        }
    }
}
