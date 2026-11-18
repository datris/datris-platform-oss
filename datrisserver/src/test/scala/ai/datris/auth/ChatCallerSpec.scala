package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.api.ApiErrors
import ai.datris.config.CapabilityInterceptor
import ai.datris.model.{Capability, ResolvedKey, User}
import ai.datris.util.APIKeyValidator
import org.scalatest.funsuite.AnyFunSuite

/** Story: Chat endpoints act with the caller's own authority, and refuse
  * tap-run tokens (plans/stories/chat-endpoints-caller-identity.md).
  *
  * `ChatCaller.decide(sessionUser, onBehalfOf, resolved, rejection)` is the
  * one decision every chat endpoint makes on the servlet thread:
  *   1. tap-run token -> 403, in every mode;
  *   2. session user, or the `ui` key on behalf of a trusted user -> that user, `ui` key;
  *   3. full-access identity -> `ui` key, no user;
  *   4. any other key -> its own presented key;
  *   5. no identity / rejected key -> 401, store outage -> 503.
  */
class ChatCallerSpec extends AnyFunSuite {

    private def user(name: String, role: String, restricted: Boolean, grants: (String, String)*): User = {
        val m = new java.util.LinkedHashMap[String, String]()
        grants.foreach { case (k, v) => m.put(k, v) }
        User(
            username = name,
            passwordHash = "x",
            role = role,
            createdAt = "2026-11-01T00:00:00Z",
            updatedAt = "2026-11-01T00:00:00Z",
            lastLoginAt = null,
            restricted = restricted,
            catalogGrants = if (grants.isEmpty) null else m
        )
    }

    private val bob: User = user("bob", User.RoleEditor, restricted = true, "Sales" -> "edit")
    private val alice: User = user("alice", User.RoleEditor, restricted = false)
    private val root: User = user("root", User.RoleAdmin, restricted = false)

    private def key(label: String, caps: String*): ResolvedKey =
        ResolvedKey(None, label, Capability.parseList(caps), isLegacyFullAccess = false)

    private val tapKey: ResolvedKey =
        ResolvedKey(None, "tap:t1", TapRunTokens.Capabilities, isLegacyFullAccess = false)
    private val legacyKey: ResolvedKey =
        ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true)
    private val uiKey: ResolvedKey =
        ResolvedKey(None, "ui", Seq(Capability.FullAccess), isLegacyFullAccess = true)
    private val anonymous: ResolvedKey =
        ResolvedKey(None, "anonymous", Seq(Capability.FullAccess), isLegacyFullAccess = true)
    private val multiTenant: ResolvedKey =
        ResolvedKey(Some("tenant-a"), "tenant-a", Seq(Capability.FullAccess), isLegacyFullAccess = true)
    private val starMetaKey: ResolvedKey = key("star-meta", "*:*")
    private val limitedKey: ResolvedKey = key("ops-ro", "pipeline:read", "tap:read")
    private val catalogKey: ResolvedKey = key("ci_scoped", "pipeline:read:catalog=Sales", "tap:read:catalog=Sales")
    private val ownerKey: ResolvedKey = key("rag-builder", "pipeline:create", "pipeline:read:owner=self", "tap:run:owner=self")
    private val scopedStarKey: ResolvedKey = key("scoped-star", "*:*:catalog=Sales")
    private val incidentKey: ResolvedKey = key("ops-inc", "incident:read", "pipeline:read")
    private val scopedIncidentKey: ResolvedKey = key("ops-inc-sales", "incident:read:catalog=Sales", "pipeline:read")

    private def session(u: User): ResolvedKey = APIKeyValidator.resolveFromSession(u)

    /** The ui key relabelled for `u`, as TenantInterceptor.applyOnBehalfOf leaves it. */
    private def onBehalfOf(u: User): ResolvedKey =
        if (u.isRestricted) session(u)
        else uiKey.copy(label = "session:" + u.username)

    private def allowed(d: ChatDecision): ChatIdentity = d match {
        case Allow(id) => id
        case other => fail(s"expected Allow, got $other")
    }

    private def refused(d: ChatDecision): Refuse = d match {
        case r: Refuse => r
        case other => fail(s"expected Refuse, got $other")
    }

    private def keyCaller(rk: ResolvedKey): ChatIdentity = allowed(ChatCaller.decide(None, None, Some(rk), None))
    private def sessionCaller(u: User): ChatIdentity = allowed(ChatCaller.decide(Some(u), None, Some(session(u)), None))

    // Acceptance 1
    test("a tap-run token is refused with 403 in every mode") {
        assert(refused(ChatCaller.decide(None, None, Some(tapKey), None)).status == 403)
        Seq(root, alice, bob).foreach { u =>
            assert(refused(ChatCaller.decide(Some(u), None, Some(tapKey), None)).status == 403, s"session ${u.username}")
            assert(refused(ChatCaller.decide(None, Some(u), Some(tapKey), None)).status == 403, s"on behalf of ${u.username}")
        }
    }

    test("a real minted tap-run token is refused with 403") {
        val t = TapRunTokens.issue("ci_probe", None, 60)
        try {
            val rk = TapRunTokens.resolve(t).get
            val r = refused(ChatCaller.decide(None, None, Some(rk), None))
            assert(r.status == 403)
            assert(r.body != null && r.body.nonEmpty)
        } finally TapRunTokens.revoke(t)
    }

    // Acceptance 2
    test("a session user keeps the ui key and is the acting user") {
        Seq(root, alice, bob).foreach { u =>
            val id = sessionCaller(u)
            assert(id.user.contains(u), u.username)
            assert(!id.ownKey, s"${u.username} must keep the ui key")
        }
    }

    // Acceptance 3
    test("the ui key on behalf of a trusted user acts as that user") {
        Seq(bob, alice, root).foreach { u =>
            val id = allowed(ChatCaller.decide(None, Some(u), Some(onBehalfOf(u)), None))
            assert(id.user.contains(u), u.username)
            assert(!id.ownKey, s"on behalf of ${u.username} must keep the ui key")
        }
    }

    // Acceptance 4
    test("a legacy full-access key, the anonymous keys-off identity and an unscoped *:* metadata key keep the ui key") {
        Seq(legacyKey, uiKey, anonymous, multiTenant, starMetaKey).foreach { rk =>
            assert(ChatCaller.isFullAccess(rk), rk.label)
            val id = keyCaller(rk)
            assert(!id.ownKey, s"${rk.label} must keep the ui key")
            assert(id.user.isEmpty, rk.label)
            assert(id.key.contains(rk), rk.label)
        }
    }

    // Acceptance 5
    test("a capability-limited key, a catalog= key and an owner=self key use their own key") {
        Seq(limitedKey, catalogKey, ownerKey, incidentKey, scopedIncidentKey).foreach { rk =>
            assert(!ChatCaller.isFullAccess(rk), rk.label)
            val id = keyCaller(rk)
            assert(id.ownKey, s"${rk.label} must use its own key")
            assert(id.user.isEmpty, rk.label)
            assert(id.key.contains(rk), rk.label)
        }
    }

    // Acceptance 6
    test("a scoped *:*:catalog=Sales key is not full access") {
        assert(!ChatCaller.isFullAccess(scopedStarKey))
        val id = keyCaller(scopedStarKey)
        assert(id.ownKey)
        assert(id.user.isEmpty)
    }

    // Acceptance 7
    test("no identity is 401; a rejected key is 401; a store outage is 503") {
        val none = refused(ChatCaller.decide(None, None, None, None))
        assert(none.status == 401)
        assert(none.body == ApiErrors.AuthenticationRequiredBody)

        Seq(APIKeyValidator.InvalidKeyMessage, "API key 'old' is revoked", APIKeyValidator.MissingKeyMessage).foreach { reason =>
            val r = refused(ChatCaller.decide(None, None, None, Some(reason)))
            assert(r.status == 401, reason)
            assert(r.body == CapabilityInterceptor.RejectedKeyBody, reason)
        }

        Seq(APIKeyValidator.MetadataUnavailableMessage, APIKeyValidator.KeyStoreUnavailableMessage).foreach { reason =>
            val r = refused(ChatCaller.decide(None, None, None, Some(reason)))
            assert(r.status == 503, reason)
            assert((r.status, r.body) == CapabilityInterceptor.rejectionResponse(reason), reason)
        }
    }

    // Acceptance 8
    test("toolKey returns the presented key for an own-key caller and never reads the ui secret") {
        Seq(limitedKey, catalogKey, ownerKey, scopedStarKey).foreach { rk =>
            val id = keyCaller(rk)
            val got = ChatCaller.toolKey(id, "presented-" + rk.label, () => fail("the ui secret must not be read for an own-key caller"))
            assert(got == "presented-" + rk.label)
        }
    }

    test("toolKey returns the ui key otherwise") {
        val ids = Seq(legacyKey, anonymous, starMetaKey).map(keyCaller) ++
            Seq(root, alice, bob).map(sessionCaller) ++
            Seq(allowed(ChatCaller.decide(None, Some(bob), Some(onBehalfOf(bob)), None)))
        ids.foreach { id =>
            assert(ChatCaller.toolKey(id, "presented", () => "ui-secret") == "ui-secret", s"$id")
        }
    }

    // Acceptance 9
    test(
        "incidents: unrestricted session yes, restricted session no, full-access key yes, incident:read key yes, incident:read:catalog=Sales key no, key without it no"
    ) {
        assert(ChatCaller.mayReadIncidents(sessionCaller(root)))
        assert(ChatCaller.mayReadIncidents(sessionCaller(alice)))
        assert(!ChatCaller.mayReadIncidents(sessionCaller(bob)))
        assert(!ChatCaller.mayReadIncidents(allowed(ChatCaller.decide(None, Some(bob), Some(onBehalfOf(bob)), None))))
        Seq(legacyKey, anonymous, starMetaKey).foreach(rk => assert(ChatCaller.mayReadIncidents(keyCaller(rk)), rk.label))
        assert(ChatCaller.mayReadIncidents(keyCaller(incidentKey)))
        assert(!ChatCaller.mayReadIncidents(keyCaller(scopedIncidentKey)))
        assert(!ChatCaller.mayReadIncidents(keyCaller(scopedStarKey)))
        Seq(limitedKey, catalogKey, ownerKey).foreach(rk => assert(!ChatCaller.mayReadIncidents(keyCaller(rk)), rk.label))
        assert(!ChatCaller.mayReadIncidents(ChatIdentity(None, None, ownKey = false)))
    }

    // Acceptance 10
    test("mayConfigure: full-access key yes; scoped key no; any session user no (the role gate owns that case)") {
        Seq(legacyKey, anonymous, starMetaKey).foreach(rk => assert(ChatCaller.mayConfigure(keyCaller(rk)), rk.label))
        Seq(limitedKey, catalogKey, ownerKey, scopedStarKey, incidentKey).foreach { rk =>
            assert(!ChatCaller.mayConfigure(keyCaller(rk)), rk.label)
        }
        Seq(root, alice, bob).foreach(u => assert(!ChatCaller.mayConfigure(sessionCaller(u)), u.username))
        assert(!ChatCaller.mayConfigure(ChatIdentity(None, None, ownKey = false)))
    }
}
