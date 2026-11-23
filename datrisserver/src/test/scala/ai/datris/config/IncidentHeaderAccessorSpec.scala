package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditActor
import ai.datris.config.ChatAuthorityFixtures._
import ai.datris.incident.RecoveryKey
import ai.datris.model.{Capability, DatrisEnvironment, ResolvedKey, TenantContext, UserContext}
import ai.datris.util.{APIKeyValidator, InMemoryUserDb, UserStore}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

/** Story: an approved agent action runs with the requester's access, and
  * internal headers count only from the platform's own callers
  * (plans/stories/approval-replay-requester-scope.md), Acceptance bullet 5,
  * Design 3 and Files (AuditActor): the trust rule and the one accessor
  * every reader of the incident header uses (the audit row, the policy's
  * recovery mode, the queued action, the tap version note).
  *
  * Assumed:
  * {{{
  * AuditActor.trustsIncident(keyLabel: String, useApiKeys: Boolean): Boolean
  * AuditActor.incidentOf(request: HttpServletRequest): Option[String]
  * }}}
  * `incidentOf` answers the trimmed header (at most 64 characters, as every
  * reader keeps it today) when the request's carrier is trusted, else None.
  * The carrier is the key the request presented: for a chat request that is
  * the `ui` key, even though the identity attached for it is the user's. */
class IncidentHeaderAccessorSpec extends AnyFunSuite with BeforeAndAfterEach {

    private var saved: DatrisEnvironment = _

    private val etl = ResolvedKey(None, "etl-agent", Capability.parseList(Seq("tap:read", "tap:run")), isLegacyFullAccess = false, keyId = Some("k_etl"))
    private val recovery =
        ResolvedKey(None, RecoveryKey.Label, Capability.parseList(RecoveryKey.Capabilities), isLegacyFullAccess = false, keyId = Some("k_rec"))

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        DatrisEnvironment.values = InMemoryUserDb.env().copy(useApiKeys = true)
        val db = new InMemoryUserDb
        allUsers.foreach(db.put)
        UserStore.dbOverride = db
        APIKeyValidator.invalidateCache()
        UserContext.clear()
        TenantContext.clear()
    }

    override def afterEach(): Unit = {
        UserStore.dbOverride = null
        APIKeyValidator.invalidateCache()
        UserContext.clear()
        TenantContext.clear()
        DatrisEnvironment.values = saved
    }

    test("the platform's own callers are trusted with the incident header: the ui key, the recovery key, and anyone with keys off") {
        assert(AuditActor.trustsIncident(AuditActor.UiKeyLabel, useApiKeys = true))
        assert(AuditActor.trustsIncident(RecoveryKey.Label, useApiKeys = true))
        for (l <- Seq("etl-agent", "anonymous", "session:root", "tap:t1", "UI", " ui", "ui ", "recovery-agent-2", "Recovery-Agent", "", null))
            assert(AuditActor.trustsIncident(l, useApiKeys = false), "keys off: " + l)
    }

    test("no other key is trusted with the incident header") {
        for (l <- Seq("etl-agent", "anonymous", "session:root", "session:ui", "tap:t1", "UI", " ui", "ui ", "recovery-agent-2", "Recovery-Agent", "", null))
            assert(!AuditActor.trustsIncident(l, useApiKeys = true), String.valueOf(l))
    }

    /** The request after TenantInterceptor attached its identity. */
    private def through(value: String, rk: ResolvedKey, headers: (String, String)*) = {
        seedKey(value, rk)
        val (r, _) = request("POST", "/api/v1/tap/run", (("x-api-key" -> value) +: headers): _*)
        assert(new TenantInterceptor().preHandle(r, new Captured().response, null))
        r
    }

    test("a customer key's incident header reads as no incident") {
        assert(AuditActor.incidentOf(through("etl-value", etl, AuditActor.HeaderIncident -> "inc_7")).isEmpty)
    }

    test("the recovery key's and the ui key's incident header is read, trimmed and kept to 64 characters") {
        assert(AuditActor.incidentOf(through("rec-value", recovery, AuditActor.HeaderIncident -> " inc_7 ")).contains("inc_7"))
        assert(AuditActor.incidentOf(through(UiValue, uiLegacy, AuditActor.HeaderIncident -> "inc_8")).contains("inc_8"))
        assert(AuditActor.incidentOf(through("rec-value", recovery, AuditActor.HeaderIncident -> ("x" * 100))).contains("x" * 64))
        assert(AuditActor.incidentOf(through("rec-value", recovery, AuditActor.HeaderIncident -> "  ")).isEmpty)
        assert(AuditActor.incidentOf(through("rec-value", recovery)).isEmpty)
    }

    test("a chat's incident header is read by its carrier (the ui key), whichever user it acts for") {
        for (u <- Seq("root", "ed", "vic", "bob")) {
            val r = through(UiValue, uiLegacy, AuditActor.HeaderOnBehalfOf -> u, AuditActor.HeaderIncident -> "inc_9")
            assert(AuditActor.incidentOf(r).contains("inc_9"), u)
        }
    }

    test("a customer key naming a user and an incident gains neither") {
        val r = through("etl-value", etl, AuditActor.HeaderOnBehalfOf -> "root", AuditActor.HeaderIncident -> "inc_7")
        assert(AuditActor.incidentOf(r).isEmpty)
    }

    test("with keys off the header is read from any caller") {
        DatrisEnvironment.values = InMemoryUserDb.env().copy(useApiKeys = false)
        val (r, _) = request("POST", "/api/v1/tap/run", AuditActor.HeaderIncident -> "inc_7")
        assert(new TenantInterceptor().preHandle(r, new Captured().response, null))
        assert(AuditActor.incidentOf(r).contains("inc_7"))
    }
}
