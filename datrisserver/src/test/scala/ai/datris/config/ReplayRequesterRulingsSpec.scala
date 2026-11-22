package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditActor, AuditEntry, AuditLog}
import ai.datris.auth.VersionActor
import ai.datris.config.ChatAuthorityFixtures._
import ai.datris.config.ReplayScopeFixtures._
import ai.datris.model.{DatrisEnvironment, ResolvedKey, TenantContext, UserContext}
import ai.datris.policy.{AgentPolicy, PendingAction, PolicyIO, PolicyReplay}
import ai.datris.util.{APIKeyValidator, InMemoryUserDb, UserStore}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.JavaConverters._

/** Story: an approved agent action runs with the requester's access
  * (plans/stories/approval-replay-requester-scope.md), the points the lead
  * settled beside the story's own specs: how a requester's key is matched
  * when one side holds no key id, the answers a replay gets when its
  * requester cannot be checked or no longer exists, the version author of
  * a replay, and the record of an ignored incident header. */
class ReplayRequesterRulingsSpec extends AnyFunSuite with BeforeAndAfterEach {

    private var saved: DatrisEnvironment = _
    private var db: InMemoryUserDb = _
    private var store: MemoryStore = _
    private val audit = new ConcurrentLinkedQueue[AuditEntry]()
    @volatile private var keys: Map[String, ResolvedKey] = liveKeys

    private val Token = "tok-rulings-0001"

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        DatrisEnvironment.values = InMemoryUserDb.env(useAuditLog = true).copy(useApiKeys = true, useAgentPolicy = true)
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

    private case class Outcome(tenant: Boolean, policy: Boolean, resp: Resp, r: jakarta.servlet.http.HttpServletRequest, identity: Option[ResolvedKey])

    private def replay(pa: PendingAction, approver: String = "ed"): Outcome = {
        store.put(pa)
        seedKey(UiValue, uiLegacy)
        val headers = Seq("x-api-key" -> UiValue, AuditActor.HeaderOnBehalfOf -> approver, PolicyReplay.HeaderApproval -> (pa.id + "." + Token))
        val (r, attrs) = call(pa.method, pa.path, pa.query, pa.body, headers)
        val resp = new Resp
        val t = new TenantInterceptor().preHandle(r, resp.response, null)
        // the application's order (WebMvcConfig): capability, policy, role
        val p = t && new CapabilityInterceptor().preHandle(r, resp.response, handler) &&
            new PolicyInterceptor().preHandle(r, resp.response, handler) &&
            new RoleEnforcementInterceptor().preHandle(r, resp.response, handler)
        Outcome(t, p, resp, r, resolved(attrs))
    }

    private def deleteAction(id: String, actor: ai.datris.audit.AuditActorInfo) =
        approved(id, actor, Token, query = Some(deleteQuery("sales_orders")))

    private def assertNotRun(o: Outcome, status: Int, body: String): Unit = {
        assert(!o.tenant, "the replay must not reach a handler")
        assert(o.resp.status == status, o.resp.status + " " + o.resp.body)
        assert(o.resp.body == body, o.resp.body)
        assert(o.identity.isEmpty, String.valueOf(o.identity))
        assert(store.consumed.isEmpty, "the token is not used")
    }

    // ================================================================ key identity match

    test("a key queued with no key id that has one now (same label) runs with its current access") {
        val queued = salesDeleter.copy(keyId = None)
        val o = replay(deleteAction("pa_noid", keyActor(queued)))
        assert(o.tenant && o.policy, o.resp.body)
        assert(o.identity.contains(salesDeleter), String.valueOf(o.identity))
    }

    test("a key queued with a key id whose current record has none (same label) runs with its current access") {
        val current = salesDeleter.copy(keyId = None)
        keys = liveKeys + (salesDeleter.label -> current)
        val o = replay(deleteAction("pa_nowid", keyActor(salesDeleter)))
        assert(o.tenant && o.policy, o.resp.body)
        assert(o.identity.contains(current), String.valueOf(o.identity))
    }

    test("the key's current access is used, not what it held when the action was queued") {
        val narrowed = salesDeleter.copy(capabilities = ai.datris.model.Capability.parseList(Seq("pipeline:read:catalog=Sales")))
        keys = liveKeys + (salesDeleter.label -> narrowed)
        val o = replay(deleteAction("pa_now", keyActor(salesDeleter)))
        assert(o.identity.contains(narrowed), String.valueOf(o.identity))
    }

    // ================================================================ refusals before anything runs

    test("a revoked requesting key: 401 with a plain body that names no key") {
        keys = liveKeys - "sales-deleter"
        val o = replay(deleteAction("pa_rev", keyActor(salesDeleter)))
        assertNotRun(o, 401, TenantInterceptor.RequesterKeyInvalidBody)
        assert(!o.resp.body.contains("sales-deleter"))
    }

    test("a deleted chat user: 401 with a body that says the user no longer exists") {
        db.rows.remove("olga")
        assertNotRun(replay(deleteAction("pa_gone", chatActor(olga)), approver = "root"), 401, TenantInterceptor.RequesterUserGoneBody)
    }

    test("a key store that cannot be read: 503, the requester could not be checked, nothing runs and the token is not used") {
        APIKeyValidator.resolveByLabelOverride = (_: String) => throw new IllegalStateException("down")
        assertNotRun(replay(deleteAction("pa_keys_down", keyActor(salesDeleter))), 503, TenantInterceptor.RequesterUncheckedBody)
    }

    test("a user store that fails on the requester's lookup: 503, nothing runs and the token is not used") {
        val failing = new InMemoryUserDb {
            override def getItemJSON(tableName: String, keyName: String, key: String, valueName: String): Option[String] =
                if (key == "olga") throw new IllegalStateException("down") else super.getItemJSON(tableName, keyName, key, valueName)
        }
        users.foreach(failing.put)
        UserStore.dbOverride = failing
        assertNotRun(replay(deleteAction("pa_users_down", chatActor(olga)), approver = "root"), 503, TenantInterceptor.RequesterUncheckedBody)
    }

    test("the refusal is recorded with the approval id and the requester") {
        keys = liveKeys - "sales-deleter"
        replay(deleteAction("pa_rec", keyActor(salesDeleter)))
        val denied = audit.asScala.toList.filter(e => e.category == "security" && e.action == "denied")
        assert(denied.size == 1, audit.toString)
        val md = denied.head.metadata.get
        assert(md.get("approvalId").getAsString == "pa_rec" && md.get("originalActor").getAsString == "sales-deleter", md.toString)
    }

    // ================================================================ attribution

    test("a version written by a replay names the approver, while the owner label is the requester's") {
        val o = replay(deleteAction("pa_ver", keyActor(salesDeleter)))
        assert(o.tenant && o.policy)
        assert(VersionActor.resolve(o.r) == "session:ed")
        assert(ai.datris.auth.ResolvedKeyAccess.keyLabel(o.r).contains("sales-deleter"))
    }

    test("an ignored incident header is recorded with the claimed id as claimedIncident, never as incidentId") {
        val etl = ResolvedKey(None, "etl-agent", ai.datris.model.Capability.parseList(Seq("tap:run")), isLegacyFullAccess = false)
        seedKey("etl-value", etl)
        val (r, _) =
            call("POST", "/api/v1/tap/run", body = Some("{\"name\":\"t1\"}"), headers = Seq("x-api-key" -> "etl-value", AuditActor.HeaderIncident -> "inc_7"))
        assert(new TenantInterceptor().preHandle(r, new Resp().response, null))
        val spoof = audit.asScala.toList.filter(_.action == "spoofed-incident")
        assert(spoof.size == 1)
        val md = spoof.head.metadata.get
        assert(md.get("claimedIncident").getAsString == "inc_7" && !md.has("incidentId"), md.toString)
        assert(r.getAttribute(AuditLog.RecordedAttr) == null, "the request's own audit row is still written")
    }

    // ================================================================ an approval that cannot be checked

    test("an approval store that cannot be read: 503, the requester could not be checked, nothing runs and the token is not used") {
        val pa = deleteAction("pa_store_down", keyActor(salesDeleter))
        store.put(pa)
        store.failReads = true
        val o = replay(pa)
        assertNotRun(o, 503, TenantInterceptor.RequesterUncheckedBody)
        val denied = audit.asScala.toList.filter(e => e.category == "security" && e.action == "denied")
        assert(denied.size == 1, audit.toString)
    }

    test("an approval store that cannot be read, sign-in off: the same 503, nothing runs") {
        DatrisEnvironment.values = DatrisEnvironment.values.copy(useUserAuth = false)
        val pa = deleteAction("pa_store_down_off", keyActor(salesDeleter))
        store.put(pa)
        store.failReads = true
        assertNotRun(replay(pa), 503, TenantInterceptor.RequesterUncheckedBody)
    }

    // ================================================================ the check and the use of the token agree

    /** The approval presented to PolicyInterceptor alone, by the platform's
      * `ui` key on behalf of ed, with `verified` as the record
      * TenantInterceptor left (None: no record). */
    private def policyOnly(pa: PendingAction, verified: Option[TenantInterceptor.VerifiedReplay]): (Boolean, Resp) = {
        store.put(pa)
        val headers = Seq("x-api-key" -> UiValue, AuditActor.HeaderOnBehalfOf -> "ed", PolicyReplay.HeaderApproval -> (pa.id + "." + Token))
        val (r, attrs) = call(pa.method, pa.path, pa.query, pa.body, headers)
        attrs.put(TenantInterceptor.ResolvedKeyAttr, TenantInterceptor.onBehalfOfIdentity(uiLegacy, ed))
        attrs.put(AuditActor.CarrierKeyLabelAttr, "ui")
        attrs.put(AuditActor.OnBehalfOfAttr, ed)
        verified.foreach(v => attrs.put(TenantInterceptor.ReplayVerifiedAttr, v))
        val resp = new Resp
        (new PolicyInterceptor().preHandle(r, resp.response, handler), resp)
    }

    test("a replay on behalf of a user that the tenant check did not verify is refused and its token is not used") {
        val (ok, resp) = policyOnly(deleteAction("pa_unverified", keyActor(salesDeleter)), None)
        assert(!ok && resp.status == 403, resp.status + " " + resp.body)
        assert(resp.body.contains("policy_replay_rejected"), resp.body)
        assert(store.consumed.isEmpty, "the token was used")
        assert(store.docs("pa_unverified").replayConsumedAt.isEmpty)
    }

    test("a replay whose tenant check verified another approval is refused and its token is not used") {
        val other = TenantInterceptor.VerifiedReplay("pa_other", uiLegacy, Some(ed))
        val (ok, resp) = policyOnly(deleteAction("pa_mismatch", keyActor(salesDeleter)), Some(other))
        assert(!ok && resp.status == 403, resp.status + " " + resp.body)
        assert(store.consumed.isEmpty, "the token was used")
    }

    test("the replay the tenant check verified for this approval uses its token once") {
        val verified = TenantInterceptor.VerifiedReplay("pa_match", uiLegacy, Some(ed))
        val (ok, resp) = policyOnly(deleteAction("pa_match", keyActor(salesDeleter)), Some(verified))
        assert(ok, resp.status + " " + resp.body)
        assert(store.consumed.toList == List("pa_match"))
    }

    // ================================================================ where the earlier labels stay

    test("keys off: a replay keeps the approver's label for what it creates and for the version author, with full access") {
        DatrisEnvironment.values = DatrisEnvironment.values.copy(useApiKeys = false)
        val anon = ai.datris.audit.AuditActorInfo("api-key", AuditActor.Anonymous, keyLabel = Some(AuditActor.Anonymous), legacyFullAccess = true)
        val o = replay(deleteAction("pa_off_label", anon))
        assert(o.tenant && o.policy, o.resp.body)
        assert(o.identity.contains(anonymous.copy(label = "session:ed")), String.valueOf(o.identity))
        assert(ai.datris.auth.ResolvedKeyAccess.keyLabel(o.r).contains("session:ed"))
        assert(VersionActor.resolve(o.r) == "session:ed")
    }

    test("keys off: a chat's approved action keeps the earlier access and label (the API is open)") {
        DatrisEnvironment.values = DatrisEnvironment.values.copy(useApiKeys = false)
        val o = replay(deleteAction("pa_off_chat", chatActor(ed)), approver = "root")
        assert(o.tenant && o.policy, o.resp.body)
        assert(o.identity.contains(anonymous.copy(label = "session:root")), String.valueOf(o.identity))
    }

    test("the platform's own key as the requester: the replay keeps the approver's label and the carrier's access") {
        val o = replay(deleteAction("pa_ui_label", keyActor(uiLegacy)))
        assert(o.tenant && o.policy, o.resp.body)
        assert(o.identity.contains(uiLegacy.copy(label = "session:ed")), String.valueOf(o.identity))
        assert(ai.datris.auth.ResolvedKeyAccess.keyLabel(o.r).contains("session:ed"))
        assert(VersionActor.resolve(o.r) == "session:ed")
    }

    test("the platform's own key as the requester with a restricted approver: held to the approver's grants, as before") {
        val o = replay(deleteAction("pa_ui_bob", keyActor(uiLegacy)), approver = "bob")
        assert(o.tenant, o.resp.body)
        assert(o.identity.contains(TenantInterceptor.onBehalfOfIdentity(uiLegacy, bob)), String.valueOf(o.identity))
    }

    test("a key requester's replay keeps the requester's label for what it creates, while the version names the approver") {
        val o = replay(deleteAction("pa_key_label", keyActor(salesDeleter)))
        assert(o.tenant && o.policy, o.resp.body)
        assert(ai.datris.auth.ResolvedKeyAccess.keyLabel(o.r).contains("sales-deleter"))
        assert(VersionActor.resolve(o.r) == "session:ed")
    }
}
